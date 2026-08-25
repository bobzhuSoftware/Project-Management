package com.pm.project.dto;

import com.pm.project.ProjectCommand;

public class ProjectCommandResponse {
    public String id;
    public String projectId;
    public String name;
    public String command;
    public boolean requireStopped;
    public Integer timeoutSeconds;
    public int sortOrder;

    public static ProjectCommandResponse from(ProjectCommand c) {
        ProjectCommandResponse r = new ProjectCommandResponse();
        r.id = c.getId();
        r.projectId = c.getProjectId();
        r.name = c.getName();
        r.command = c.getCommand();
        r.requireStopped = c.isRequireStopped();
        r.timeoutSeconds = c.getTimeoutSeconds();
        r.sortOrder = c.getSortOrder();
        return r;
    }
}
