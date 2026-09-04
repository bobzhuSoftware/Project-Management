package com.pm.project;

import com.pm.project.dto.ProjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    @PutMapping("/{launchId}/reach")
    public ProjectResponse setReach(@PathVariable String launchId, @RequestBody ReachRequest body) {
        String projectId = launchService.setReach(launchId, body.reach, body.shareTtlMinutes);
        return projectService.get(projectId);
    }

    public static class ReachRequest {
        public Reach reach;
        /** For reach=INTERNET: minutes until the public link auto-expires; null/0 = no expiry. */
        public Integer shareTtlMinutes;
    }
}
