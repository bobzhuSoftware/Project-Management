package com.pm.project;

import com.pm.process.ProcessSupervisor;
import com.pm.git.GitService;
import com.pm.process.RuntimeStateRepository;
import com.pm.proxy.LocalProxyServer;
import com.pm.proxy.Slugs;
import com.pm.proxy.TunnelManager;
import com.pm.project.dto.LaunchRequest;
import com.pm.project.dto.LaunchResponse;
import com.pm.project.dto.ProjectCommandRequest;
import com.pm.project.dto.ProjectCommandResponse;
import com.pm.project.dto.ProjectRequest;
import com.pm.project.dto.ProjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository repo;
    private final LaunchRepository launchRepo;
    private final ProjectCommandRepository commandRepo;
    private final RuntimeStateRepository runtimeRepo;
    private final ProcessSupervisor supervisor;
    private final GitService gitService;
    private final LocalProxyServer proxy;
    private final TunnelManager tunnels;

    // No @Transactional: enriching each launch spawns PowerShell (port detection),
    // which must not run while a DB connection is held (it would exhaust the pool on
    // slow machines). Each repo read below runs in its own short auto-commit tx.
    public List<ProjectResponse> list() {
        return repo.findAllByOrderBySortOrderAsc().stream().map(this::toResponse).toList();
    }

    public ProjectResponse get(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        return toResponse(p);
    }

    @Transactional
    public ProjectResponse create(ProjectRequest req) {
        repo.findByName(req.name).ifPresent(p -> {
            throw new IllegalArgumentException("Project name already exists: " + req.name);
        });
        requireLaunches(req);
        Instant now = Instant.now();
        Project p = Project.newId();
        applyRequest(p, req);
        p.setSortOrder((int) repo.count());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        Project saved = repo.save(p);
        reconcileLaunches(saved, req.launches);
        reconcileCommands(saved.getId(), req.commands);
        gitService.applyPushHook(saved);
        return toResponse(saved);
    }

    @Transactional
    public ProjectResponse update(String id, ProjectRequest req) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        repo.findByName(req.name).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("Project name already exists: " + req.name);
            }
        });
        requireLaunches(req);
        applyRequest(p, req);
        p.setUpdatedAt(Instant.now());
        Project saved = repo.save(p);
        reconcileLaunches(saved, req.launches);
        reconcileCommands(saved.getId(), req.commands);
        gitService.applyPushHook(saved);
        return toResponse(saved);
    }

    @Transactional
    public void delete(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        for (Launch l : launchRepo.findByProjectIdOrderBySortOrderAsc(id)) {
            ProjectStatus status = supervisor.statusOf(l.getId());
            if (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED) {
                throw new IllegalStateException("Stop all launches before deleting the project.");
            }
            runtimeRepo.findById(l.getId()).ifPresent(runtimeRepo::delete);
        }
        commandRepo.deleteByProjectId(id);
        launchRepo.deleteByProjectId(id);
        repo.delete(p);
    }

    @Transactional(readOnly = true)
    public String runCommand(String projectId, String commandId) {
        Project p = repo.findById(projectId)
                .orElseThrow(() -> new NotFoundException("Project not found: " + projectId));
        ProjectCommand cmd = commandRepo.findById(commandId)
                .filter(c -> c.getProjectId().equals(projectId))
                .orElseThrow(() -> new NotFoundException("Command not found: " + commandId));
        if (cmd.isRequireStopped()) {
            for (Launch l : launchRepo.findByProjectIdOrderBySortOrderAsc(projectId)) {
                ProjectStatus status = supervisor.statusOf(l.getId());
                if (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED) {
                    throw new IllegalStateException("Stop all launches before running '" + cmd.getName() + "'.");
                }
            }
        }
        if (cmd.isScript()) {
            supervisor.runCommandAsync(p, cmd);
            return "";
        }
        return supervisor.runCommand(p, cmd);
    }

    @Transactional
    public ProjectResponse setPushEnabled(String id, boolean enabled) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        p.setPushEnabled(enabled);
        p.setUpdatedAt(Instant.now());
        Project saved = repo.save(p);
        gitService.applyPushHook(saved);
        return toResponse(saved);
    }

    @Transactional
    public void reorder(List<String> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Project p = repo.findById(orderedIds.get(i))
                    .orElseThrow(() -> new NotFoundException("Project not found"));
            p.setSortOrder(i);
            repo.save(p);
        }
    }

    @Transactional(readOnly = true)
    public void openFolder(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        String root = p.getRootDirectory();
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("Project has no root directory configured.");
        }
        File dir = new File(root);
        if (!dir.exists() || !dir.isDirectory()) {
            throw new IllegalStateException("Root directory does not exist: " + root);
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        ProcessBuilder pb;
        if (os.contains("win")) {
            pb = new ProcessBuilder("explorer.exe", dir.getAbsolutePath());
        } else if (os.contains("mac") || os.contains("darwin")) {
            pb = new ProcessBuilder("open", dir.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("xdg-open", dir.getAbsolutePath());
        }
        try {
            pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open folder: " + e.getMessage(), e);
        }
    }

    private void requireLaunches(ProjectRequest req) {
        if (req.launches == null || req.launches.isEmpty()) {
            throw new IllegalArgumentException("A project needs at least one launch (startup script).");
        }
    }

    private void applyRequest(Project p, ProjectRequest req) {
        p.setName(req.name.trim());
        p.setRootDirectory(req.rootDirectory.trim());
        p.setDescription(req.description);
        p.setCategory(req.category != null ? req.category : ProjectCategory.APPLICATION);
        p.setPushEnabled(req.pushEnabled == null || req.pushEnabled);
    }

    /** Creates/updates/removes launches so the persisted set matches the request. */
    private void reconcileLaunches(Project project, List<LaunchRequest> requested) {
        String projectId = project.getId();
        Map<String, Launch> existing = launchRepo.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .collect(Collectors.toMap(Launch::getId, Function.identity()));
        // Every alias in use across all launches, so generated names never collide.
        Set<String> takenAliases = launchRepo.findAll().stream()
                .map(Launch::getAlias)
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> keep = new HashSet<>();
        Instant now = Instant.now();

        for (int i = 0; i < requested.size(); i++) {
            LaunchRequest lr = requested.get(i);
            Launch launch;
            if (lr.id != null && !lr.id.isBlank() && existing.containsKey(lr.id)) {
                launch = existing.get(lr.id);
            } else {
                launch = Launch.newId();
                launch.setProjectId(projectId);
                launch.setCreatedAt(now);
            }
            launch.setName(lr.name.trim());
            launch.setStartCommand(lr.startCommand.trim());
            launch.setStopCommand(lr.stopCommand != null && !lr.stopCommand.isBlank() ? lr.stopCommand.trim() : null);
            launch.setPorts(lr.ports != null ? new ArrayList<>(lr.ports) : new ArrayList<>());
            launch.setSortOrder(i);
            launch.setUpdatedAt(now);
            // reach is managed via the dedicated endpoint; honour an explicit request value,
            // otherwise keep the existing one (new launches default to LOCAL).
            if (lr.reach != null) {
                launch.setReach(lr.reach);
            } else if (launch.getReach() == null) {
                launch.setReach(Reach.LOCAL);
            }
            assignAlias(launch, lr, project, requested.size(), takenAliases);
            launchRepo.save(launch);
            keep.add(launch.getId());
        }

        for (Launch old : existing.values()) {
            if (keep.contains(old.getId())) continue;
            ProjectStatus status = supervisor.statusOf(old.getId());
            if (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED) {
                throw new IllegalStateException("Stop launch '" + old.getName() + "' before removing it.");
            }
            runtimeRepo.findById(old.getId()).ifPresent(runtimeRepo::delete);
            launchRepo.delete(old);
        }
    }

    /** Creates/updates/removes commands so the persisted set matches the request. */
    private void reconcileCommands(String projectId, List<ProjectCommandRequest> requested) {
        List<ProjectCommandRequest> list = requested != null ? requested : List.of();
        Map<String, ProjectCommand> existing = commandRepo.findByProjectIdOrderBySortOrderAsc(projectId).stream()
                .collect(Collectors.toMap(ProjectCommand::getId, Function.identity()));
        Set<String> keep = new HashSet<>();

        for (int i = 0; i < list.size(); i++) {
            ProjectCommandRequest cr = list.get(i);
            ProjectCommand cmd;
            if (cr.id != null && !cr.id.isBlank() && existing.containsKey(cr.id)) {
                cmd = existing.get(cr.id);
            } else {
                cmd = ProjectCommand.newId();
                cmd.setProjectId(projectId);
            }
            cmd.setName(cr.name.trim());
            cmd.setCommand(cr.command.trim());
            cmd.setRequireStopped(cr.requireStopped != null && cr.requireStopped);
            cmd.setScript(cr.script != null && cr.script);
            cmd.setTimeoutSeconds(cr.timeoutSeconds != null && cr.timeoutSeconds > 0 ? cr.timeoutSeconds : null);
            cmd.setSortOrder(i);
            commandRepo.save(cmd);
            keep.add(cmd.getId());
        }

        for (ProjectCommand old : existing.values()) {
            if (!keep.contains(old.getId())) {
                commandRepo.delete(old);
            }
        }
    }

    private ProjectResponse toResponse(Project p) {
        List<LaunchResponse> launches = launchRepo.findByProjectIdOrderBySortOrderAsc(p.getId()).stream()
                .map(this::toLaunchResponse)
                .toList();
        List<ProjectCommandResponse> commands = commandRepo.findByProjectIdOrderBySortOrderAsc(p.getId()).stream()
                .map(ProjectCommandResponse::from)
                .toList();
        return ProjectResponse.from(p, launches, commands);
    }

    private LaunchResponse toLaunchResponse(Launch l) {
        ProjectStatus status = supervisor.statusOf(l.getId());
        List<Integer> detected = (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED)
                ? supervisor.detectListeningPorts(l.getId())
                : List.of();
        LaunchResponse r = LaunchResponse.from(
                l,
                status,
                supervisor.getLive(l.getId()).orElse(null),
                supervisor.getRuntimeState(l.getId()).orElse(null),
                detected);
        r.address = buildAddress(l.getAlias());
        r.wifiAddress = buildWifiAddress(l);
        tunnels.current(l.getId()).ifPresent(s -> {
            r.shareUrl = s.url();
            r.shareKey = s.key();
            r.shareExpiresAt = s.expiresAt();
        });
        return r;
    }

    /** The {@code http://<alias>.localhost[:port]} URL when the proxy is up, else null. */
    private String buildAddress(String alias) {
        if (alias == null || alias.isBlank()) return null;
        int port = proxy.getBoundPort();
        if (port <= 0) return null;
        return "http://" + alias + ".localhost" + (port == 80 ? "" : ":" + port);
    }

    /** The {@code http://<alias>.local[:port]} URL for a Wi-Fi-shared launch, else null. */
    private String buildWifiAddress(Launch l) {
        if (l.getReach() == null || l.getReach() == Reach.LOCAL) return null;
        String alias = l.getAlias();
        if (alias == null || alias.isBlank()) return null;
        int port = proxy.getLanPort();
        if (port <= 0) return null;
        return "http://" + alias + ".local" + (port == 80 ? "" : ":" + port);
    }

    /**
     * Resolves the launch's {@code <alias>.localhost} slug: honours an explicit request alias,
     * keeps an existing one, otherwise derives a unique slug from the project (and launch) name.
     */
    private void assignAlias(Launch launch, LaunchRequest lr, Project project,
                             int launchCount, Set<String> taken) {
        String shortId = launch.getId().substring(0, Math.min(8, launch.getId().length()));
        String fallback = "launch-" + shortId;
        String result;
        if (lr.alias != null && !lr.alias.isBlank()) {
            if (launch.getAlias() != null) taken.remove(launch.getAlias().toLowerCase(Locale.ROOT));
            result = Slugs.uniqueSlug(lr.alias, fallback, taken);
        } else if (launch.getAlias() != null && !launch.getAlias().isBlank()) {
            result = launch.getAlias();
        } else {
            String base = launchCount == 1 ? project.getName() : project.getName() + "-" + lr.name;
            result = Slugs.uniqueSlug(base, fallback, taken);
        }
        launch.setAlias(result);
        taken.add(result.toLowerCase(Locale.ROOT));
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }
}
