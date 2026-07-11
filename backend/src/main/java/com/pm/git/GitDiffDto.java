package com.pm.git;

public class GitDiffDto {
    public String path;
    public boolean staged;
    public boolean binary;   // true when git reports a binary diff
    public boolean truncated; // true when output exceeded the size limit
    public String diff;      // unified diff text

    public GitDiffDto(String path, boolean staged, boolean binary, boolean truncated, String diff) {
        this.path = path;
        this.staged = staged;
        this.binary = binary;
        this.truncated = truncated;
        this.diff = diff;
    }
}
