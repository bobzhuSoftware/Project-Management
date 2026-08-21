package com.pm.project.dto;

import com.pm.process.ManagedProcess;
import com.pm.process.RuntimeStateEntity;
import com.pm.project.Launch;
import com.pm.project.ProjectStatus;

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
