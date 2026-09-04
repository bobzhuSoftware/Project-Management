package com.pm.proxy;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Discovers inbound TCP ports that Windows Firewall already allows for <em>any</em> program
 * (typically opened by installers that ran as admin, e.g. printer software). Binding the LAN
 * proxy to one of these lets phones connect <b>without needing admin rights</b> to add a rule.
 *
 * <p>Reading firewall rules does not require elevation. The result is cached for the app
 * lifetime and warmed up in the background at startup, since the query is slow.
 */
@Slf4j
@Component
public class FirewallAllowedPorts {

    private volatile List<Integer> cached;

    @PostConstruct
    void warmUp() {
        Thread t = new Thread(this::get, "pm-firewall-ports");
        t.setDaemon(true);
        t.start();
    }

    /** Program-agnostic inbound-allow TCP ports, ascending. Empty on non-Windows or on failure. */
    public List<Integer> get() {
        List<Integer> c = cached;
        if (c != null) return c;
        synchronized (this) {
            if (cached == null) cached = query();
            return cached;
        }
    }

    private List<Integer> query() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return List.of();
        // Read firewall rules straight from the registry via `reg query` — a single fast native call
        // (~0.25s). The per-rule Get-NetFirewall*Filter cmdlets are correct but take ~65s on machines
        // with many rules, which always blew the timeout and left Wi-Fi sharing stuck on a port the
        // firewall never allowed. Each REG_SZ value is a rule string like
        // "v2.33|Action=Allow|Active=TRUE|Dir=In|Protocol=6|LPort=21336|Name=...|"; a program-agnostic
        // rule (usable without admin) has no "App=" segment.
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    "HKLM\\SYSTEM\\CurrentControlSet\\Services\\SharedAccess\\Parameters\\FirewallPolicy\\FirewallRules");
            pb.redirectErrorStream(true);
            p = pb.start();
            List<String> lines = readLinesAsync(p);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.debug("Firewall registry query timed out");
                return List.of();
            }
            Set<Integer> ports = new TreeSet<>();
            for (String line : lines) parseRule(line, ports);
            List<Integer> result = List.copyOf(ports);
            if (!result.isEmpty()) log.info("Firewall already allows inbound TCP ports {} (usable for Wi-Fi share without admin)", result);
            return result;
        } catch (IOException | InterruptedException e) {
            if (p != null) p.destroyForcibly();
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return List.of();
        }
    }

    /** Extracts TCP LocalPorts from a `reg query` line that is a program-agnostic inbound-allow rule. */
    private void parseRule(String line, Set<Integer> out) {
        int idx = line.indexOf("REG_SZ");
        if (idx < 0) return;
        boolean allow = false, active = false, inbound = false, tcp = false, anyProgram = true;
        String lports = null;
        for (String seg : line.substring(idx + 6).trim().split("\\|")) {
            if (seg.equals("Action=Allow")) allow = true;
            else if (seg.equals("Active=TRUE")) active = true;
            else if (seg.equals("Dir=In")) inbound = true;
            else if (seg.equals("Protocol=6")) tcp = true;      // 6 = TCP
            else if (seg.startsWith("App=")) anyProgram = false;
            else if (seg.startsWith("LPort=")) lports = seg.substring(6);
        }
        if (allow && active && inbound && tcp && anyProgram && lports != null) parsePorts(lports, out);
    }

    /** Accepts a single port ("21339"), a comma list, or a small range ("5000-5010"). */
    private void parsePorts(String token, Collection<Integer> out) {
        if (token.isEmpty()) return;
        for (String part : token.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            try {
                if (dash > 0) {
                    int lo = Integer.parseInt(part.substring(0, dash).trim());
                    int hi = Integer.parseInt(part.substring(dash + 1).trim());
                    if (hi >= lo && hi - lo <= 64) for (int i = lo; i <= hi; i++) out.add(i);
                } else {
                    out.add(Integer.parseInt(part));
                }
            } catch (NumberFormatException ignored) {
                // Non-numeric LocalPort keyword (e.g. RPC) — skip.
            }
        }
    }

    private static List<String> readLinesAsync(Process p) {
        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), Charset.forName("GBK")))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            } catch (IOException ignored) {}
        }, "pm-firewall-read");
        reader.setDaemon(true);
        reader.start();
        return lines;
    }
}
