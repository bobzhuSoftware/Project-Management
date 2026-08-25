package com.pm.project.dto;

import com.pm.project.Project;
import com.pm.project.ProjectCategory;

import java.time.Instant;
import java.util.List;

public class ProjectResponse {
    public String id;
    public String name;
    public String rootDirectory;
    public String description;
    public ProjectCategory category;
    public Instant createdAt;
    public Instant updatedAt;

    public int sortOrder;
    public boolean pushEnabled;

    /** Runnable configurations, each with its own runtime state. */
    public List<LaunchResponse> launches;

    /** User-defined maintenance commands (clean, build frontend, ...). */
    public List<ProjectCommandResponse> commands;

    public static ProjectResponse from(Project p, List<LaunchResponse> launches, List<ProjectCommandResponse> commands) {
        ProjectResponse r = new ProjectResponse();
        r.id = p.getId();
        r.name = p.getName();
        r.rootDirectory = p.getRootDirectory();
        r.description = p.getDescription();
        r.category = p.getCategory();
        r.sortOrder = p.getSortOrder();
        r.pushEnabled = p.isPushEnabled();
        r.createdAt = p.getCreatedAt();
        r.updatedAt = p.getUpdatedAt();
        r.launches = launches;
        r.commands = commands;
        return r;
    }
}
