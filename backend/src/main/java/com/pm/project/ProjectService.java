package com.pm.project;

import com.pm.process.ProcessSupervisor;
import com.pm.project.dto.ProjectRequest;
import com.pm.project.dto.ProjectResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository repo;
    private final ProcessSupervisor supervisor;

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return repo.findAllByOrderBySortOrderAsc().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        return toResponse(p);
    }

    @Transactional
    public ProjectResponse create(ProjectRequest req) {
        repo.findByName(req.name).ifPresent(p -> {
            throw new IllegalArgumentException("Project name already exists: " + req.name);
        });
        Instant now = Instant.now();
        Project p = Project.newId();
        applyRequest(p, req);
        p.setSortOrder((int) repo.count());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        return toResponse(repo.save(p));
    }

    @Transactional
    public ProjectResponse update(String id, ProjectRequest req) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        repo.findByName(req.name).ifPresent(existing -> {
            if (!existing.getId().equals(id)) {
                throw new IllegalArgumentException("Project name already exists: " + req.name);
            }
        });
        applyRequest(p, req);
        p.setUpdatedAt(Instant.now());
        return toResponse(repo.save(p));
    }

    @Transactional
    public void delete(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        ProjectStatus status = supervisor.statusOf(p);
        if (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED) {
            throw new IllegalStateException("Stop the project before deleting it.");
        }
        repo.delete(p);
    }

    @Transactional
    public ProjectResponse start(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        supervisor.start(p);
        return toResponse(p);
    }

    @Transactional
    public ProjectResponse stop(String id) {
        Project p = repo.findById(id).orElseThrow(() -> new NotFoundException("Project not found: " + id));
        supervisor.stop(p);
        return toResponse(p);
    }

    @Transactional
    public void reorder(List<String> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Project p = repo.findById(orderedIds.get(i))
                    .orElseThrow(() -> new NotFoundException("Project not found"));
            p.setSortOrder(i);
            repo.save(p);
        }
    }

    private void applyRequest(Project p, ProjectRequest req) {
        p.setName(req.name.trim());
        p.setRootDirectory(req.rootDirectory.trim());
        p.setStartCommand(req.startCommand.trim());
        p.setStopCommand(req.stopCommand != null ? req.stopCommand.trim() : null);
        p.setPorts(req.ports != null ? new ArrayList<>(req.ports) : new ArrayList<>());
        p.setDescription(req.description);
    }

    private ProjectResponse toResponse(Project p) {
        ProjectStatus status = supervisor.statusOf(p);
        List<Integer> detected = (status == ProjectStatus.RUNNING || status == ProjectStatus.ATTACHED)
                ? supervisor.detectListeningPorts(p)
                : List.of();
        return ProjectResponse.from(
                p,
                status,
                supervisor.getLive(p.getId()).orElse(null),
                supervisor.getRuntimeState(p.getId()).orElse(null),
                detected);
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String msg) { super(msg); }
    }
}
