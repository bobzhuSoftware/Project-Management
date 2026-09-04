package com.pm.proxy;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Maps a live public tunnel hostname (e.g. {@code foo.trycloudflare.com}) to the launch it fronts
 * and the secret key that gates it. Kept separate from {@link TunnelManager} so the proxy can read
 * it without a dependency cycle (proxy -&gt; registry &lt;- tunnel manager).
 */
@Component
public class TunnelRegistry {

    /** One public tunnel: which alias it routes to and the key required to reach it. */
    public record Entry(String launchId, String alias, String key) {}

    // Keyed by lowercase host with no port, matching how the proxy parses the Host header.
    private final Map<String, Entry> byHost = new ConcurrentHashMap<>();

    public void put(String host, Entry entry) {
        if (host == null || host.isBlank()) return;
        byHost.put(host.toLowerCase(), entry);
    }

    public void remove(String host) {
        if (host == null) return;
        byHost.remove(host.toLowerCase());
    }

    public Optional<Entry> lookup(String host) {
        if (host == null) return Optional.empty();
        return Optional.ofNullable(byHost.get(host.toLowerCase()));
    }
}
