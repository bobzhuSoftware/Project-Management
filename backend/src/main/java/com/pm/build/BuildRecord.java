package com.pm.build;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One run of a build command that is linked to a launch. The launch/command ids are deliberately
 * not foreign keys, and the command name is a snapshot, so history survives command edits and deletes.
 */
@Entity
@Table(name = "build_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BuildRecord {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(name = "launch_id", length = 36, nullable = false)
    private String launchId;

    @Column(name = "command_id", length = 36, nullable = false)
    private String commandId;

    @Column(name = "command_name", length = 200, nullable = false)
    private String commandName;

    /** HEAD when the build started; null for a non-git directory or when HEAD could not be read. */
    @Column(name = "commit_sha", length = 40)
    private String commitSha;

    @Column(name = "commit_subject", length = 500)
    private String commitSubject;

    @Column(length = 200)
    private String branch;

    // Explicit column type so Hibernate doesn't generate a CHECK constraint listing the enum values:
    // such a constraint rejects any status added later (see BuildStatusConstraintMigration).
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false, columnDefinition = "VARCHAR(16)")
    private BuildStatus status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;
}
