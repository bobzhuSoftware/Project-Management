package com.pm.project.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.ArrayList;
import java.util.List;

public class LaunchRequest {
    /** Null / blank for a new launch; existing launch id when editing. */
    public String id;

    @NotBlank
    public String name;

    @NotBlank
    public String startCommand;

    public String stopCommand;
    public List<Integer> ports = new ArrayList<>();
}
