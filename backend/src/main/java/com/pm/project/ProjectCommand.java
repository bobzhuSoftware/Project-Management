package com.pm.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A user-defined maintenance command (clean, build frontend, build backend, ...) belonging to a {@link Project}. */
@Entity
@Table(name = "project_commands")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectCommand {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, length = 2000)
    private String command;

    /** When true the command may only run while every launch of the project is stopped (e.g. clean). */
    @Column(name = "require_stopped", nullable = false)
    @Builder.Default
    private boolean requireStopped = false;

    /** Optional per-command timeout; falls back to a default when null. */
    @Column(name = "timeout_seconds")
    private Integer timeoutSeconds;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    public static ProjectCommand newId() {
        ProjectCommand c = new ProjectCommand();
        c.setId(UUID.randomUUID().toString());
        return c;
    }
}
