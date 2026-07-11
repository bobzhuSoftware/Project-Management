package com.pm.git;

import com.pm.project.Project;
import com.pm.project.ProjectRepository;
import com.pm.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Reads Git status via JGit; performs sync (add/commit/push) by invoking the
 * system `git` CLI so the user's existing credential helper / SSH key is reused.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitService {

    private static final Duration STATUS_TTL = Duration.ofSeconds(30);
    private static final int COMMIT_MESSAGE_MAX = 500;
    private static final long GIT_PROCESS_TIMEOUT_SECONDS = 120;
    private static final int DIFF_MAX_BYTES = 400_000;

    private final ProjectRepository projectRepo;

    private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();

    private record CachedStatus(GitStatusDto status, Instant cachedAt) {}

    public GitStatusDto status(String projectId) {
        return status(projectId, false, false);
    }

    public GitStatusDto status(String projectId, boolean forceRefresh) {
        return status(projectId, forceRefresh, false);
    }

    public GitStatusDto status(String projectId, boolean forceRefresh, boolean checkRemote) {
        if (!forceRefresh) {
            CachedStatus cached = cache.get(projectId);
            // When the caller wants a remote-verified status, only reuse a cached
            // entry that was itself remote-verified — otherwise recompute.
            if (cached != null
                    && Duration.between(cached.cachedAt, Instant.now()).compareTo(STATUS_TTL) < 0
                    && (!checkRemote || cached.status.remoteChecked)) {
                return cached.status;
            }
        }
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + projectId));
        GitStatusDto dto = computeStatus(p, checkRemote);
        cache.put(projectId, new CachedStatus(dto, Instant.now()));
        return dto;
    }

    public GitSyncResultDto sync(String projectId, String commitMessage) {
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + projectId));

        String message = sanitizeMessage(commitMessage);
        File root = new File(p.getRootDirectory());
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist: " + p.getRootDirectory());
        }
        if (!isGitRepo(root)) {
            throw new IllegalArgumentException("Not a git repository: " + p.getRootDirectory());
        }

        List<String> steps = new ArrayList<>();
        try {
            // 1. Check whether there is anything to commit
            GitStatusDto preStatus = computeStatus(p, false);
            boolean hasChanges = (preStatus.staged + preStatus.modified + preStatus.untracked + preStatus.conflicting) > 0;

            if (preStatus.conflicting > 0) {
                return GitSyncResultDto.fail(
                        "Repository has unresolved merge conflicts. Resolve them manually before syncing.",
                        steps, preStatus);
            }

            if (hasChanges) {
                runGit(root, steps, "add", "-A");
                runGit(root, steps, "commit", "-m", message);
            } else {
                steps.add("No local changes to commit.");
            }

            // 2. Refresh remote info, then check whether we are behind
            try {
                runGit(root, steps, "fetch", "--prune");
            } catch (GitCommandException fetchErr) {
                steps.add("[warn] fetch failed: " + fetchErr.getMessage());
            }

            GitStatusDto midStatus = computeStatus(p, false);
            cache.put(projectId, new CachedStatus(midStatus, Instant.now()));

            if (midStatus.behind > 0) {
                return GitSyncResultDto.fail(
                        "Remote has " + midStatus.behind + " new commit(s). Pull/merge manually before pushing.",
                        steps, midStatus);
            }

            if (midStatus.ahead > 0 || hasChanges) {
                runGit(root, steps, "push");
            } else {
                steps.add("Already up to date with remote — nothing to push.");
            }

            GitStatusDto postStatus = computeStatus(p, false);
            cache.put(projectId, new CachedStatus(postStatus, Instant.now()));
            return GitSyncResultDto.ok(steps, postStatus);

        } catch (GitCommandException e) {
            GitStatusDto curr = computeStatus(p, false);
            cache.put(projectId, new CachedStatus(curr, Instant.now()));
            return GitSyncResultDto.fail(e.getMessage(), steps, curr);
        }
    }

    /**
     * Returns the unified diff for a single changed file. {@code staged} selects
     * the index diff (git diff --cached) versus the working-tree diff (git diff).
     * Untracked files are rendered as an all-added diff via {@code --no-index}.
     */
    public GitDiffDto diff(String projectId, String path, boolean staged) {
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + projectId));

        File root = new File(p.getRootDirectory());
        if (!root.isDirectory() || !isGitRepo(root)) {
            throw new IllegalArgumentException("Not a git repository: " + p.getRootDirectory());
        }

        String rel = path == null ? "" : path.replace('\\', '/').trim();
        // Reject path traversal, absolute paths and drive-letter prefixes.
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("..") || rel.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }

        try {
            String out;
            if (isUntracked(root, rel)) {
                // git diff does not show untracked files; compare against the null device.
                out = runGitCapture(root, "diff", "--no-index", "--", nullDevice(), rel);
            } else if (staged) {
                out = runGitCapture(root, "diff", "--cached", "--", rel);
            } else {
                out = runGitCapture(root, "diff", "--", rel);
            }
            boolean truncated = out.length() >= DIFF_MAX_BYTES;
            boolean binary = out.contains("Binary files") || out.contains("GIT binary patch");
            return new GitDiffDto(rel, staged, binary, truncated, out);
        } catch (GitCommandException e) {
            throw new IllegalStateException("Failed to compute diff: " + e.getMessage());
        }
    }

    // --- internals ---

    private boolean isUntracked(File root, String rel) {
        try {
            String out = runGitCapture(root, "status", "--porcelain", "--", rel);
            return out.startsWith("??");
        } catch (GitCommandException e) {
            return false;
        }
    }

    private String nullDevice() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";
    }

    private GitStatusDto computeStatus(Project p, boolean checkRemote) {
        File root = new File(p.getRootDirectory());
        if (!root.isDirectory()) {
            return GitStatusDto.error("Root directory does not exist: " + p.getRootDirectory());
        }
        if (!isGitRepo(root)) {
            return GitStatusDto.notRepo();
        }

        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(root)
                .readEnvironment()
                .build();
             Git git = new Git(repo)) {

            GitStatusDto dto = new GitStatusDto();
            dto.repo = true;
            dto.branch = repo.getBranch();
            dto.remoteUrl = repo.getConfig().getString("remote", "origin", "url");
            dto.checkedAt = Instant.now();

            Status st = git.status().call();
            dto.staged = st.getAdded().size() + st.getChanged().size() + st.getRemoved().size();
            dto.modified = st.getModified().size() + st.getMissing().size();
            dto.untracked = st.getUntracked().size();
            dto.conflicting = st.getConflicting().size();

            st.getAdded().forEach(f      -> dto.files.add(new GitFileChange(f, "ADDED",     true)));
            st.getChanged().forEach(f    -> dto.files.add(new GitFileChange(f, "MODIFIED",  true)));
            st.getRemoved().forEach(f    -> dto.files.add(new GitFileChange(f, "DELETED",   true)));
            st.getModified().forEach(f   -> dto.files.add(new GitFileChange(f, "MODIFIED",  false)));
            st.getMissing().forEach(f    -> dto.files.add(new GitFileChange(f, "DELETED",   false)));
            st.getUntracked().forEach(f  -> dto.files.add(new GitFileChange(f, "UNTRACKED", false)));
            st.getConflicting().forEach(f -> dto.files.add(new GitFileChange(f, "CONFLICT", false)));

            String fullBranch = repo.getFullBranch();
            boolean onBranch = fullBranch != null && fullBranch.startsWith("refs/heads/");

            // Verify against the real remote so `behind` reflects reality instead of
            // a stale local tracking ref. Runs `git fetch` which updates the cached
            // remote-tracking ref that BranchTrackingStatus reads below.
            if (checkRemote && onBranch && dto.remoteUrl != null) {
                try {
                    fetchQuietly(root);
                    repo.getRefDatabase().refresh();
                    dto.remoteChecked = true;
                } catch (GitCommandException e) {
                    dto.remoteError = "Could not reach remote (offline or authentication required)";
                    log.debug("git fetch failed for project {}: {}", p.getName(), e.getMessage());
                }
            }

            if (onBranch) {
                BranchTrackingStatus tracking = BranchTrackingStatus.of(repo, dto.branch);
                if (tracking != null) {
                    dto.hasUpstream = true;
                    dto.ahead = tracking.getAheadCount();
                    dto.behind = tracking.getBehindCount();
                } else {
                    dto.hasUpstream = false;
                }
            }

            dto.clean = (dto.staged + dto.modified + dto.untracked + dto.conflicting) == 0;
            dto.inSync = dto.clean && dto.ahead == 0 && dto.behind == 0 && dto.hasUpstream;
            return dto;
        } catch (Exception e) {
            log.warn("git status failed for project {}: {}", p.getName(), e.toString());
            return GitStatusDto.error("Failed to read git status: " + e.getMessage());
        }
    }

    /** Runs {@code git fetch --prune}, discarding its step output. */
    private void fetchQuietly(File root) throws GitCommandException {
        runGit(root, new ArrayList<>(), "fetch", "--prune");
    }

    private boolean isGitRepo(File root) {
        try (Repository repo = new FileRepositoryBuilder()
                .findGitDir(root)
                .readEnvironment()
                .build()) {
            return repo.getObjectDatabase().exists();
        } catch (Exception e) {
            return false;
        }
    }

    private String sanitizeMessage(String raw) {
        if (raw == null) raw = "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            trimmed = "chore: sync from Project Management";
        }
        if (trimmed.length() > COMMIT_MESSAGE_MAX) {
            trimmed = trimmed.substring(0, COMMIT_MESSAGE_MAX);
        }
        // Strip control characters except \n and \t to prevent terminal escapes.
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (char c : trimmed.toCharArray()) {
            if (c == '\n' || c == '\t' || c >= 0x20) sb.append(c);
        }
        return sb.toString();
    }

    private void runGit(File workDir, List<String> steps, String... args) throws GitCommandException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        String pretty = String.join(" ", cmd);
        steps.add("$ " + pretty);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        // Force English output so messages are stable for users to read.
        pb.environment().put("LC_ALL", "C");
        pb.environment().put("LANG", "C");
        // Disable interactive prompts; we cannot answer them.
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new GitCommandException("Failed to launch git: " + e.getMessage(), "");
        }

        String output = drain(proc.getInputStream());
        boolean finished;
        try {
            finished = proc.waitFor(GIT_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new GitCommandException("Interrupted while running: " + pretty, output);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new GitCommandException("Timeout running: " + pretty, output);
        }
        if (!output.isBlank()) {
            steps.add(output.stripTrailing());
        }
        if (proc.exitValue() != 0) {
            String summary = firstNonBlankLine(output);
            throw new GitCommandException(pretty + " failed (" + proc.exitValue() + "): " + summary, output);
        }
    }

    private static String drain(InputStream in) {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String firstNonBlankLine(String s) {
        if (s == null) return "";
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /**
     * Runs git and returns captured stdout/stderr. Unlike {@link #runGit}, exit
     * code 1 is treated as success because {@code git diff} uses it to signal
     * "differences found". Output is capped at {@link #DIFF_MAX_BYTES}.
     */
    private String runGitCapture(File workDir, String... args) throws GitCommandException {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        for (String a : args) cmd.add(a);
        String pretty = String.join(" ", cmd);

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.environment().put("LC_ALL", "C");
        pb.environment().put("LANG", "C");
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new GitCommandException("Failed to launch git: " + e.getMessage(), "");
        }

        String output = drainLimited(proc.getInputStream(), DIFF_MAX_BYTES);
        boolean finished;
        try {
            finished = proc.waitFor(GIT_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
            throw new GitCommandException("Interrupted while running: " + pretty, output);
        }
        if (!finished) {
            proc.destroyForcibly();
            throw new GitCommandException("Timeout running: " + pretty, output);
        }
        int code = proc.exitValue();
        // git diff / diff --no-index: 0 = no differences, 1 = differences found.
        if (code != 0 && code != 1) {
            throw new GitCommandException(pretty + " failed (" + code + "): " + firstNonBlankLine(output), output);
        }
        return output;
    }

    private static String drainLimited(InputStream in, long maxBytes) {
        try (in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) > 0 && buf.size() < maxBytes) {
                buf.write(chunk, 0, n);
            }
            return buf.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static class GitCommandException extends Exception {
        @SuppressWarnings("unused") final String fullOutput;
        GitCommandException(String message, String fullOutput) {
            super(message);
            this.fullOutput = fullOutput;
        }
    }
}
