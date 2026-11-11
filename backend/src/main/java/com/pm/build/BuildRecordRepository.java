package com.pm.build;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BuildRecordRepository extends JpaRepository<BuildRecord, String> {
    List<BuildRecord> findTop20ByLaunchIdOrderByStartedAtDesc(String launchId);
    List<BuildRecord> findByLaunchIdOrderByStartedAtDesc(String launchId);
    Optional<BuildRecord> findFirstByLaunchIdOrderByStartedAtDesc(String launchId);
    Optional<BuildRecord> findFirstByCommandIdAndLaunchIdAndStatusOrderByStartedAtDesc(
            String commandId, String launchId, BuildStatus status);
    boolean existsByCommandIdAndLaunchIdAndStatus(String commandId, String launchId, BuildStatus status);
    List<BuildRecord> findByCommandIdAndLaunchIdAndStatus(String commandId, String launchId, BuildStatus status);
    List<BuildRecord> findByStatus(BuildStatus status);
    void deleteByLaunchId(String launchId);
    void deleteByProjectId(String projectId);
}
