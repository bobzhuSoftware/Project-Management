package com.pm.project;

import com.pm.project.dto.ProjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/launches")
@RequiredArgsConstructor
public class LaunchController {

    private final LaunchService launchService;
    private final ProjectService projectService;

    @PostMapping("/{launchId}/start")
    public ProjectResponse start(@PathVariable String launchId) {
        String projectId = launchService.start(launchId);
        return projectService.get(projectId);
    }

    @PostMapping("/{launchId}/stop")
    public ProjectResponse stop(@PathVariable String launchId) {
        String projectId = launchService.stop(launchId);
        return projectService.get(projectId);
    }
}
