package com.pm.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LaunchRepository extends JpaRepository<Launch, String> {
    List<Launch> findByProjectIdOrderBySortOrderAsc(String projectId);
    void deleteByProjectId(String projectId);
    Optional<Launch> findByAliasIgnoreCase(String alias);
}
