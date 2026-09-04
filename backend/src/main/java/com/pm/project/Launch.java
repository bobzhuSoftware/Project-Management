package com.pm.project;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A single runnable configuration (startup script) belonging to a {@link Project}. */
@Entity
@Table(name = "launches")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Launch {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(nullable = false, length = 200)
    private String name;

    /** Stable slug used for the {@code <alias>.localhost} named address (Rung 1). Unique across launches. */
    @Column(name = "alias", length = 200)
    private String alias;

    /** How far the named address reaches (Rung 2/3). Default LOCAL = localhost only. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reach", length = 16, nullable = false)
    @Builder.Default
    private Reach reach = Reach.LOCAL;

    /** When an INTERNET share link auto-expires (Rung 3). Null = no expiry (until toggled off / quit). */
    @Column(name = "share_expires_at")
    private Instant shareExpiresAt;

    @Column(name = "start_command", nullable = false, length = 2000)
    private String startCommand;

    @Column(name = "stop_command", length = 2000)
    private String stopCommand;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "launch_ports", joinColumns = @JoinColumn(name = "launch_id"))
    @Column(name = "port")
    @Builder.Default
    private List<Integer> ports = new ArrayList<>();

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Launch newId() {
        Launch l = new Launch();
        l.setId(UUID.randomUUID().toString());
        return l;
    }
}
