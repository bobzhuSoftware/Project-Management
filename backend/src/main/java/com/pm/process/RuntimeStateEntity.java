package com.pm.process;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "runtime_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeStateEntity {

    @Id
    @Column(name = "project_id", length = 36, nullable = false)
    private String projectId;

    @Column(nullable = false)
    private long pid;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "runtime_state_ports", joinColumns = @JoinColumn(name = "project_id"))
    @Column(name = "port")
    private List<Integer> recordedPorts = new ArrayList<>();
}
