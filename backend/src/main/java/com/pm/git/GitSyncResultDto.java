package com.pm.git;

import java.util.List;

public class GitSyncResultDto {
    public boolean success;
    public String message;
    public List<String> steps;
    public GitStatusDto status;

    public static GitSyncResultDto ok(List<String> steps, GitStatusDto status) {
        GitSyncResultDto r = new GitSyncResultDto();
        r.success = true;
        r.steps = steps;
        r.status = status;
        return r;
    }

    public static GitSyncResultDto fail(String message, List<String> steps, GitStatusDto status) {
        GitSyncResultDto r = new GitSyncResultDto();
        r.success = false;
        r.message = message;
        r.steps = steps;
        r.status = status;
        return r;
    }
}
