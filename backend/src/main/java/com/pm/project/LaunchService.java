package com.pm.project;

import com.pm.process.ProcessSupervisor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Start/stop of individual launches (startup scripts) within a project. */
@Service
@RequiredArgsConstructor
public class LaunchService {

    private final ProjectRepository projectRepo;
    private final LaunchRepository launchRepo;
    private final ProcessSupervisor supervisor;

    /** Starts the launch and returns its parent project id. */
    @Transactional
    public String start(String launchId) {
        Launch launch = launchRepo.findById(launchId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Launch not found: " + launchId));
        Project project = projectRepo.findById(launch.getProjectId())
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + launch.getProjectId()));
        supervisor.start(launch, project);
        return project.getId();
    }

    /** Stops the launch and returns its parent project id. */
    @Transactional
    public String stop(String launchId) {
        Launch launch = launchRepo.findById(launchId)
                .orElseThrow(() -> new ProjectService.NotFoundException("Launch not found: " + launchId));
        Project project = projectRepo.findById(launch.getProjectId())
                .orElseThrow(() -> new ProjectService.NotFoundException("Project not found: " + launch.getProjectId()));
        supervisor.stop(launch, project);
        return project.getId();
    }
}
