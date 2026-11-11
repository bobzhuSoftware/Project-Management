package com.pm.project.dto;

import com.pm.process.ProcessSupervisor.CommandRunState;
import com.pm.project.ProjectCommand;

import java.time.Instant;

public class ProjectCommandResponse {
    public String id;
    public String projectId;
    public String name;
    public String command;
    public boolean requireStopped;
    public boolean script;
    public Integer timeoutSeconds;
    public String buildForLaunchId;
    public int sortOrder;

    // Runtime: true while the command's process is alive (script or synchronous).
    public boolean running;
    public Instant startedAt;
    // Last time a running script produced output; null for synchronous commands.
    public Instant lastOutputAt;

    public static ProjectCommandResponse from(ProjectCommand c, CommandRunState state) {
        ProjectCommandResponse r = new ProjectCommandResponse();
        r.id = c.getId();
        r.projectId = c.getProjectId();
        r.name = c.getName();
        r.command = c.getCommand();
        r.requireStopped = c.isRequireStopped();
        r.script = c.isScript();
        r.timeoutSeconds = c.getTimeoutSeconds();
        r.buildForLaunchId = c.getBuildForLaunchId();
        r.sortOrder = c.getSortOrder();
        if (state != null) {
            r.running = true;
            r.startedAt = state.startedAt();
            r.lastOutputAt = state.lastOutputAt();
        }
        return r;
    }
}
