package com.pm.git;

public class GitFileChange {
    public String path;
    public String type;    // ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED, CONFLICT
    public boolean staged; // true = index (green), false = work tree (red)

    public GitFileChange(String path, String type, boolean staged) {
        this.path = path;
        this.type = type;
        this.staged = staged;
    }
}
