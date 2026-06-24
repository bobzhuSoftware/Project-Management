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

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Project {

    @Id
    @Column(length = 36, nullable = false, updatable = false)
    private String id;

    @Column(nullable = false, unique = true, length = 200)
    private String name;

    @Column(name = "root_directory", nullable = false, length = 1000)
    private String rootDirectory;

    @Column(name = "start_command", nullable = false, length = 2000)
    private String startCommand;

    @Column(name = "stop_command", length = 2000)
    private String stopCommand;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_ports", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "port")
    @Builder.Default
    private List<Integer> ports = new ArrayList<>();

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    @Builder.Default
    private ProjectCategory category = ProjectCategory.APPLICATION;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static Project newId() {
        Project p = new Project();
        p.setId(UUID.randomUUID().toString());
        return p;
    }
}
