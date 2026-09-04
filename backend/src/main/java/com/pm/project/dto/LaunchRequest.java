package com.pm.project.dto;

import com.pm.project.Reach;
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

    /** Optional custom alias for the {@code <alias>.localhost} address; auto-generated when blank. */
    public String alias;

    /** Optional reach (LOCAL / WIFI / INTERNET); null keeps the existing value (defaults LOCAL for new launches). */
    public Reach reach;

    public List<Integer> ports = new ArrayList<>();
}
