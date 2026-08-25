package com.pm.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectCommandRepository extends JpaRepository<ProjectCommand, String> {
    List<ProjectCommand> findByProjectIdOrderBySortOrderAsc(String projectId);
    void deleteByProjectId(String projectId);
}
