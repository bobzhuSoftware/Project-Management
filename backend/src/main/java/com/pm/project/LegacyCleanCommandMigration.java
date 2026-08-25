package com.pm.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent migration from the single {@code projects.clean_command} column to a
 * row in {@code project_commands}. For every project that still carries a non-blank
 * {@code clean_command}, a "Clean" command (require_stopped = true) is created. The legacy
 * column is then cleared so the command is not resurrected on later boots and the old feature
 * is fully retired.
 */
@Slf4j
@Component
@Order(100)
@RequiredArgsConstructor
public class LegacyCleanCommandMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        if (!legacyCleanCommandColumnExists()) {
            return; // Fresh install — nothing to migrate.
        }
        try {
            int migrated = jdbc.update(
                    "INSERT INTO project_commands (id, project_id, name, command, require_stopped, timeout_seconds, sort_order) " +
                    "SELECT RANDOM_UUID(), p.id, 'Clean', p.clean_command, TRUE, NULL, 0 " +
                    "FROM projects p " +
                    "WHERE p.clean_command IS NOT NULL AND TRIM(p.clean_command) <> '' " +
                    "AND NOT EXISTS (SELECT 1 FROM project_commands c WHERE c.project_id = p.id AND c.name = 'Clean')");
            if (migrated > 0) {
                log.info("Migrated {} legacy clean command(s) into project_commands", migrated);
            }
            // Decommission the legacy column so the migration is a no-op on later boots.
            jdbc.update("UPDATE projects SET clean_command = NULL WHERE clean_command IS NOT NULL");
        } catch (RuntimeException e) {
            log.warn("Legacy clean command migration skipped: {}", e.getMessage());
        }
    }

    private boolean legacyCleanCommandColumnExists() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE upper(table_name) = 'PROJECTS' AND upper(column_name) = 'CLEAN_COMMAND'",
                    Integer.class);
            return count != null && count > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
