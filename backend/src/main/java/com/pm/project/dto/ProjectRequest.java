package com.pm.project.dto;

import com.pm.project.ProjectCategory;
import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

public class ProjectRequest {
    @NotBlank
    public String name;

    @NotBlank
    public String rootDirectory;

    @NotBlank
    public String startCommand;

    public String stopCommand;
    public List<Integer> ports = new ArrayList<>();
    public String description;
    public ProjectCategory category;
    public Boolean pushEnabled;
}
