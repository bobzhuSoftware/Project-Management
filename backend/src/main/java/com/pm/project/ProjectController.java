package com.pm.project;

import com.pm.project.dto.ProjectRequest;
import com.pm.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {

    private final ProjectService service;

    @GetMapping
    public List<ProjectResponse> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ProjectResponse create(@Valid @RequestBody ProjectRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable String id, @Valid @RequestBody ProjectRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/start")
    public ProjectResponse start(@PathVariable String id) {
        return service.start(id);
    }

    @PostMapping("/{id}/stop")
    public ProjectResponse stop(@PathVariable String id) {
        return service.stop(id);
    }

    @PutMapping("/{id}/push-enabled")
    public ProjectResponse setPushEnabled(@PathVariable String id, @RequestBody PushToggleRequest body) {
        return service.setPushEnabled(id, body.enabled);
    }

    public static class PushToggleRequest {
        public boolean enabled;
    }

    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<String> orderedIds) {
        service.reorder(orderedIds);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/open-folder")
    public ResponseEntity<Void> openFolder(@PathVariable String id) {
        service.openFolder(id);
        return ResponseEntity.noContent().build();
    }
}
