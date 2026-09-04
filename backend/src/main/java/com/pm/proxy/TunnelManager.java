package com.pm.proxy;

import com.pm.project.Launch;
import com.pm.project.LaunchRepository;
import com.pm.project.Reach;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rung 3: exposes a launch over a temporary public HTTPS URL using a Cloudflare
 * <a href="https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/">quick tunnel</a>.
 *
 * <p>Each shared launch spawns its own {@code cloudflared} process pointed at the local proxy port.
 * Cloudflare hands back a {@code https://<random>.trycloudflare.com} URL; we register that hostname
 * with a per-share secret key in {@link TunnelRegistry} so the proxy can route and gate it. No
 * Cloudflare account, sign-up or domain is needed for quick tunnels; only the host needs the
 * {@code cloudflared} binary, and visitors need only a browser.
 */
@Slf4j
@Component
public class TunnelManager {

    private static final Pattern TRYCLOUDFLARE_URL =
            Pattern.compile("https://[a-z0-9-]+\\.trycloudflare\\.com");
    private static final DateTimeFormatter LOG_DATE = DateTimeFormatter.ISO_DATE;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final LocalProxyServer proxy;
    private final LaunchRepository launchRepo;
    private final MdnsResponder mdns;
    private final TunnelRegistry registry;

    @Value("${pm.tunnel.enabled:true}")
    private boolean enabled;

    @Value("${pm.tunnel.cloudflared-path:cloudflared}")
    private String cloudflaredPath;

    @Value("${pm.tunnel.start-timeout-seconds:45}")
    private int startTimeoutSeconds;

    @Value("${pm.logs.dir}")
    private String logsDir;

    private volatile Boolean availableCache;

    private final Map<String, Active> active = new ConcurrentHashMap<>();

    /** A running cloudflared process fronting one launch. */
    private record Active(String launchId, String alias, String host, String url, String key,
                          Process process, Instant expiresAt) {}

    /** The public link details surfaced to the UI. */
    public record ShareInfo(String url, String key, Instant expiresAt) {}

    public TunnelManager(LocalProxyServer proxy, LaunchRepository launchRepo,
                         MdnsResponder mdns, TunnelRegistry registry) {
        this.proxy = proxy;
        this.launchRepo = launchRepo;
        this.mdns = mdns;
        this.registry = registry;
    }

    /** True when Internet sharing is enabled and the {@code cloudflared} binary is runnable. */
    public boolean isAvailable() {
        if (!enabled) return false;
        Boolean cached = availableCache;
        if (cached != null) return cached;
        synchronized (this) {
            if (availableCache == null) availableCache = probeCloudflared();
            return availableCache;
        }
    }

    private boolean probeCloudflared() {
        try {
            Process p = new ProcessBuilder(cloudflaredPath, "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            boolean ok = p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
            if (!ok) log.warn("cloudflared not usable (exit != 0); Internet sharing disabled");
            return ok;
        } catch (IOException e) {
            log.warn("cloudflared binary not found on PATH ('{}'); Internet sharing unavailable. "
                    + "Install it (winget install --id Cloudflare.cloudflared) to enable public links.",
                    cloudflaredPath);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The active share for a launch, if a tunnel is currently up. */
    public Optional<ShareInfo> current(String launchId) {
        Active a = active.get(launchId);
        return a == null ? Optional.empty()
                : Optional.of(new ShareInfo(a.url(), a.key(), a.expiresAt()));
    }

    /**
     * Starts (or restarts) a public tunnel for the given launch and returns its link details.
     * The launch's {@code shareExpiresAt} is used only to remember when to auto-close it; pass a
     * launch whose reach is already INTERNET. Throws when cloudflared is missing or never prints a URL.
     */
    public synchronized ShareInfo start(Launch launch) {
        if (!enabled) throw new IllegalStateException("Internet sharing is disabled (pm.tunnel.enabled=false).");
        if (!isAvailable()) {
            throw new IllegalStateException("cloudflared is not installed on this machine. Install it "
                    + "(e.g. `winget install --id Cloudflare.cloudflared`) to share a launch on the Internet.");
        }
        int target = proxy.getBoundPort();
        if (target <= 0) throw new IllegalStateException("Local proxy is not listening; cannot open a tunnel.");

        stop(launch.getId()); // replace any previous tunnel for this launch

        String key = newKey();
        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    cloudflaredPath, "tunnel", "--no-autoupdate",
                    "--url", "http://127.0.0.1:" + target);
            pb.redirectErrorStream(true);
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start cloudflared: " + e.getMessage(), e);
        }

        CompletableFuture<String> urlFuture = new CompletableFuture<>();
        Path logFile = Paths.get(logsDir,
                "tunnel-" + launch.getId() + "-" + LocalDate.now().format(LOG_DATE) + ".log");
        Thread reader = new Thread(() -> readOutput(p, logFile, urlFuture), "pm-tunnel-" + launch.getId());
        reader.setDaemon(true);
        reader.start();

        String url;
        try {
            url = urlFuture.get(startTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            p.destroyForcibly();
            throw new IllegalStateException("cloudflared did not return a public URL within "
                    + startTimeoutSeconds + "s. Check " + logFile + " and your network.");
        } catch (ExecutionException e) {
            p.destroyForcibly();
            String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            throw new IllegalStateException(reason != null ? reason
                    : "cloudflared failed to open a tunnel. Check " + logFile + ".");
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting cloudflared.", e);
        }

        String host = hostOf(url);
        Active a = new Active(launch.getId(), launch.getAlias(), host, url, key, p, launch.getShareExpiresAt());
        active.put(launch.getId(), a);
        registry.put(host, new TunnelRegistry.Entry(launch.getId(), launch.getAlias(), key));
        // Auto-close if cloudflared dies on its own (network drop, crash).
        p.onExit().thenRun(() -> {
            if (active.remove(launch.getId(), a)) registry.remove(host);
        });
        log.info("Internet share up for '{}': {} (expires {})",
                launch.getAlias(), url, a.expiresAt() == null ? "never" : a.expiresAt());
        return new ShareInfo(url, key, a.expiresAt());
    }

    /** Stops the tunnel for a launch, if any. */
    public synchronized void stop(String launchId) {
        Active a = active.remove(launchId);
        if (a == null) return;
        registry.remove(a.host());
        try { a.process().destroyForcibly(); } catch (Exception ignored) {}
        log.info("Internet share stopped for '{}'", a.alias());
    }

    private void readOutput(Process p, Path logFile, CompletableFuture<String> urlFuture) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = openLog(logFile)) {
            String line;
            while ((line = in.readLine()) != null) {
                if (out != null) { out.write(line); out.newLine(); out.flush(); }
                if (!urlFuture.isDone()) {
                    Matcher m = TRYCLOUDFLARE_URL.matcher(line);
                    if (m.find()) { urlFuture.complete(m.group()); continue; }
                    // Cloudflare rate-limits anonymous quick tunnels per IP (HTTP 429 / error 1015).
                    if (line.contains("429 Too Many Requests") || line.contains("error code: 1015")) {
                        urlFuture.completeExceptionally(new IOException(
                                "Cloudflare is rate-limiting anonymous quick tunnels (HTTP 429 / error 1015). "
                                + "Wait a few minutes before sharing again, or set up a named Cloudflare tunnel."));
                    }
                }
            }
        } catch (IOException e) {
            log.debug("Tunnel output reader ended: {}", e.getMessage());
        } finally {
            if (!urlFuture.isDone()) {
                urlFuture.completeExceptionally(new IOException("cloudflared exited before printing a URL"));
            }
        }
    }

    private BufferedWriter openLog(Path logFile) {
        try {
            Files.createDirectories(logFile.getParent());
            return Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Could not open tunnel log {}: {}", logFile, e.getMessage());
            return null;
        }
    }

    /** Auto-close tunnels whose share window has elapsed, flipping the launch back to Local. */
    @Scheduled(fixedDelay = 30_000)
    void reapExpired() {
        Instant now = Instant.now();
        for (Active a : active.values()) {
            if (a.expiresAt() != null && now.isAfter(a.expiresAt())) {
                log.info("Internet share for '{}' expired; closing", a.alias());
                turnOff(a.launchId());
            }
        }
    }

    /** Flip a launch fully off (reach=LOCAL) and tear down its tunnel + mDNS. */
    private void turnOff(String launchId) {
        stop(launchId);
        launchRepo.findById(launchId).ifPresent(l -> {
            l.setReach(Reach.LOCAL);
            l.setShareExpiresAt(null);
            l.setUpdatedAt(Instant.now());
            launchRepo.save(l);
        });
        proxy.refreshLanBinding();
        mdns.refresh();
    }

    /** On boot, re-establish tunnels for launches still marked INTERNET (each gets a fresh URL). */
    @EventListener(ApplicationReadyEvent.class)
    void restoreOnBoot() {
        if (!enabled) return;
        Thread t = new Thread(() -> {
            for (Launch l : launchRepo.findAll()) {
                if (l.getReach() != Reach.INTERNET) continue;
                Instant exp = l.getShareExpiresAt();
                if (exp != null && Instant.now().isAfter(exp)) {
                    turnOff(l.getId());
                    continue;
                }
                try {
                    start(l);
                } catch (RuntimeException e) {
                    log.warn("Could not restore Internet share for '{}': {}", l.getAlias(), e.getMessage());
                }
            }
        }, "pm-tunnel-restore");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void shutdown() {
        active.values().forEach(a -> {
            try { a.process().destroyForcibly(); } catch (Exception ignored) {}
        });
        active.clear();
    }

    private static String newKey() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hostOf(String url) {
        String h = url.replaceFirst("^https?://", "");
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.toLowerCase();
    }
}
