package com.pm.process;

import com.pm.project.Project;
import com.pm.project.ProjectRepository;
import com.pm.project.ProjectStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Central registry of live managed processes; handles start/stop and cross-restart re-attach. */
@Slf4j
@Service
public class ProcessSupervisor {

    private final RuntimeStateRepository runtimeRepo;
    private final ProjectRepository projectRepo;
    private final ConcurrentHashMap<String, ManagedProcess> live = new ConcurrentHashMap<>();

    @Value("${pm.logs.dir}")
    private String logsDir;

    @Value("${pm.logs.ring-capacity:2000}")
    private int ringCapacity;

    @Value("${pm.shutdown.kill-children:true}")
    private boolean killChildrenOnShutdown;

    public ProcessSupervisor(RuntimeStateRepository runtimeRepo, ProjectRepository projectRepo) {
        this.runtimeRepo = runtimeRepo;
        this.projectRepo = projectRepo;
    }

    @PostConstruct
    void onBoot() {
        log.info("ProcessSupervisor boot: {} runtime records found (killChildrenOnShutdown={})",
                runtimeRepo.count(), killChildrenOnShutdown);
        if (killChildrenOnShutdown) {
            // Register a JVM-level hook so we only cascade-kill on real JVM exit
            // (Ctrl+C, /api/_internal/shutdown, kill <pid>). Spring DevTools restart
            // tears down the application context but keeps the JVM alive, so this
            // hook will NOT fire on DevTools restart and child projects survive.
            Thread hook = new Thread(this::cascadeKillOnJvmExit, "pm-cascade-kill");
            Runtime.getRuntime().addShutdownHook(hook);
        }
    }

    @PreDestroy
    void onShutdown() {
        // Always runs on context close (including DevTools restart). Only release
        // our own resources; do NOT kill child projects here.
        log.info("Context close: detaching {} live process(es) — children keep running", live.size());
        live.values().forEach(ManagedProcess::close);
        live.clear();
    }

