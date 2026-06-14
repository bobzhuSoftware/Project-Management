package com.pm.git;

import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/projects/{id}/git")
@RequiredArgsConstructor
public class GitController {

    private final GitService gitService;

    @GetMapping("/status")
    public GitStatusDto status(@PathVariable String id,
                               @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        return gitService.status(id, refresh);
    }

    @PostMapping("/sync")
    public GitSyncResultDto sync(@PathVariable String id, @RequestBody(required = false) SyncRequest body) {
        String message = body != null ? body.message : null;
        return gitService.sync(id, message);
    }

    public static class SyncRequest {
        @Size(max = 500)
        public String message;
    }
}
