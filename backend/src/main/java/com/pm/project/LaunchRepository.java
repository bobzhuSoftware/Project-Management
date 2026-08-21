package com.pm.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LaunchRepository extends JpaRepository<Launch, String> {
    List<Launch> findByProjectIdOrderBySortOrderAsc(String projectId);
    void deleteByProjectId(String projectId);
}
