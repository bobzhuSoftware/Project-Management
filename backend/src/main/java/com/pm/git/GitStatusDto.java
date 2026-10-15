package com.pm.git;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class GitStatusDto {
    public boolean repo;
    /** Null when HEAD is detached. */
    public String branch;
    public boolean detached;
    /** Abbreviated commit id; only set when detached. */
    public String headShort;
    /** origin's default branch (or main/master), null when unknown. */
    public String defaultBranch;
    public String remoteUrl;
    /** Whether an "origin" remote is configured. */
    public boolean hasRemote;
    /** Whether refs/remotes/origin/&lt;branch&gt; exists, regardless of tracking config. */
    public boolean remoteBranchExists;
    /** Tracking ref such as "origin/main"; null when there is no upstream. */
    public String upstreamName;
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
