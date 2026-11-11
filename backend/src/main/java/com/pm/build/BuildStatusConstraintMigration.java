package com.pm.build;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Databases created before {@code CANCELLED} / {@code TIMED_OUT} existed carry a Hibernate-generated
 * CHECK constraint on {@code build_records.status} that only allows the old enum values, so finishing a
 * build with a new status failed and the record stayed RUNNING ("Building…" forever). This drops that
 * constraint. Idempotent: does nothing when no such constraint exists.
 */
@Slf4j
@Component
@Order(90)
@RequiredArgsConstructor
public class BuildStatusConstraintMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        try {
            // The constraint name is auto-generated (CONSTRAINT_n), so look it up by what it checks.
            List<Map<String, Object>> found = jdbc.queryForList(
                    "SELECT tc.constraint_schema AS s, tc.constraint_name AS n " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.check_constraints cc " +
                    "  ON cc.constraint_schema = tc.constraint_schema AND cc.constraint_name = tc.constraint_name " +
                    "WHERE upper(tc.table_name) = 'BUILD_RECORDS' AND tc.constraint_type = 'CHECK' " +
                    "AND upper(cc.check_clause) LIKE '%STATUS%'");
            for (Map<String, Object> row : found) {
                String schema = String.valueOf(row.get("S"));
                String name = String.valueOf(row.get("N"));
                jdbc.execute("ALTER TABLE build_records DROP CONSTRAINT \"" + schema + "\".\"" + name + "\"");
                log.info("Dropped outdated check constraint {}.{} on build_records.status", schema, name);
            }
        } catch (RuntimeException e) {
            log.warn("build_records status constraint migration skipped: {}", e.getMessage());
        }
    }
}
