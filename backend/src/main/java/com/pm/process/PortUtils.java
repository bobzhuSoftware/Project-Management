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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
        String pidList = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String script =
                "$pids=@(" + pidList + ");" +
                "Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue | " +
                "Where-Object { $pids -contains $_.OwningProcess } | " +
                "Select-Object -ExpandProperty LocalPort -Unique";
        List<Integer> result = new ArrayList<>();
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
                    try { result.add(Integer.parseInt(line)); } catch (NumberFormatException ignored) {}
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return Collections.emptyList();
        }
        Collections.sort(result);
        return result;
    }
}
