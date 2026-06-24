package com.pm.process;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class PortUtils {

    private PortUtils() {}

    /** Returns true if a TCP server is currently accepting connections on 127.0.0.1:port. */
    public static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean anyListening(List<Integer> ports) {
        if (ports == null) return false;
        for (Integer p : ports) {
            if (p != null && isListening(p)) return true;
        }
        return false;
    }

    /** Kill any process listening on each of the given ports via PowerShell. Best-effort. */
    public static void killByPorts(List<Integer> ports) {
        if (ports == null || ports.isEmpty()) return;
        String portList = String.join(",", ports.stream().map(String::valueOf).toList());
        String script =
                "$ports=@(" + portList + ");" +
                "foreach($p in $ports){" +
                "  $c=Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue;" +
                "  if($c){ $c | Select-Object -ExpandProperty OwningProcess -Unique | ForEach-Object {" +
                "    try{ Stop-Process -Id $_ -Force -ErrorAction Stop; Write-Host \"killed $_ on $p\" }" +
                "    catch{ Write-Host \"failed $_ on ${p}: $($_.Exception.Message)\" } } }" +
                "}";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            drainSilently(p);
            p.waitFor(15, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            log.warn("killByPorts failed: {}", e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** Returns PIDs listening on a single port (best-effort). */
    public static List<Long> listeningPids(int port) {
        List<Long> result = new ArrayList<>();
        String script = "Get-NetTCPConnection -LocalPort " + port +
                " -State Listen -ErrorAction SilentlyContinue | " +
                "Select-Object -ExpandProperty OwningProcess -Unique";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        try { result.add(Long.parseLong(line)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        return result;
    }

    private static void drainSilently(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                while (r.readLine() != null) { /* drain */ }
            } catch (IOException ignored) {}
        }, "port-kill-drain");
        t.setDaemon(true);
        t.start();
    }

    /** Returns the set of listening TCP ports whose OwningProcess is in the given PID set. */
    public static List<Integer> listeningPortsOfPids(Collection<Long> pids) {
        if (pids == null || pids.isEmpty()) return Collections.emptyList();
        Map<Long, List<Integer>> snapshot = snapshotListeningByPid();
        return pids.stream()
                .flatMap(pid -> snapshot.getOrDefault(pid, List.of()).stream())
                .distinct()
                .sorted()
                .toList();
    }

    /** Cached snapshot: pid -> listening ports. Avoids spawning PowerShell per-project per-poll. */
    private static volatile Map<Long, List<Integer>> snapshotCache = Collections.emptyMap();
    private static volatile long snapshotCacheTs = 0L;
    private static final long SNAPSHOT_TTL_MS = 3_000;
    private static final Object snapshotLock = new Object();

    /**
     * Returns a snapshot of all currently-listening TCP ports grouped by OwningProcess.
     * One PowerShell invocation per {@value #SNAPSHOT_TTL_MS}ms; subsequent calls within
     * the TTL return the cached map.
     */
    public static Map<Long, List<Integer>> snapshotListeningByPid() {
        long now = System.currentTimeMillis();
        if (now - snapshotCacheTs < SNAPSHOT_TTL_MS) {
            return snapshotCache;
        }
        synchronized (snapshotLock) {
            if (now - snapshotCacheTs < SNAPSHOT_TTL_MS) {
                return snapshotCache;
            }
            Map<Long, List<Integer>> fresh = querySnapshot();
            snapshotCache = fresh;
            snapshotCacheTs = System.currentTimeMillis();
            return fresh;
        }
    }

    private static Map<Long, List<Integer>> querySnapshot() {
        // Use single quotes + -f operator to avoid double-quote escaping issues
        // when Java's ProcessBuilder passes the script through Windows arg quoting.
        String script =
                "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | " +
                "ForEach-Object { '{0} {1}' -f $_.OwningProcess, $_.LocalPort }";
        Map<Long, List<Integer>> result = new HashMap<>();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = r.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    try {
                        long pid = Long.parseLong(line.substring(0, sp));
                        int port = Integer.parseInt(line.substring(sp + 1).trim());
                        result.computeIfAbsent(pid, k -> new ArrayList<>()).add(port);
                    } catch (NumberFormatException ignored) {}
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("snapshotListeningByPid failed: {}", e.getMessage());
            return Collections.emptyMap();
        }
        return result;
    }
}
