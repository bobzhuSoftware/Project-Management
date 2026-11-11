package com.pm.build;

import com.pm.project.LaunchRepository;
import com.pm.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/launches")
@RequiredArgsConstructor
public class BuildController {

    private final BuildRecordService builds;
    private final LaunchRepository launchRepo;

    /** The most recent build records for a launch, newest first. */
    @GetMapping("/{launchId}/builds")
    public List<BuildRecordDto> history(@PathVariable String launchId) {
        if (!launchRepo.existsById(launchId)) {
            throw new ProjectService.NotFoundException("Launch not found: " + launchId);
        }
        return builds.history(launchId);
    }
}
