package com.pm.build;

import java.time.Instant;

public class BuildRecordDto {
    public String id;
    public String commandName;
    public String commitSha;
    public String commitSubject;
    public String branch;
    public BuildStatus status;
    public Integer exitCode;
    public Instant startedAt;
    public Instant finishedAt;

    public static BuildRecordDto from(BuildRecord r) {
        BuildRecordDto d = new BuildRecordDto();
        d.id = r.getId();
        d.commandName = r.getCommandName();
        d.commitSha = r.getCommitSha();
        d.commitSubject = r.getCommitSubject();
        d.branch = r.getBranch();
        d.status = r.getStatus();
        d.exitCode = r.getExitCode();
        d.startedAt = r.getStartedAt();
        d.finishedAt = r.getFinishedAt();
        return d;
    }
}
