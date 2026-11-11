package com.pm.process;

import com.pm.project.Launch;
import com.pm.project.Project;
import com.pm.project.ProjectCommand;
import com.pm.project.ProjectRepository;
import com.pm.project.ProjectStatus;
import com.pm.settings.AppSettings;
import com.pm.settings.AppSettingsRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Central registry of live managed processes; handles start/stop and cross-restart re-attach. */
@Slf4j
@Service
public class ProcessSupervisor {

    /** How a script command ended; lets callers tell a failed build from a stopped or timed-out one. */
    public record CommandExit(int exitCode, boolean cancelled, boolean timedOut) {}

    /** Snapshot of a running command. {@code lastOutputAt} is null for synchronous commands (no live output). */
    public record CommandRunState(Instant startedAt, Instant lastOutputAt) {}

    /** A synchronous (non-script) command in flight, tracked so it can be cancelled from another request. */
    private static final class SyncRun {
        final Process process;
        final Instant startedAt = Instant.now();
        volatile boolean cancelled;

        SyncRun(Process process) {
            this.process = process;
        }
    }

    private static final File NULL_DEVICE = new File("NUL");

    private final RuntimeStateRepository runtimeRepo;
    private final ProjectRepository projectRepo;
    private final AppSettingsRepository settingsRepo;
    private final ConcurrentHashMap<String, ManagedProcess> live = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, SyncRun> syncRuns = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "pm-command-watchdog");
        t.setDaemon(true);
        return t;
    });
    /** Set once the JVM starts cascade-killing children, so their forced exits aren't reported as results. */
    private volatile boolean jvmShuttingDown = false;

    @Value("${pm.logs.dir}")
    private String logsDir;

    @Value("${pm.logs.ring-capacity:2000}")
    private int ringCapacity;

    @Value("${pm.shutdown.kill-children:true}")
    private boolean killChildrenOnShutdown;

    public ProcessSupervisor(RuntimeStateRepository runtimeRepo, ProjectRepository projectRepo,
                              AppSettingsRepository settingsRepo) {
        this.runtimeRepo = runtimeRepo;
        this.projectRepo = projectRepo;
        this.settingsRepo = settingsRepo;
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
        watchdog.shutdownNow();
        live.values().forEach(ManagedProcess::close);
        live.clear();
    }

    /** Runs on JVM shutdown only. Safe to assume Spring beans may already be closed. */
    private void cascadeKillOnJvmExit() {
        jvmShuttingDown = true;
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

    /** Start a launch of a project. Throws if already running. */
    public synchronized ManagedProcess start(Launch launch, Project project) {
        if (statusOf(launch.getId()) == ProjectStatus.RUNNING || statusOf(launch.getId()) == ProjectStatus.ATTACHED) {
            throw new IllegalStateException("Launch already running: " + project.getName() + " / " + launch.getName());
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
                "& cmd.exe /c '" + escapeForPowerShell(launch.getStartCommand()) + "'");
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        applyUtf8AndNoColorEnv(pb);
        applyConfiguredJavaHome(pb);
        applyConfiguredNodeHome(pb);

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start process: " + e.getMessage(), e);
        }

        Path logFile = Paths.get(logsDir,
                launch.getId() + "-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".log");
        ManagedProcess mp = new ManagedProcess(launch.getId(), p, new RingBuffer(ringCapacity), logFile);
        live.put(launch.getId(), mp);

        RuntimeStateEntity state = new RuntimeStateEntity();
        state.setLaunchId(launch.getId());
        state.setPid(p.pid());
        state.setStartedAt(mp.getStartedAt());
        state.setRecordedPorts(launch.getPorts());
        runtimeRepo.save(state);

        log.info("Started {} / {} (pid={}, cmd={})", project.getName(), launch.getName(), p.pid(), launch.getStartCommand());
        return mp;
    }

    /** Stop a launch: optional stop command -> kill process tree -> kill by ports. */
    public synchronized void stop(Launch launch, Project project) {
        ManagedProcess mp = live.get(launch.getId());
        Optional<RuntimeStateEntity> stateOpt = runtimeRepo.findById(launch.getId());

        // 1) Run user-provided stop command if any.
        if (launch.getStopCommand() != null && !launch.getStopCommand().isBlank()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                        "& cmd.exe /c '" + escapeForPowerShell(launch.getStopCommand()) + "'");
                pb.directory(new File(project.getRootDirectory()));
                pb.redirectErrorStream(true);
                applyUtf8AndNoColorEnv(pb);
                applyConfiguredJavaHome(pb);
                applyConfiguredNodeHome(pb);
                Process p = pb.start();
                p.waitFor(20, TimeUnit.SECONDS);
                if (p.isAlive()) p.destroyForcibly();
            } catch (IOException | InterruptedException e) {
                log.warn("stopCommand failed for {} / {}: {}", project.getName(), launch.getName(), e.getMessage());
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            }
        }

        // 2) Destroy process tree via ProcessHandle.
        Long pid = mp != null ? mp.getPid() : stateOpt.map(RuntimeStateEntity::getPid).orElse(null);
        if (pid != null) {
            killTree(pid);
        }

        // 3) Belt-and-braces: kill anything still listening on declared ports.
        List<Integer> ports = launch.getPorts();
        if (ports != null && !ports.isEmpty()) {
            PortUtils.killByPorts(ports);
        }

        // 4) Cleanup.
        if (mp != null) {
            mp.close();
            live.remove(launch.getId());
        }
        stateOpt.ifPresent(runtimeRepo::delete);
        log.info("Stopped {} / {} (pid={})", project.getName(), launch.getName(), pid);
    }

    /**
     * Run one of the project's maintenance commands synchronously. When the command requires it,
     * the caller must ensure no launch of this project is running so build artifacts
     * (target/, node_modules, ...) are not locked. Returns the combined stdout/stderr.
     * Deliberately not {@code synchronized}: a long command must not block start/stop of other
     * launches. It is tracked in {@link #syncRuns} so {@link #stopCommand} can cancel it, and a
     * timeout kills the whole process tree rather than just the PowerShell wrapper.
     */
    public String runCommand(Project project, ProjectCommand command) {
        if (command.getCommand() == null || command.getCommand().isBlank()) {
            throw new IllegalStateException("No command configured for: " + command.getName());
        }
        if (isCommandRunning(command.getId())) {
            throw new IllegalStateException("Command '" + command.getName() + "' is already running.");
        }

        File workDir = new File(project.getRootDirectory());
        if (!workDir.isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist: " + project.getRootDirectory());
        }

        int timeoutSeconds = command.getTimeoutSeconds() != null && command.getTimeoutSeconds() > 0
                ? command.getTimeoutSeconds()
                : 120;

        ProcessBuilder pb = commandProcessBuilder(command, workDir);

        SyncRun run = null;
        try {
            Process p = pb.start();
            run = new SyncRun(p);
            if (syncRuns.putIfAbsent(command.getId(), run) != null) {
                killTree(p.pid());
                throw new IllegalStateException("Command '" + command.getName() + "' is already running.");
            }
            java.util.concurrent.CompletableFuture<String> outputFuture = readAllAsync(p.getInputStream());
            boolean finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                killTree(p.pid());
                throw new CommandTimeoutException(
                        "Command '" + command.getName() + "' timed out after " + timeoutSeconds + "s for: " + project.getName());
            }
            if (run.cancelled) {
                throw new CommandCancelledException(
                        "Command '" + command.getName() + "' was stopped for: " + project.getName());
            }
            String output;
            try {
                output = outputFuture.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException te) {
                output = "";
            }
            int exit = p.exitValue();
            if (exit != 0) {
                throw new CommandFailedException(
                        "Command '" + command.getName() + "' failed (exit " + exit + "):\n" + output, exit);
            }
            log.info("Ran command '{}' on {} (cmd={})", command.getName(), project.getName(), command.getCommand());
            return output;
        } catch (IOException | InterruptedException | java.util.concurrent.ExecutionException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException(
                    "Command '" + command.getName() + "' failed for " + project.getName() + ": " + e.getMessage(), e);
        } finally {
            if (run != null) syncRuns.remove(command.getId(), run);
        }
    }

    /**
     * Run a long-running script command asynchronously: start it in the background as a managed
     * process, stream its output to the logs (keyed by command id), and return immediately. Unlike
     * {@link #runCommand} there is no default timeout — use this for builds that may take minutes.
     * An explicit {@code timeoutSeconds} on the command acts as a maximum run time, after which the
     * process tree is killed; it can also be stopped at any time via {@link #stopCommand}.
     * {@code onExit} (optional) receives how the script ended when it finishes; it is
     * not called when the JVM is shutting down.
     */
    public synchronized void runCommandAsync(Project project, ProjectCommand command, Consumer<CommandExit> onExit) {
        if (command.getCommand() == null || command.getCommand().isBlank()) {
            throw new IllegalStateException("No command configured for: " + command.getName());
        }
        ManagedProcess existing = live.get(command.getId());
        if (existing != null && existing.isAlive()) {
            throw new IllegalStateException("Command '" + command.getName() + "' is already running.");
        }

        File workDir = new File(project.getRootDirectory());
        if (!workDir.isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist: " + project.getRootDirectory());
        }

        ProcessBuilder pb = commandProcessBuilder(command, workDir);

        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start command '" + command.getName() + "': " + e.getMessage(), e);
        }

        Path logFile = Paths.get(logsDir,
                command.getId() + "-" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".log");
        ManagedProcess mp = new ManagedProcess(command.getId(), p, new RingBuffer(ringCapacity), logFile);
        live.put(command.getId(), mp);

        Integer timeout = command.getTimeoutSeconds();
        ScheduledFuture<?> timeoutTask = null;
        if (timeout != null && timeout > 0) {
            timeoutTask = watchdog.schedule(() -> {
                if (mp.isAlive()) {
                    mp.markTimedOut();
                    mp.note("[pm] timed out after " + timeout + "s — killing process tree");
                    killTree(mp.getPid());
                }
            }, timeout, TimeUnit.SECONDS);
        }
        final ScheduledFuture<?> timeoutTaskRef = timeoutTask;

        // Evict from the live registry once the script finishes; the archived log file remains.
        p.onExit().thenRun(() -> {
            if (timeoutTaskRef != null) timeoutTaskRef.cancel(false);
            live.remove(command.getId(), mp);
            if (onExit != null && !jvmShuttingDown) {
                try {
                    onExit.accept(new CommandExit(p.exitValue(), mp.isCancelRequested(), mp.isTimedOut()));
                } catch (RuntimeException e) {
                    log.warn("Exit callback for command '{}' failed: {}", command.getName(), e.toString());
                }
            }
        });

        log.info("Started script command '{}' on {} (pid={}, cmd={})",
                command.getName(), project.getName(), p.pid(), command.getCommand());
    }

    /**
     * Stop a running command (script or synchronous) by killing its whole process tree.
     * Not {@code synchronized} so it can never queue behind another long operation.
     * Returns true when something was actually running and got stopped.
     */
    public boolean stopCommand(String commandId) {
        boolean stopped = false;

        ManagedProcess mp = live.get(commandId);
        if (mp != null && mp.isAlive()) {
            mp.markCancelRequested();
            mp.note("[pm] stop requested — killing process tree (pid=" + mp.getPid() + ")");
            killTree(mp.getPid());
            try {
                mp.getProcess().waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            stopped = true;
        }

        SyncRun run = syncRuns.get(commandId);
        if (run != null && run.process.isAlive()) {
            run.cancelled = true;
            killTree(run.process.pid());
            stopped = true;
        }

        if (stopped) log.info("Stopped command {}", commandId);
        return stopped;
    }

    /** True while a command (script or synchronous) is still running. */
    public boolean isCommandRunning(String commandId) {
        return commandRunState(commandId).isPresent();
    }

    public Optional<CommandRunState> commandRunState(String commandId) {
        ManagedProcess mp = live.get(commandId);
        if (mp != null && mp.isAlive()) {
            return Optional.of(new CommandRunState(mp.getStartedAt(), mp.getLastOutputAt()));
        }
        SyncRun run = syncRuns.get(commandId);
        if (run != null && run.process.isAlive()) {
            return Optional.of(new CommandRunState(run.startedAt, null));
        }
        return Optional.empty();
    }

    /**
     * Builds the PowerShell wrapper for a maintenance command. stdin is bound to NUL so a command
     * that prompts for input (pause, y/n, login) sees EOF instead of waiting forever. This is not
     * applied to launches: some dev servers (e.g. Vite) shut down when stdin closes.
     */
    private ProcessBuilder commandProcessBuilder(ProjectCommand command, File workDir) {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; " +
                "[Console]::InputEncoding = [System.Text.Encoding]::UTF8; " +
                "$OutputEncoding = [System.Text.Encoding]::UTF8; " +
                "& cmd.exe /c '" + escapeForPowerShell(command.getCommand()) + "'; exit $LASTEXITCODE");
        pb.directory(workDir);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.from(NULL_DEVICE));
        applyUtf8AndNoColorEnv(pb);
        applyConfiguredJavaHome(pb);
        applyConfiguredNodeHome(pb);
        return pb;
    }

    /**
     * Kill a process and everything it spawned. Descendants are snapshotted twice (a dying build
     * tool can spawn a last child), then the root is killed; {@code taskkill /T /F} is the fallback
     * if the root survives.
     */
    static void killTree(long pid) {
        Optional<ProcessHandle> root = ProcessHandle.of(pid);
        if (root.isEmpty()) return;
        ProcessHandle h = root.get();
        for (int pass = 0; pass < 2; pass++) {
            h.descendants().forEach(d -> {
                log.debug("destroyForcibly descendant pid={}", d.pid());
                d.destroyForcibly();
            });
        }
        log.debug("destroyForcibly root pid={}", h.pid());
        h.destroyForcibly();
        try {
            h.onExit().get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (!h.isAlive()) return;
            try {
                Process tk = new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!tk.waitFor(5, TimeUnit.SECONDS)) tk.destroyForcibly();
            } catch (IOException | InterruptedException ex) {
                if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("taskkill fallback failed for pid={}: {}", pid, ex.getMessage());
            }
        }
    }

    /** Resolve current status without mutation. */
    public ProjectStatus statusOf(String launchId) {
        ManagedProcess mp = live.get(launchId);
        if (mp != null) {
            if (mp.isAlive()) {
                return ProjectStatus.RUNNING;
            }
            // Process exited abnormally — evict from live registry and purge runtime record.
            mp.close();
            live.remove(launchId);
            runtimeRepo.findById(launchId).ifPresent(runtimeRepo::delete);
            return ProjectStatus.STOPPED;
        }
        Optional<RuntimeStateEntity> stateOpt = runtimeRepo.findById(launchId);
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

    /** Detect the actual TCP ports that the launch's process tree is listening on. */
    public List<Integer> detectListeningPorts(String launchId) {
        Long pid = live.containsKey(launchId)
                ? live.get(launchId).getPid()
                : runtimeRepo.findById(launchId).map(RuntimeStateEntity::getPid).orElse(null);
        if (pid == null) return List.of();

        long now = System.currentTimeMillis();
        long[] ts = portCacheTs.get(launchId);
        if (ts != null && now - ts[0] < PORT_CACHE_TTL_MS) {
            List<Integer> cached = portCache.get(launchId);
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
        // launch) are still shown verbatim because they come from launch.getPorts(),
        // not from this detection path.
        List<Integer> filtered = ports.stream()
                .filter(p -> p > 0 && p < 49152)
                .toList();
        portCache.put(launchId, filtered);
        portCacheTs.put(launchId, new long[]{now});
        return filtered;
    }

    /** Injects JAVA_HOME and prepends its bin/ to PATH when the user has configured one. */
    private void applyConfiguredJavaHome(ProcessBuilder pb) {
        settingsRepo.findById(1)
                .map(AppSettings::getJavaHome)
                .filter(jh -> jh != null && !jh.isBlank())
                .ifPresent(javaHome -> {
                    var env = pb.environment();
                    env.put("JAVA_HOME", javaHome);
                    String current = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
                    env.put("PATH", javaHome + "\\bin;" + current);
                });
    }

    /**
     * Injects NODE_HOME and prepends it to PATH when the user has configured one.
     * On Windows, node.exe lives directly in the install root (no bin/ subdirectory).
     */
    private void applyConfiguredNodeHome(ProcessBuilder pb) {
        settingsRepo.findById(1)
                .map(AppSettings::getNodeHome)
                .filter(nh -> nh != null && !nh.isBlank())
                .ifPresent(nodeHome -> {
                    var env = pb.environment();
                    env.put("NODE_HOME", nodeHome);
                    String current = env.getOrDefault("PATH", env.getOrDefault("Path", ""));
                    env.put("PATH", nodeHome + ";" + current);
                });
    }

    private static void applyUtf8AndNoColorEnv(ProcessBuilder pb) {
        var env = pb.environment();
        // Encourage child processes to emit UTF-8 instead of the system code page.
        env.put("PYTHONIOENCODING", "utf-8");
        env.put("PYTHONUTF8", "1");
        // NOTE: We deliberately do NOT set JAVA_TOOL_OPTIONS here. Any JVM started
        // with it set prints "Picked up JAVA_TOOL_OPTIONS: ..." to stderr, and user
        // launch scripts that pipe with `2>&1` under `$ErrorActionPreference='Stop'`
        // treat that line as a terminating NativeCommandError and abort. UTF-8 log
        // display is already covered by the PowerShell console-encoding wrapper and
        // ManagedProcess.decodeSmart (UTF-8 with GBK fallback).
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

    /**
     * Reads a process's stdout fully on a daemon thread so a hung child cannot block the
     * caller past its waitFor timeout. The caller bounds the wait via waitFor + future timeout.
     */
    private static java.util.concurrent.CompletableFuture<String> readAllAsync(java.io.InputStream in) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException e) {
                return "";
            }
        });
    }
}
