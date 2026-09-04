package com.pm.project;

import com.pm.process.ProcessSupervisor;
import com.pm.proxy.LocalProxyServer;
import com.pm.proxy.MdnsResponder;
import com.pm.proxy.TunnelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Start/stop of individual launches (startup scripts) within a project. */
@Service
@RequiredArgsConstructor
public class LaunchService {

    private final ProjectRepository projectRepo;
    private final LaunchRepository launchRepo;
    private final ProcessSupervisor supervisor;
    private final LocalProxyServer proxy;
    private final MdnsResponder mdns;
    private final TunnelManager tunnels;

    /** Starts the launch and returns its parent project id. */
    @Transactional
    public String start(String launchId) {
        Launch launch = launchRepo.findById(launchId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Launch not found: " + launchId));
        Project project = projectRepo.findById(launch.getProjectId())
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + launch.getProjectId()));
        supervisor.start(launch, project);
        return project.getId();
    }

    /** Stops the launch and returns its parent project id. */
    @Transactional
    public String stop(String launchId) {
        Launch launch = launchRepo.findById(launchId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Launch not found: " + launchId));
        Project project = projectRepo.findById(launch.getProjectId())
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + launch.getProjectId()));
        supervisor.stop(launch, project);
        return project.getId();
    }

    /** Updates how far the launch's named address reaches and returns its parent project id. */
    @Transactional
    public String setReach(String launchId, Reach reach, Integer shareTtlMinutes) {
        Launch launch = launchRepo.findById(launchId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Launch not found: " + launchId));
        Reach previous = launch.getReach() != null ? launch.getReach() : Reach.LOCAL;
        Reach target = reach != null ? reach : Reach.LOCAL;
        Instant previousExpiry = launch.getShareExpiresAt();

        launch.setReach(target);
        launch.setShareExpiresAt(target == Reach.INTERNET ? computeExpiry(shareTtlMinutes) : null);
        launch.setUpdatedAt(Instant.now());
        launchRepo.save(launch);

        if (target == Reach.INTERNET) {
            try {
                tunnels.start(launch);
            } catch (RuntimeException e) {
                // Roll back so the UI reflects that the share never came up.
                launch.setReach(previous);
                launch.setShareExpiresAt(previous == Reach.INTERNET ? previousExpiry : null);
                launch.setUpdatedAt(Instant.now());
                launchRepo.save(launch);
                proxy.refreshLanBinding();
                mdns.refresh();
                throw e;
            }
        } else {
            tunnels.stop(launchId);
        }
        // Re-evaluate LAN exposure: bind/unbind 0.0.0.0 and add/remove the <alias>.local announcement.
        proxy.refreshLanBinding();
        mdns.refresh();
        return launch.getProjectId();
    }

    /** Turns a requested time-to-live into an absolute expiry, or null for "no expiry". */
    private Instant computeExpiry(Integer ttlMinutes) {
        if (ttlMinutes == null || ttlMinutes <= 0) return null;
        return Instant.now().plusSeconds(ttlMinutes * 60L);
    }
}