    /** Runs on JVM shutdown only. Safe to assume Spring beans may already be closed. */
    private void cascadeKillOnJvmExit() {
        try {
            java.util.Set<Long> pidsToKill = new java.util.HashSet<>();
            for (ManagedProcess mp : live.values()) {
                pidsToKill.add(mp.getPid());
            }
            // runtimeRepo may still be usable; if it fails just stick with the in-memory set.
            try {
                for (RuntimeStateEntity st : runtimeRepo.findAll()) {
                    pidsToKill.add(st.getPid());
                }
            } catch (Exception ignored) {}

            log.info("JVM shutdown: cascade-killing {} child process tree(s)", pidsToKill.size());
            for (Long pid : pidsToKill) {
                ProcessHandle.of(pid).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                });
            }
        } catch (Throwable t) {
            // Shutdown hooks must never throw.
            log.warn("cascadeKillOnJvmExit error: {}", t.getMessage());
        }
    }

    /** Start a project. Throws if already running. */
    public synchronized ManagedProcess start(Project project) {
        if (statusOf(project) == ProjectStatus.RUNNING || statusOf(project) == ProjectStatus.ATTACHED) {
            throw new IllegalStateException("Project already running: " + project.getName());
        }

        File workDir = new File(project.getRootDirectory());
        if (!workDir.isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist: " + project.getRootDirectory());
        }

        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                "[Console]::InputEncoding = [System.Text.Encoding]::UTF8; " +
                "$OutputEncoding = [System.Text.Encoding]::UTF8; " +
                "& cmd.exe /c '" + escapeForPowerShell(project.getStartCommand()) + "'");
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        applyUtf8AndNoColorEnv(pb);

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + e.getMessage(), e);
        }

        Path logFile = Paths.get(logsDir,
                project.getId() + "-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".log");
        ManagedProcess mp = new ManagedProcess(project.getId(), p, new RingBuffer(ringCapacity), logFile);
        live.put(project.getId(), mp);

        RuntimeStateEntity state = new RuntimeStateEntity();
        state.setProjectId(project.getId());
        state.setPid(p.pid());
        state.setStartedAt(mp.getStartedAt());
        state.setRecordedPorts(project.getPorts());
        runtimeRepo.save(state);

        log.info("Started {} (pid={}, cmd={})", project.getName(), p.pid(), project.getStartCommand());
        return mp;
    }

    /** Stop a project: optional stop command -> kill process tree -> kill by ports. */
    public synchronized void stop(Project project) {
        ManagedProcess mp = live.get(project.getId());
        Optional<RuntimeStateEntity> stateOpt = runtimeRepo.findById(project.getId());

        // 1) Run user-provided stop command if any.
        if (project.getStopCommand() != null && !project.getStopCommand().isBlank()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                        "& cmd.exe /c '" + escapeForPowerShell(project.getStopCommand()) + "'");
                pb.directory(new File(project.getRootDirectory()));
                pb.redirectErrorStream(true);
                applyUtf8AndNoColorEnv(pb);
                Process p = pb.start();
                p.waitFor(20, TimeUnit.SECONDS);
                if (p.isAlive()) p.destroyForcibly();
            } catch (IOException | InterruptedException e) {
                log.warn("stopCommand failed for {}: {}", project.getName(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        // 2) Destroy process tree via ProcessHandle.
        Long pid = mp != null ? mp.getPid() : stateOpt.map(RuntimeStateEntity::getPid).orElse(null);
        if (pid != null) {
            ProcessHandle.of(pid).ifPresent(h -> {
                h.descendants().forEach(d -> {
                    log.debug("destroyForcibly descendant pid={}", d.pid());
                    d.destroyForcibly();
                });
                log.debug("destroyForcibly root pid={}", h.pid());
                h.destroyForcibly();
            });
        }

        // 3) Belt-and-braces: kill anything still listening on declared ports.
        List<Integer> ports = project.getPorts();
        if (ports != null && !ports.isEmpty()) {
            PortUtils.killByPorts(ports);
        }

        // 4) Cleanup.
        if (mp != null) {
            mp.close();
            live.remove(project.getId());
        }
        stateOpt.ifPresent(runtimeRepo::delete);
        log.info("Stopped {} (pid={})", project.getName(), pid);
    }

    /** Resolve current status without mutation. */
    public ProjectStatus statusOf(Project project) {
        ManagedProcess mp = live.get(project.getId());
        if (mp != null) {
            if (mp.isAlive()) {
                return ProjectStatus.RUNNING;
            }
            // Process exited abnormally — evict from live registry and purge runtime record.
            mp.close();
            live.remove(project.getId());
            runtimeRepo.findById(project.getId()).ifPresent(runtimeRepo::delete);
            return ProjectStatus.STOPPED;
        }
        Optional<RuntimeStateEntity> stateOpt = runtimeRepo.findById(project.getId());
        if (stateOpt.isPresent()) {
            Optional<ProcessHandle> handle = ProcessHandle.of(stateOpt.get().getPid());
            if (handle.isPresent() && handle.get().isAlive()) {
                return ProjectStatus.ATTACHED;
            }
            // PID dead — clean up stale record.
            runtimeRepo.delete(stateOpt.get());
        }
        return ProjectStatus.STOPPED;
    }

    public Optional<ManagedProcess> getLive(String projectId) {
        return Optional.ofNullable(live.get(projectId));
    }

    public Optional<RuntimeStateEntity> getRuntimeState(String projectId) {
        return runtimeRepo.findById(projectId);
    }

    /** TTL cache: projectId -> (timestamp, ports). Avoids running PowerShell on every poll. */
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> portCacheTs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, List<Integer>> portCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PORT_CACHE_TTL_MS = 5_000;

    /** Detect the actual TCP ports that the project's process tree is listening on. */
    public List<Integer> detectListeningPorts(Project project) {
        Long pid = live.containsKey(project.getId())
                ? live.get(project.getId()).getPid()
                : runtimeRepo.findById(project.getId()).map(RuntimeStateEntity::getPid).orElse(null);
        if (pid == null) return List.of();

        long now = System.currentTimeMillis();
        long[] ts = portCacheTs.get(project.getId());
        if (ts != null && now - ts[0] < PORT_CACHE_TTL_MS) {
            List<Integer> cached = portCache.get(project.getId());
            if (cached != null) return cached;
        }

        Optional<ProcessHandle> root = ProcessHandle.of(pid);
        if (root.isEmpty()) return List.of();
        java.util.Set<Long> pids = new java.util.HashSet<>();
        pids.add(pid);
        root.get().descendants().forEach(d -> pids.add(d.pid()));

        List<Integer> ports = PortUtils.listeningPortsOfPids(pids);
        // Filter out Windows dynamic/ephemeral range (49152-65535).
        // Anything in there is almost always an internal socket (H2 AUTO_SERVER,
        // language runtime IPC, debug agent, etc.) — not a service the user
        // would point a browser at. Registered ports (those configured on the
        // project) are still shown verbatim because they come from p.getPorts(),
        // not from this detection path.
        List<Integer> filtered = ports.stream()
                .filter(p -> p > 0 && p < 49152)
                .toList();
        portCache.put(project.getId(), filtered);
        portCacheTs.put(project.getId(), new long[]{now});
        return filtered;
    }

    private static void applyUtf8AndNoColorEnv(ProcessBuilder pb) {
        var env = pb.environment();
        // Encourage child processes to emit UTF-8 instead of the system code page.
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        // Any nested JVM (mvn, gradle, java) will pick this up automatically.
        String jto = env.getOrDefault("JAVA_TOOL_OPTIONS", "");
        if (!jto.contains("file.encoding")) {
            env.put("JAVA_TOOL_OPTIONS",
                    (jto.isBlank() ? "" : jto + " ") + "-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8");
        }
        // Disable ANSI colors at the source so the log pane stays clean.
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        env.put("TERM", "dumb");
        env.put("CLICOLOR", "0");
        env.put("CLICOLOR_FORCE", "0");
        // Node.js / npm: disable color.
        env.put("NODE_NO_WARNINGS", "1");
    }

    /** Escape single quotes for embedding a command inside a PowerShell single-quoted string. */
    private static String escapeForPowerShell(String cmd) {
        // In PowerShell single-quoted strings, the only escape is '' for a literal '.
        return cmd.replace("'", "''");
    }
}
