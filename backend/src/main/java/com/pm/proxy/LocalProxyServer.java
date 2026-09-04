package com.pm.proxy;

import com.pm.project.Reach;
import com.pm.proxy.RouteRegistry.Route;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rung 1 reverse proxy. Listens on {@code pm.proxy.port} (80, falling back to 7777) and
 * routes {@code <alias>.localhost} to whatever port that launch is listening on right now.
 * It reads only the request head to find the Host, then forwards raw bytes both ways — so
 * WebSockets, HMR, SSE and streaming pass through untouched. A bare {@code localhost} shows
 * an index of every named project.
 */
@Slf4j
@Component
public class LocalProxyServer {

    private static final int MAX_HEADER_BYTES = 64 * 1024;
    private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 3000;
    // How long an idle keep-alive connection waits for the next request before we close it.
    private static final int KEEPALIVE_IDLE_MS = 30_000;
    private static final byte[] HEADER_END = {'\r', '\n', '\r', '\n'};

    private final RouteRegistry routes;
    private final FirewallAllowedPorts firewallPorts;
    private final TunnelRegistry tunnels;

    @Value("${pm.proxy.enabled:true}")
    private boolean enabled;

    @Value("${pm.proxy.port:80}")
    private int preferredPort;

    @Value("${pm.proxy.fallback-port:7777}")
    private int fallbackPort;

    @Value("${pm.proxy.lan-port:7777}")
    private int lanPreferredPort;

    private static final int LAN_PORT_SCAN_TRIES = 20;

    // Primary listener: always localhost, serves <alias>.localhost + the index. Stable port.
    private volatile int boundPort = 0;
    private volatile boolean running = false;
    private volatile ServerSocket serverSocket;
    // Secondary listener: 0.0.0.0, only while a launch is shared over Wi-Fi. Serves <alias>.local.
    private volatile int lanPort = 0;
    private volatile ServerSocket lanSocket;
    private volatile Thread lanAcceptThread;
    private final Object bindLock = new Object();
    private ExecutorService pool;
    private Thread acceptThread;

    public LocalProxyServer(RouteRegistry routes, FirewallAllowedPorts firewallPorts, TunnelRegistry tunnels) {
        this.routes = routes;
        this.firewallPorts = firewallPorts;
        this.tunnels = tunnels;
    }

    /** The localhost port the proxy bound to (for {@code <alias>.localhost}), or 0 when not running. */
    public int getBoundPort() {
        return boundPort;
    }

