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

    /** Clean command lives at the repository level (shared across all launches). */
    public String cleanCommand;

    public String description;
    public ProjectCategory category;
    public Boolean pushEnabled;

    /** One or more runnable configurations (startup scripts) for this project. */
    @Valid
    public List<LaunchRequest> launches = new ArrayList<>();
}
