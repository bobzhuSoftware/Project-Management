package com.pm.git;

/** The commit a project's working tree is currently on. {@code branch} is null for a detached HEAD. */
public record HeadInfo(String sha, String subject, String branch) {}