    /** The LAN (0.0.0.0) port for {@code <alias>.local}, or 0 when nothing is shared over Wi-Fi. */
    public int getLanPort() {
        return lanPort;
    }

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("Local named-address proxy disabled (pm.proxy.enabled=false)");
            return;
        }
        // Primary listener stays on localhost so <alias>.localhost keeps a stable port across shares.
        ServerSocket ss = tryBind("127.0.0.1", preferredPort);
        if (ss == null && fallbackPort != preferredPort) {
            ss = tryBind("127.0.0.1", fallbackPort);
        }
        if (ss == null) {
            log.warn("Local named-address proxy could not bind {} or {}; <alias>.localhost disabled",
                    preferredPort, fallbackPort);
            return;
        }
        this.serverSocket = ss;
        this.boundPort = ss.getLocalPort();
        this.running = true;
        AtomicInteger seq = new AtomicInteger();
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "pm-proxy-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.acceptThread = new Thread(() -> acceptLoop(false), "pm-proxy-accept");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
        String shown = boundPort == 80 ? "" : ":" + boundPort;
        log.info("Local named-address proxy listening on 127.0.0.1:{} — open http://localhost{} for the index",
                boundPort, shown);
        // Open the LAN listener too if a launch is already shared over Wi-Fi.
        refreshLanBinding();
    }

    @PreDestroy
    void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        try { if (lanSocket != null) lanSocket.close(); } catch (IOException ignored) {}
        if (pool != null) pool.shutdownNow();
    }

    private ServerSocket tryBind(String host, int port) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(host, port));
            return ss;
        } catch (IOException e) {
            log.debug("Proxy bind {}:{} failed: {}", host, port, e.getMessage());
            return null;
        }
    }

    /**
     * Opens the 0.0.0.0 LAN listener when any launch is shared over Wi-Fi, or closes it when none
     * are. The localhost listener is untouched, so {@code <alias>.localhost} keeps working. Called
     * at boot and after a launch's reach changes.
     */
    public void refreshLanBinding() {
        if (!running) return;
        synchronized (bindLock) {
            boolean want = routes.anySharedLan();
            if (want && lanSocket == null) openLan();
            else if (!want && lanSocket != null) closeLan();
        }
    }

    /**
     * Binds 0.0.0.0 for Wi-Fi sharing. Prefers a port that Windows Firewall already allows for any
     * program (so no admin is needed to add a rule); otherwise falls back to the preferred LAN port
     * (7777), scanning upward for a free one.
     */
    private void openLan() {
        java.util.Set<Integer> allowed = new java.util.LinkedHashSet<>(firewallPorts.get());
        java.util.LinkedHashSet<Integer> candidates = new java.util.LinkedHashSet<>(allowed);
        for (int i = 0; i < LAN_PORT_SCAN_TRIES; i++) candidates.add(lanPreferredPort + i);

        ServerSocket ss = null;
        int port = 0;
        boolean viaFirewall = false;
        for (int candidate : candidates) {
            if (candidate == boundPort) continue; // don't clash with the localhost listener
            ss = tryBind("0.0.0.0", candidate);
            if (ss != null) { port = candidate; viaFirewall = allowed.contains(candidate); break; }
        }
        if (ss == null) {
            log.warn("Could not bind a LAN proxy port near {}; Wi-Fi sharing unavailable", lanPreferredPort);
            return;
        }
        this.lanSocket = ss;
        this.lanPort = port;
        Thread t = new Thread(() -> acceptLoop(true), "pm-proxy-lan-accept");
        t.setDaemon(true);
        this.lanAcceptThread = t;
        t.start();
        if (viaFirewall) {
            log.info("Proxy LAN sharing on 0.0.0.0:{} (firewall-allowed port — no admin needed) — phones can reach http://<alias>.local:{}", port, port);
        } else {
            log.info("Proxy LAN sharing on 0.0.0.0:{} — phones can reach http://<alias>.local:{} (Windows Firewall may need to allow inbound on this port)", port, port);
        }
    }

    private void closeLan() {
        ServerSocket ss = this.lanSocket;
        this.lanSocket = null;
        this.lanPort = 0;
        this.lanAcceptThread = null;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        log.info("Proxy LAN sharing stopped");
    }

    private void acceptLoop(boolean lan) {
        while (running) {
            ServerSocket ss = lan ? lanSocket : serverSocket;
            if (ss == null) {
                if (lan) return; // LAN listener was closed — end this thread
                try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                continue;
            }
            try {
                Socket client = ss.accept();
                pool.submit(() -> handle(client));
            } catch (IOException e) {
                if (lan && lanSocket != ss) return; // our socket was swapped/closed — exit
                if (running && !ss.isClosed()) log.debug("Proxy accept error: {}", e.getMessage());
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            // LAN clients only ever see launches explicitly shared over Wi-Fi; localhost sees all.
            boolean fromLan = !client.getInetAddress().isLoopbackAddress();
            InputStream in = client.getInputStream();
            byte[] head = readHead(in);
            if (head == null) { client.close(); return; }

            String host = parseHost(head);

            // Rung 3: public tunnel traffic arrives on the loopback listener carrying the
            // trycloudflare Host. Gate it by the share key before it can reach any launch.
            Optional<TunnelRegistry.Entry> tunnel = tunnels.lookup(host);
            if (tunnel.isPresent()) { handleTunnel(client, in, head, tunnel.get()); return; }
            // A tunnel-style host that isn't registered (stale/expired) must never see the index.
            if (host != null && host.endsWith(".trycloudflare.com")) { serveOpaque(client); return; }

            String alias = aliasFromHost(host);

            if (alias == null) {
                // Never expose the project index over the LAN — it would name everything.
                if (fromLan) { serveOpaque(client); return; }
                serveIndex(client);
                return;
            }
            if (fromLan) {
                Reach reach = routes.reachOf(alias).orElse(null);
                // Admission control: unknown and not-shared look identical, revealing nothing.
                if (reach == null || reach == Reach.LOCAL) { serveOpaque(client); return; }
            }
            Optional<Integer> port = routes.resolvePort(alias);
            if (port.isEmpty()) {
                if (fromLan) { serveOpaque(client); return; }
                serveUnavailable(client, alias);
                return;
            }
            proxyKeepAlive(client, in, head, requestHead -> {
                Optional<Integer> p = routes.resolvePort(alias);
                if (p.isPresent()) return p.get();
                if (fromLan) serveOpaque(client); else serveUnavailable(client, alias);
                return -1;
            });
        } catch (IOException e) {
            log.debug("Proxy handle error: {}", e.getMessage());
            closeQuietly(client);
        }
    }

    /**
     * Serves a public tunnel request. The link is gated by a secret key: on the first browser hit
     * the {@code ?key=} is swapped for an {@code HttpOnly} cookie (so assets and reloads keep
     * working without the key in every URL); webhooks/other methods pass the key each time. Without
     * a valid key the response is an opaque 404 that reveals nothing.
     */
    private void handleTunnel(Socket client, InputStream in, byte[] head, TunnelRegistry.Entry entry) {
        String[] rl = parseRequestLine(head);
        String method = rl[0];
        String target = rl[1];
        String cookieKey = parseCookie(head, "pm_share");
        String queryKey = queryParam(target, "key");
        boolean cookieValid = entry.key().equals(cookieKey);
        boolean queryValid = entry.key().equals(queryKey);

        if (!cookieValid && !queryValid) { serveOpaque(client); return; }

        Optional<Integer> port = routes.resolvePort(entry.alias());
        if (port.isEmpty()) { serveShareIdle(client); return; }

        // Browser first hit with the key: set the cookie and redirect to the clean URL.
        if (queryValid && !cookieValid && "GET".equalsIgnoreCase(method)) {
            redirectWithCookie(client, stripQueryParam(target, "key"), entry.key());
            return;
        }
        proxyKeepAlive(client, in, head, requestHead -> {
            // Re-gate every request on this reused connection; the cookie rides along automatically.
            String reqTarget = parseRequestLine(requestHead)[1];
            String ck = parseCookie(requestHead, "pm_share");
            String qk = queryParam(reqTarget, "key");
            if (!entry.key().equals(ck) && !entry.key().equals(qk)) { serveOpaque(client); return -1; }
            Optional<Integer> p = routes.resolvePort(entry.alias());
            if (p.isEmpty()) { serveShareIdle(client); return -1; }
            return p.get();
        });
    }

    /** {@code [method, target]} from the request line, defaulting to {@code ["GET", "/"]}. */
    private String[] parseRequestLine(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        int eol = text.indexOf("\r\n");
        String line = eol >= 0 ? text.substring(0, eol) : text;
        String[] parts = line.split(" ");
        if (parts.length < 2) return new String[]{"GET", "/"};
        return new String[]{parts[0], parts[1]};
    }

    private String queryParam(String target, String name) {
        int q = target.indexOf('?');
        if (q < 0) return null;
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(name)) return eq >= 0 ? pair.substring(eq + 1) : "";
        }
        return null;
    }

    private String stripQueryParam(String target, String name) {
        int q = target.indexOf('?');
        if (q < 0) return target;
        String path = target.substring(0, q);
        StringBuilder kept = new StringBuilder();
        for (String pair : target.substring(q + 1).split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            if (k.equals(name)) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(pair);
        }
        return kept.length() > 0 ? path + "?" + kept : path;
    }

    private String parseCookie(byte[] head, String name) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("cookie")) {
                for (String pair : line.substring(colon + 1).split(";")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0 && pair.substring(0, eq).trim().equals(name)) {
                        return pair.substring(eq + 1).trim();
                    }
                }
            }
        }
        return null;
    }

    private void redirectWithCookie(Socket client, String location, String key) {
        try {
            String header = "HTTP/1.1 302 Found\r\n"
                    + "Location: " + location + "\r\n"
                    + "Set-Cookie: pm_share=" + key + "; Path=/; HttpOnly; SameSite=Lax; Secure\r\n"
                    + "Content-Length: 0\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(header.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    /** Shown when the key is valid but the shared launch isn't listening right now. */
    private void serveShareIdle(Socket client) {
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>Not running</title><style>"
                + "body{font:14px/1.6 -apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:4rem auto;max-width:480px;color:#1b1f24;padding:0 1rem}"
                + "</style></head><body><h1>Nothing is running here yet</h1>"
                + "<p>This shared app isn't listening right now. Try again once it's started.</p></body></html>";
        sendHtml(client, 503, "Service Unavailable", html);
    }

    /** Reads bytes up to and including the blank line that ends the request head. */
    private byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(1024);
        int matched = 0;
        int b;
        while ((b = in.read()) != -1) {
            buf.write(b);
            matched = (b == HEADER_END[matched]) ? matched + 1 : (b == '\r' ? 1 : 0);
            if (matched == HEADER_END.length) return buf.toByteArray();
            if (buf.size() > MAX_HEADER_BYTES) return null;
        }
        return buf.size() > 0 ? buf.toByteArray() : null;
    }

    private String parseHost(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("host")) {
                String host = line.substring(colon + 1).trim();
                int portSep = host.lastIndexOf(':');
                if (portSep > 0 && host.indexOf(']') < 0) host = host.substring(0, portSep);
                return host.toLowerCase();
            }
        }
        return "";
    }

    /**
     * Rewrites the request head's Host and (when present) Origin to the upstream's own origin so
     * dev-server host checks (Vite {@code server.allowedHosts}, webpack, …) and the near-universal
     * {@code http://localhost:*} / {@code http://127.0.0.1:*} CORS allowlists accept requests that
     * arrived via {@code <alias>.localhost}, {@code <alias>.local}, or a public tunnel — whose real
     * Origin ({@code https://….trycloudflare.com} etc.) those apps would otherwise reject.
     *
     * <p>Also forces {@code Connection: close} on the request sent to the <em>upstream</em> so each
     * upstream exchange is self-contained (the upstream closes after its response, cleanly delimiting
     * it). This is the loopback leg only and does not affect the client: {@link #proxyKeepAlive}
     * keeps the client connection alive and re-parses/rewrites every request head, so the CORS and
     * host-check fix holds without the client paying a reconnect per request. WebSocket upgrades are
     * exempt so HMR/live-reload keep their long-lived connection.
     */
    private byte[] rewriteHeadForUpstream(byte[] head, int port) {
        String origin = "http://127.0.0.1:" + port;
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n", -1);

        boolean upgrade = false;
        for (String line : lines) {
            int c = line.indexOf(':');
            if (c <= 0) continue;
            String name = line.substring(0, c).trim();
            String val = line.substring(c + 1).trim();
            if (name.equalsIgnoreCase("upgrade") && !val.isEmpty()) upgrade = true;
            if (name.equalsIgnoreCase("connection") && val.toLowerCase().contains("upgrade")) upgrade = true;
        }

        java.util.List<String> out = new java.util.ArrayList<>(lines.length + 1);
        boolean inHeaders = true;
        boolean injected = false;
        for (String line : lines) {
            if (inHeaders && line.isEmpty()) {
                if (!upgrade && !injected) { out.add("Connection: close"); injected = true; }
                inHeaders = false;
                out.add(line);
                continue;
            }
            if (inHeaders) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    if (name.equalsIgnoreCase("host")) { out.add("Host: 127.0.0.1:" + port); continue; }
                    if (name.equalsIgnoreCase("origin")) { out.add("Origin: " + origin); continue; }
                    if (!upgrade && (name.equalsIgnoreCase("connection")
                            || name.equalsIgnoreCase("keep-alive")
                            || name.equalsIgnoreCase("proxy-connection"))) { continue; }
                }
            }
            out.add(line);
        }
        return String.join("\r\n", out).getBytes(StandardCharsets.ISO_8859_1);
    }

    /** Returns the alias for an {@code <alias>.localhost} / {@code <alias>.local} host, or null. */
    private String aliasFromHost(String host) {
        if (host == null) return null;
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.equals("localhost") || host.equals("127.0.0.1")) return null;
        if (host.endsWith(".localhost")) {
            String alias = host.substring(0, host.length() - ".localhost".length());
            return alias.isBlank() ? null : alias;
        }
        if (host.endsWith(".local")) {
            String alias = host.substring(0, host.length() - ".local".length());
            return alias.isBlank() ? null : alias;
        }
        return null;
    }

    /** Resolves the live upstream port for a request head, or writes a terminal response and returns -1. */
    @FunctionalInterface
    private interface UpstreamResolver {
        int resolve(byte[] head) throws IOException;
    }

    private enum Exchange { KEEP_ALIVE, CLOSE }

    /**
     * Proxies one client connection, keeping it alive across requests so the client (a browser, a
     * phone hitting {@code <alias>.local}, or cloudflared) reuses a single TCP connection for many
     * assets instead of reconnecting per request. Every request head is re-parsed and its
     * Host/Origin rewritten for the upstream, so the CORS/host-check fix that previously required
     * {@code Connection: close} on the client leg still holds — without paying a reconnect each time.
     */
    private void proxyKeepAlive(Socket client, InputStream clientIn, byte[] firstHead, UpstreamResolver resolver) {
        OutputStream clientOut;
        try { clientOut = client.getOutputStream(); }
        catch (IOException e) { closeQuietly(client); return; }
        byte[] head = firstHead;
        try {
            while (head != null) {
                int port = resolver.resolve(head);
                if (port <= 0) return; // resolver already wrote a terminal response and closed the socket
                if (proxyExchange(client, clientIn, clientOut, head, port) != Exchange.KEEP_ALIVE) return;
                try { client.setSoTimeout(KEEPALIVE_IDLE_MS); } catch (IOException ignored) {}
                head = readHead(clientIn);
                try { client.setSoTimeout(0); } catch (IOException ignored) {}
            }
        } catch (IOException e) {
            log.debug("Proxy keep-alive ended: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
    }

    /** Performs one request/response against the upstream. Returns whether the client may be reused. */
    private Exchange proxyExchange(Socket client, InputStream clientIn, OutputStream clientOut, byte[] head, int port) {
        boolean webSocket = isWebSocketUpgrade(head);
        boolean headMethod = "HEAD".equalsIgnoreCase(parseRequestLine(head)[0]);
        boolean clientWantsClose = headerHasToken(head, "connection", "close");
        Socket upstream = new Socket();
        try {
            upstream.connect(new InetSocketAddress("127.0.0.1", port), UPSTREAM_CONNECT_TIMEOUT_MS);
            upstream.setTcpNoDelay(true);
            OutputStream upOut = upstream.getOutputStream();
            InputStream upIn = upstream.getInputStream();
            // Present the upstream's own origin as Host + Origin so dev-server host checks and
            // localhost CORS allowlists accept traffic that came via <alias>.local / a public tunnel.
            upOut.write(rewriteHeadForUpstream(head, port));
            forwardRequestBody(clientIn, upOut, head);
            upOut.flush();

            if (webSocket) {
                rawBidirectionalPump(client, clientIn, upstream);
                return Exchange.CLOSE;
            }

            byte[] respHead = readHead(upIn);
            if (respHead == null) return Exchange.CLOSE;
            boolean keepAlive = !clientWantsClose;
            writeResponse(clientOut, respHead, upIn, headMethod, keepAlive);
            return keepAlive ? Exchange.KEEP_ALIVE : Exchange.CLOSE;
        } catch (IOException e) {
            log.debug("Proxy upstream 127.0.0.1:{} error: {}", port, e.getMessage());
            return Exchange.CLOSE;
        } finally {
            closeQuietly(upstream);
        }
    }

    /**
     * Streams the upstream response to the client, keeping the client connection alive. A response
     * that already delimits its body (Content-Length or chunked) is forwarded verbatim; one that
     * relied on connection-close to signal its end is re-framed as {@code chunked} so the client
     * still knows where it ends without the connection closing. Nothing is buffered, so streaming
     * (SSE, large downloads) is preserved.
     */
    private void writeResponse(OutputStream clientOut, byte[] respHead, InputStream upIn,
                               boolean headMethod, boolean keepAlive) throws IOException {
        int status = parseStatusCode(respHead);
        boolean chunked = headerHasToken(respHead, "transfer-encoding", "chunked");
        long contentLength = headerLong(respHead, "content-length", -1);
        boolean noBody = headMethod || status == 204 || status == 304 || (status >= 100 && status < 200);
        boolean reChunk = !noBody && !chunked && contentLength < 0;

        clientOut.write(rewriteResponseHead(respHead, keepAlive, reChunk));
        if (noBody) { clientOut.flush(); return; }
        if (chunked) {
            copyChunkedBody(upIn, clientOut);
        } else if (contentLength >= 0) {
            copyExact(upIn, clientOut, contentLength);
        } else {
            reChunkToClient(upIn, clientOut);
        }
        clientOut.flush();
    }

    /** Forwards a request body to the upstream, delimited by Content-Length or chunked framing. */
    private void forwardRequestBody(InputStream clientIn, OutputStream upOut, byte[] head) throws IOException {
        if (headerHasToken(head, "transfer-encoding", "chunked")) {
            copyChunkedBody(clientIn, upOut);
        } else {
            long len = headerLong(head, "content-length", 0);
            if (len > 0) copyExact(clientIn, upOut, len);
        }
    }

    /** Bidirectional raw byte pump for WebSocket/HMR upgrades (request head already sent upstream). */
    private void rawBidirectionalPump(Socket client, InputStream clientIn, Socket upstream) {
        Thread c2u = new Thread(() -> {
            pump(clientIn, upstream);
            shutdownOutput(upstream);
        }, "pm-proxy-c2u");
        c2u.setDaemon(true);
        c2u.start();
        try {
            pump(upstream.getInputStream(), client.getOutputStream());
        } catch (IOException ignored) {
        }
        shutdownOutput(client);
        try { c2u.join(2000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private void copyExact(InputStream from, OutputStream to, long n) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long remaining = n;
        while (remaining > 0) {
            int want = (int) Math.min(buffer.length, remaining);
            int read = from.read(buffer, 0, want);
            if (read == -1) break;
            to.write(buffer, 0, read);
            remaining -= read;
        }
        to.flush();
    }

    /** Copies a chunked body verbatim (both sides speak chunked) while parsing sizes to find its end. */
    private void copyChunkedBody(InputStream from, OutputStream to) throws IOException {
        while (true) {
            ByteArrayOutputStream line = new ByteArrayOutputStream(16);
            int b;
            while ((b = from.read()) != -1) {
                line.write(b);
                if (b == '\n') break;
            }
            if (b == -1) { to.flush(); return; }
            to.write(line.toByteArray());
            String sizeLine = line.toString(StandardCharsets.ISO_8859_1).trim();
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi).trim();
            int size;
            try { size = Integer.parseInt(sizeLine, 16); }
            catch (NumberFormatException e) { to.flush(); return; }
            if (size == 0) {
                // Trailer headers up to and including the terminating blank line.
                int prev = -1, cur;
                while ((cur = from.read()) != -1) {
                    to.write(cur);
                    if (prev == '\n' && cur == '\n') break;
                    if (cur == '\n' && prev == '\r') { prev = cur; continue; }
                    prev = cur;
                }
                to.flush();
                return;
            }
            copyExact(from, to, size + 2L); // chunk data plus its trailing CRLF
        }
    }

    /** Re-frames a connection-delimited upstream body as chunked so the client stays keep-alive. */
    private void reChunkToClient(InputStream from, OutputStream to) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        int n;
        while ((n = from.read(buffer)) != -1) {
            to.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            to.write(buffer, 0, n);
            to.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            to.flush();
        }
        to.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        to.flush();
    }

    /** Rewrites a response head: normalises to HTTP/1.1, drops hop-by-hop headers, sets framing. */
    private byte[] rewriteResponseHead(byte[] head, boolean keepAlive, boolean reChunk) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        String[] lines = text.split("\r\n", -1);
        java.util.List<String> out = new java.util.ArrayList<>(lines.length + 2);
        boolean inHeaders = true;
        boolean injected = false;
        boolean first = true;
        for (String line : lines) {
            if (first) {
                out.add(line.startsWith("HTTP/1.0") ? "HTTP/1.1" + line.substring("HTTP/1.0".length()) : line);
                first = false;
                continue;
            }
            if (inHeaders && line.isEmpty()) {
                if (!injected) {
                    out.add("Connection: " + (keepAlive ? "keep-alive" : "close"));
                    if (reChunk) out.add("Transfer-Encoding: chunked");
                    injected = true;
                }
                inHeaders = false;
                out.add(line);
                continue;
            }
            if (inHeaders) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    if (name.equalsIgnoreCase("connection")
                            || name.equalsIgnoreCase("keep-alive")
                            || name.equalsIgnoreCase("proxy-connection")) continue;
                }
            }
            out.add(line);
        }
        return String.join("\r\n", out).getBytes(StandardCharsets.ISO_8859_1);
    }

    private boolean isWebSocketUpgrade(byte[] head) {
        String upgrade = headerValue(head, "upgrade");
        if (upgrade != null && !upgrade.isBlank()) return true;
        return headerHasToken(head, "connection", "upgrade");
    }

    private int parseStatusCode(byte[] head) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        int eol = text.indexOf("\r\n");
        String line = eol >= 0 ? text.substring(0, eol) : text;
        String[] parts = line.split(" ");
        if (parts.length < 2) return 0;
        try { return Integer.parseInt(parts[1]); } catch (NumberFormatException e) { return 0; }
    }

    private String headerValue(byte[] head, String name) {
        String text = new String(head, StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private boolean headerHasToken(byte[] head, String name, String token) {
        String value = headerValue(head, name);
        if (value == null) return false;
        for (String part : value.split(",")) {
            if (part.trim().equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    private long headerLong(byte[] head, String name, long fallback) {
        String value = headerValue(head, name);
        if (value == null) return fallback;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private void pump(InputStream from, OutputStream to) {
        byte[] buffer = new byte[16 * 1024];
        try {
            int n;
            while ((n = from.read(buffer)) != -1) {
                to.write(buffer, 0, n);
                to.flush();
            }
        } catch (IOException ignored) {
            // Either side closed — normal for keep-alive / websocket teardown.
        }
    }

    private void pump(InputStream from, Socket toSocket) {
        try {
            pump(from, toSocket.getOutputStream());
        } catch (IOException ignored) {}
    }

    // ---- static content -------------------------------------------------

    private void serveIndex(Socket client) {
        List<Route> list = routes.list();
        StringBuilder rows = new StringBuilder();
        if (list.isEmpty()) {
            rows.append("<tr><td colspan='4' class='muted'>No named projects yet.</td></tr>");
        }
        String suffix = boundPort == 80 ? "" : ":" + boundPort;
        for (Route r : list) {
            String url = "http://" + esc(r.alias()) + ".localhost" + suffix;
            boolean up = r.port() != null;
            String portCell = up ? String.valueOf(r.port()) : "—";
            String link = up
                    ? "<a href='" + url + "'>" + esc(r.alias()) + ".localhost</a>"
                    : "<span class='muted'>" + esc(r.alias()) + ".localhost</span>";
            rows.append("<tr><td>").append(esc(r.projectName()))
                    .append("<div class='sub'>").append(esc(r.launchName())).append("</div></td>")
                    .append("<td>").append(link).append("</td>")
                    .append("<td><span class='badge ").append(r.status()).append("'>")
                    .append(r.status()).append("</span></td>")
                    .append("<td>").append(portCell).append("</td></tr>");
        }
        String html = "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width, initial-scale=1'>"
                + "<title>Project Management — localhost</title><style>"
                + "body{font:14px/1.5 -apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:2rem auto;max-width:760px;color:#1b1f24;padding:0 1rem}"
                + "h1{font-size:1.25rem;margin:0 0 .25rem}p.lead{color:#666;margin:0 0 1.5rem}"
                + "table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:.5rem .6rem;border-bottom:1px solid #eee;vertical-align:top}"
                + "th{font-size:.72rem;text-transform:uppercase;letter-spacing:.04em;color:#888}"
                + "a{color:#2563eb;text-decoration:none}a:hover{text-decoration:underline}"
                + ".sub{color:#999;font-size:.8rem}.muted{color:#999}"
                + ".badge{font-size:.7rem;padding:.1rem .4rem;border-radius:.3rem;background:#eee;color:#555}"
                + ".badge.RUNNING,.badge.ATTACHED{background:#dcfce7;color:#166534}"
                + ".badge.EXTERNAL{background:#fef9c3;color:#854d0e}"
                + "</style></head><body>"
                + "<h1>Every project, by name</h1>"
                + "<p class='lead'>Bookmark a name and it keeps working across restarts and port changes.</p>"
                + "<table><thead><tr><th>Project</th><th>Address</th><th>Status</th><th>Port</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table></body></html>";
        sendHtml(client, 200, "OK", html);
    }

    private void serveUnavailable(Socket client, String alias) {
        String html = "<!doctype html><html><head><meta charset='utf-8'>"
                + "<title>Not reachable</title><style>"
                + "body{font:14px/1.6 -apple-system,Segoe UI,Roboto,Arial,sans-serif;margin:4rem auto;max-width:520px;color:#1b1f24;padding:0 1rem}"
                + "code{background:#f3f4f6;padding:.1rem .35rem;border-radius:.25rem}</style></head><body>"
                + "<h1>Nothing is answering here yet</h1>"
                + "<p><code>" + esc(alias) + ".localhost</code> isn't a running project right now — "
                + "it may be stopped, or the name doesn't exist.</p></body></html>";
        sendHtml(client, 502, "Bad Gateway", html);
    }

    /** Identical opaque response for unknown and not-shared hosts over the LAN — names nothing. */
    private void serveOpaque(Socket client) {
        sendHtml(client, 404, "Not Found",
                "<!doctype html><html><head><meta charset='utf-8'><title>404</title></head>"
                        + "<body></body></html>");
    }

    private void sendHtml(Socket client, int status, String reason, String html) {
        try {
            byte[] body = html.getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            OutputStream out = client.getOutputStream();
            out.write(header.getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
        } catch (IOException ignored) {
        } finally {
            closeQuietly(client);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static void shutdownOutput(Socket s) {
        try { if (!s.isClosed() && !s.isOutputShutdown()) s.shutdownOutput(); } catch (IOException ignored) {}
    }

    private static void closeQuietly(Socket s) {
        try { if (s != null && !s.isClosed()) s.close(); } catch (IOException ignored) {}
    }
}
