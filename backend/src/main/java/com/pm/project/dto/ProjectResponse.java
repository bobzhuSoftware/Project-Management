package com.pm.project.dto;

import com.pm.process.ManagedProcess;
import com.pm.process.RuntimeStateEntity;
import com.pm.project.Project;
import com.pm.project.ProjectCategory;
import com.pm.project.ProjectStatus;

import java.time.Instant;
import java.util.List;

public class ProjectResponse {
    public String id;
    public String name;
    public String rootDirectory;
    public String startCommand;
    public String stopCommand;
    public List<Integer> ports;
    public String description;
    public ProjectCategory category;
    public Instant createdAt;
    public Instant updatedAt;

    public int sortOrder;

    // Runtime
    public ProjectStatus status;
    public Long pid;
    public Instant startedAt;
    public List<Integer> detectedPorts;

    public static ProjectResponse from(Project p,
                                       ProjectStatus status,
                                       ManagedProcess live,
                                       RuntimeStateEntity attached,
                                       List<Integer> detectedPorts) {
        ProjectResponse r = new ProjectResponse();
        r.id = p.getId();
        r.name = p.getName();
        r.rootDirectory = p.getRootDirectory();
        r.startCommand = p.getStartCommand();
        r.stopCommand = p.getStopCommand();
        r.ports = p.getPorts();
        r.description = p.getDescription();
        r.category = p.getCategory();
        r.sortOrder = p.getSortOrder();
        r.createdAt = p.getCreatedAt();
        r.updatedAt = p.getUpdatedAt();
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
