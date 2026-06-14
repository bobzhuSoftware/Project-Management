package com.pm.git;

import java.time.Instant;

public class GitStatusDto {
    public boolean repo;
    public String branch;
    public String remoteUrl;
    public boolean hasUpstream;
    public int ahead;
    public int behind;
    public int staged;
    public int modified;
    public int untracked;
    public int conflicting;
    public boolean clean;
    public boolean inSync;
    public Instant checkedAt;
    public String error;

    public static GitStatusDto notRepo() {
        GitStatusDto d = new GitStatusDto();
        d.repo = false;
        d.checkedAt = Instant.now();
        return d;
    }

    public static GitStatusDto error(String message) {
        GitStatusDto d = new GitStatusDto();
        d.repo = false;
        d.error = message;
        d.checkedAt = Instant.now();
        return d;
    }
}
