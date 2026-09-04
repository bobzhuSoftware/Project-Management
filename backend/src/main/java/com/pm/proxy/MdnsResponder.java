package com.pm.proxy;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rung 2 mDNS responder. For every launch shared over Wi-Fi (reach &ge; WIFI) it announces a
 * single-label {@code <alias>.local} A-record pointing at this host's LAN IPv4, so a phone on
 * the same network can open {@code http://<alias>.local[:proxyPort]}.
 *
 * <p>iOS only resolves single-label {@code .local} names, so each alias gets its own JmDNS
 * instance (its own hostname) rather than sharing one machine hostname.
 */
@Slf4j
@Component
public class MdnsResponder {

    private final RouteRegistry routes;
    private final LocalProxyServer proxy;

    @Value("${pm.mdns.enabled:true}")
    private boolean enabled;

    /** alias -> the JmDNS instance announcing {@code <alias>.local}. */
    private final ConcurrentHashMap<String, JmDNS> announced = new ConcurrentHashMap<>();
    private volatile InetAddress lastLan;

    public MdnsResponder(RouteRegistry routes, LocalProxyServer proxy) {
        this.routes = routes;
        this.proxy = proxy;
    }

    @PostConstruct
    void init() {
        if (!enabled) {
            log.info("mDNS responder disabled (pm.mdns.enabled=false)");
            return;
        }
        refresh();
    }

    /**
     * Reconciles the set of announced {@code <alias>.local} names with the launches currently
     * shared over Wi-Fi. Safe to call whenever a launch's reach changes.
     */
    public synchronized void refresh() {
        if (!enabled) return;
        // Without a bound LAN listener there's no working port to advertise; announcing anyway would
        // point phones at :80 (owned by http.sys) and yield a 404. Withdraw everything until it binds.
        if (proxy.getLanPort() <= 0) {
            if (!announced.isEmpty()) {
                log.warn("LAN proxy not bound; withdrawing all <alias>.local announcements");
                closeAll();
            }
            return;
        }
        InetAddress lan = lanIpv4();
        if (lan == null) {
            if (!announced.isEmpty()) {
                log.warn("No LAN IPv4 found; withdrawing all <alias>.local announcements");
                closeAll();
            }
            return;
        }
        // If the LAN address changed (e.g. moved networks), rebuild everything on the new IP.
        if (lastLan != null && !lastLan.equals(lan)) {
            closeAll();
        }
        lastLan = lan;

        Set<String> desired = routes.sharedAliases();
        // Withdraw names no longer shared.
        for (String alias : new HashSet<>(announced.keySet())) {
            if (!desired.contains(alias)) {
                closeOne(alias);
            }
        }
        // Announce newly shared names.
        for (String alias : desired) {
            if (!announced.containsKey(alias)) {
                announce(alias, lan);
            }
        }
    }

    private void announce(String alias, InetAddress lan) {
        int port = proxy.getLanPort();
        if (port <= 0) return;   // no LAN listener bound; nothing worth announcing
        try {
            JmDNS jmdns = JmDNS.create(lan, alias);
            // A plain _http._tcp service backs the <alias>.local hostname so discovery tools see it;
            // Safari only needs the A-record, which JmDNS answers for this instance's hostname.
            ServiceInfo info = ServiceInfo.create("_http._tcp.local.", alias, port, "path=/");
            jmdns.registerService(info);
            announced.put(alias, jmdns);
            log.info("mDNS announcing {}.local -> {}:{}", alias, lan.getHostAddress(), port);
        } catch (IOException e) {
            log.warn("mDNS announce failed for {}.local: {}", alias, e.getMessage());
        }
    }

    private void closeOne(String alias) {
        JmDNS jmdns = announced.remove(alias);
        if (jmdns == null) return;
        try {
            jmdns.unregisterAllServices();
            jmdns.close();
            log.info("mDNS withdrew {}.local", alias);
        } catch (IOException e) {
            log.debug("mDNS close error for {}.local: {}", alias, e.getMessage());
        }
    }

    private void closeAll() {
        for (String alias : new HashSet<>(announced.keySet())) {
            closeOne(alias);
        }
    }

    @PreDestroy
    void shutdown() {
        closeAll();
    }

    /** First site-local (private) IPv4 on an up, non-loopback interface, or null when offline. */
    private InetAddress lanIpv4() {
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            while (nics.hasMoreElements()) {
                NetworkInterface nic = nics.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) continue;
                Enumeration<InetAddress> addrs = nic.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a.isLoopbackAddress() || a.getAddress().length != 4) continue;
                    if (a.isSiteLocalAddress()) return a;
                }
            }
        } catch (Exception e) {
            log.debug("LAN IPv4 lookup failed: {}", e.getMessage());
        }
        return null;
    }
}
