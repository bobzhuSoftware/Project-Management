package com.pm.proxy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Decides which of a launch's listening ports serves the browser UI, so {@code <alias>.localhost}
 * points at the frontend rather than an API/DB port — even when every port is picked dynamically.
 *
 * <p>It sends a tiny {@code GET /} to each candidate and scores the reply: HTML and dev-server
 * markers (Vite, React Refresh, Next, Nuxt) win; JSON and non-HTTP replies lose. The winner is
 * cached per launch and only re-probed when the set of listening ports changes (i.e. a restart),
 * so routing stays stable within a session and costs one probe round per port-set.
 */
@Slf4j
@Component
public class PortRoleResolver {

    private static final int CONNECT_TIMEOUT_MS = 300;
    private static final int READ_TIMEOUT_MS = 500;
    private static final int MAX_PROBE_BYTES = 16 * 1024;
    private static final int NOT_HTTP = -1000;

    private record Choice(String signature, int port) {}

    private final Map<String, Choice> cache = new ConcurrentHashMap<>();

    /**
     * Returns the candidate most likely to be the web UI. {@code candidates} must be non-empty;
     * a single candidate is returned without probing.
     */
    public int chooseWebPort(String launchId, List<Integer> candidates) {
        List<Integer> sorted = candidates.stream().sorted().distinct().toList();
        if (sorted.size() == 1) return sorted.get(0);

        String signature = sorted.stream().map(String::valueOf).collect(Collectors.joining(","));
        Choice cached = cache.get(launchId);
        if (cached != null && cached.signature.equals(signature)) return cached.port;

        int best = sorted.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (Integer port : sorted) {
            int score = probe(port);
            log.debug("Port role probe launch={} port={} score={}", launchId, port, score);
            if (score > bestScore) {
                bestScore = score;
                best = port;
            }
        }
        cache.put(launchId, new Choice(signature, best));
        return best;
    }

    /** Scores a single port by fetching {@code /} and inspecting the response. Higher = more web-UI-like. */
    private int probe(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
            s.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = s.getOutputStream();
            out.write(("GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Accept: text/html\r\n"
                    + "User-Agent: pm-role-probe\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            InputStream in = s.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            byte[] tmp = new byte[2048];
            try {
                int n;
                while (buf.size() < MAX_PROBE_BYTES && (n = in.read(tmp)) != -1) {
                    buf.write(tmp, 0, n);
                }
            } catch (SocketTimeoutException ste) {
                // Server sent something then held the socket open — score what we already have.
            }
            if (buf.size() == 0) return NOT_HTTP;
            return score(buf.toString(StandardCharsets.ISO_8859_1.name()));
        } catch (IOException e) {
            return NOT_HTTP;
        }
    }

    private int score(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http/")) return NOT_HTTP; // raw TCP service (DB, etc.)

        int headerEnd = lower.indexOf("\r\n\r\n");
        String head = headerEnd >= 0 ? lower.substring(0, headerEnd) : lower;
        String body = headerEnd >= 0 ? lower.substring(headerEnd + 4) : "";

        int score = 0;
        if (head.contains("content-type:")) {
            if (head.contains("text/html")) score += 100;
            else if (head.contains("application/json")) score -= 60;
            else if (head.contains("text/plain")) score -= 20;
        }
        // Dev-server fingerprints are the strongest "this is the frontend you edit" signal.
        if (body.contains("/@vite/") || body.contains("@react-refresh")
                || body.contains("__vite") || body.contains("/@react-refresh")) {
            score += 80;
        }
        if (body.contains("<!doctype html") || body.contains("<html")) score += 40;
        if (body.contains("window.__nuxt") || body.contains("__next_data__")
                || body.contains("id=\"root\"") || body.contains("id=\"app\"")
                || body.contains("id='root'") || body.contains("id='app'")) {
            score += 20;
        }
        return score;
    }
}
