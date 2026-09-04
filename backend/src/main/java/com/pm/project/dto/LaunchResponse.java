package com.pm.project.dto;

import com.pm.process.ManagedProcess;
import com.pm.process.RuntimeStateEntity;
import com.pm.project.Launch;
import com.pm.project.ProjectStatus;
import com.pm.project.Reach;

import java.time.Instant;
import java.util.List;

public class LaunchResponse {
    public String id;
    public String projectId;
    public String name;
    public String startCommand;
    public String stopCommand;
    public List<Integer> ports;
    public int sortOrder;

    // Rung 1: named address
    public String alias;
    public String address;

    // Rung 2/3: how far the named address reaches
    public Reach reach;
    // Rung 2: http://<alias>.local[:proxyPort] when shared over Wi-Fi, else null.
    public String wifiAddress;
    // Rung 3: temporary public share (only while a tunnel is live).
    public String shareUrl;          // base https://<random>.trycloudflare.com (no key)
    public String shareKey;          // secret to append as ?key=
    public Instant shareExpiresAt;   // when it auto-expires; null = no expiry

    // Runtime
    public ProjectStatus status;
    public Long pid;
    public Instant startedAt;
    public List<Integer> detectedPorts;

    public static LaunchResponse from(Launch l,
                                      ProjectStatus status,
                                      ManagedProcess live,
                                      RuntimeStateEntity attached,
                                      List<Integer> detectedPorts) {
        LaunchResponse r = new LaunchResponse();
        r.id = l.getId();
        r.projectId = l.getProjectId();
        r.name = l.getName();
        r.alias = l.getAlias();
        r.reach = l.getReach();
        r.startCommand = l.getStartCommand();
        r.stopCommand = l.getStopCommand();
        r.ports = l.getPorts();
        r.sortOrder = l.getSortOrder();
        r.status = status;
        r.detectedPorts = detectedPorts;
        if (live != null) {
            r.pid = live.getPid();
            r.startedAt = live.getStartedAt();
        } else if (attached != null) {
            r.pid = attached.getPid();
            r.startedAt = attached.getStartedAt();
        }
        return r;
    }
}
