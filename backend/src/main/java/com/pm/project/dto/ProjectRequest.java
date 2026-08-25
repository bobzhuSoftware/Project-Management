package com.pm.project.dto;

import com.pm.project.ProjectCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

public class ProjectRequest {
    @NotBlank
    public String name;

    @NotBlank
    public String rootDirectory;

    public String description;
    public ProjectCategory category;
    public Boolean pushEnabled;

    /** One or more runnable configurations (startup scripts) for this project. */
    @Valid
    public List<LaunchRequest> launches = new ArrayList<>();

    /** User-defined maintenance commands (clean, build frontend, build backend, ...). */
    @Valid
    public List<ProjectCommandRequest> commands = new ArrayList<>();
}
