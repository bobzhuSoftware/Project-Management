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

    private final ProjectRepository projectRepo;

    private final Map<String, CachedStatus> cache = new ConcurrentHashMap<>();

    private record CachedStatus(GitStatusDto status, Instant cachedAt) {}

    public GitStatusDto status(String projectId) {
        return status(projectId, false);
    }

    public GitStatusDto status(String projectId, boolean forceRefresh) {
        if (!forceRefresh) {
            CachedStatus cached = cache.get(projectId);
            if (cached != null && Duration.between(cached.cachedAt, Instant.now()).compareTo(STATUS_TTL) < 0) {
                return cached.status;
            }
        }
        Project p = projectRepo.findById(projectId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + projectId));
        GitStatusDto dto = computeStatus(p);
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
            GitStatusDto preStatus = computeStatus(p);
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

            GitStatusDto midStatus = computeStatus(p);
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

            GitStatusDto postStatus = computeStatus(p);
            cache.put(projectId, new CachedStatus(postStatus, Instant.now()));
            return GitSyncResultDto.ok(steps, postStatus);

        } catch (GitCommandException e) {
            GitStatusDto curr = computeStatus(p);
            cache.put(projectId, new CachedStatus(curr, Instant.now()));
            return GitSyncResultDto.fail(e.getMessage(), steps, curr);
        }
    }

    // --- internals ---

    private GitStatusDto computeStatus(Project p) {
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

            String fullBranch = repo.getFullBranch();
            if (fullBranch != null && fullBranch.startsWith("refs/heads/")) {
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

    private static class GitCommandException extends Exception {
        @SuppressWarnings("unused") final String fullOutput;
        GitCommandException(String message, String fullOutput) {
            super(message);
            this.fullOutput = fullOutput;
        }
    }
}
