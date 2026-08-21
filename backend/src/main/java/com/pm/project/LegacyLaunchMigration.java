package com.pm.project;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * One-time, idempotent migration from the old single-command Project model to the
 * two-tier Project -> Launch model. For every legacy project that still carries a
 * {@code start_command} and has no launches yet, a default launch (id = project id,
 * so existing runtime records and log files keep matching) is created and the
 * legacy columns are cleared so the migration never runs twice.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LegacyLaunchMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        // Allow inserting projects without the legacy (now unused) start_command.
        safeExec("ALTER TABLE projects ALTER COLUMN start_command SET NULL");

        if (!legacyStartCommandColumnExists()) {
            return; // Fresh install — nothing to migrate.
        }

        try {
            int created = jdbc.update(
                    "INSERT INTO launches (id, project_id, name, start_command, stop_command, sort_order, created_at, updated_at) " +
                    "SELECT p.id, p.id, '默认', p.start_command, p.stop_command, 0, p.created_at, p.updated_at " +
                    "FROM projects p " +
                    "WHERE p.start_command IS NOT NULL " +
                    "AND NOT EXISTS (SELECT 1 FROM launches l WHERE l.project_id = p.id)");
            if (created > 0) {
                log.info("Migrated {} legacy project(s) to default launches", created);
            }

            // Move declared ports onto the default launch.
            safeExec(
                    "INSERT INTO launch_ports (launch_id, port) " +
                    "SELECT pp.project_id, pp.port FROM project_ports pp " +
                    "WHERE EXISTS (SELECT 1 FROM launches l WHERE l.id = pp.project_id) " +
                    "AND NOT EXISTS (SELECT 1 FROM launch_ports lp WHERE lp.launch_id = pp.project_id AND lp.port = pp.port)");

            // Decommission legacy columns so the migration is a no-op on later boots.
            jdbc.update(
                    "UPDATE projects SET start_command = NULL " +
                    "WHERE start_command IS NOT NULL " +
                    "AND EXISTS (SELECT 1 FROM launches l WHERE l.project_id = projects.id)");
        } catch (RuntimeException e) {
            log.warn("Legacy launch migration skipped: {}", e.getMessage());
        }
    }

    private boolean legacyStartCommandColumnExists() {
        try {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE upper(table_name) = 'PROJECTS' AND upper(column_name) = 'START_COMMAND'",
                    Integer.class);
            return count != null && count > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void safeExec(String sql) {
        try {
            jdbc.execute(sql);
        } catch (RuntimeException e) {
            log.debug("migration statement skipped ({}): {}", e.getMessage(), sql);
        }
    }
}
