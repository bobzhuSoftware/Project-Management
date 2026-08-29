package com.pm.project.dto;

import jakarta.validation.constraints.NotBlank;

public class ProjectCommandRequest {
    /** Null / blank for a new command; existing command id when editing. */
    public String id;

    @NotBlank
    public String name;

    @NotBlank
    public String command;

    public Boolean requireStopped;
    public Boolean script;
    public Integer timeoutSeconds;
}
