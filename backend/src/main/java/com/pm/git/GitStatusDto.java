package com.pm.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    /** True when this status was verified against the real remote (a fetch succeeded). */
    public boolean remoteChecked;
    /** Populated when the remote check failed (offline / authentication required). */
    public String remoteError;
    public Instant checkedAt;
    public String error;
    public List<GitFileChange> files = new ArrayList<>();

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
