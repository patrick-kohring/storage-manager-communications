package com.storagemanager.communications.communication;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the host Flyway migration {@code V1__create_communication_templates.sql}.
 *
 * <p>A plain JUnit 5 test (no Spring context) that applies the communications service's V1
 * migration against a fresh in-memory H2 database in PostgreSQL compatibility mode and asserts
 * the {@code communication_templates} schema contract. Ported from the api's V19 smoke test and
 * renumbered to V1 (the communications service has its own linear Flyway history).
 *
 * <p>The Postgres-only partial-index predicate ({@code WHERE facility_id IS NULL}) on the
 * org-level unique index is neutralized before applying on H2: H2 does not support partial
 * indexes, but the unique index on the same columns still materializes and validates.
 */
class V1CommunicationMigrationSmokeTest {

    private static final String V1_MIGRATION = "db/migration/V1__create_communication_templates.sql";

    private static String freshUrl(String name) {
        return "jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
    }

    @Test
    void v1AppliesOnH2AndCreatesCommunicationTemplatesTable() throws Exception {
        List<String> statements = migrationStatements();

        try (Connection conn = DriverManager.getConnection(freshUrl("comm_v1"), "sa", "")) {
            assertThatCode(() -> applyAll(conn, statements))
                    .as("V1 migration must apply unchanged on H2 PostgreSQL mode")
                    .doesNotThrowAnyException();

            assertThat(tableExists(conn, "communication_templates"))
                    .as("communication_templates table should exist").isTrue();

            for (String column : List.of(
                    "id", "organization_id", "facility_id", "template_name",
                    "email_content", "sms_content", "created_at", "updated_at")) {
                assertThat(columnExists(conn, "communication_templates", column))
                        .as("communication_templates.%s should exist", column).isTrue();
            }

            assertThat(constraintExists(conn, "uq_comm_tmpl_org_facility_name"))
                    .as("composite unique constraint uq_comm_tmpl_org_facility_name should exist").isTrue();

            assertThat(indexExists(conn, "uq_comm_tmpl_org_name_facility_null"))
                    .as("org-level unique index uq_comm_tmpl_org_name_facility_null should exist").isTrue();
        }
    }

    private static List<String> migrationStatements() throws Exception {
        return splitStatements(readResource(V1_MIGRATION));
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream in = V1CommunicationMigrationSmokeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("V1 migration must be readable from the classpath: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<String> splitStatements(String script) {
        StringBuilder sql = new StringBuilder();
        for (String line : script.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            sql.append(line).append('\n');
        }
        List<String> statements = new ArrayList<>();
        for (String part : sql.toString().split(";")) {
            String stmt = part.strip();
            if (stmt.isEmpty()) {
                continue;
            }
            String upper = stmt.toUpperCase(Locale.ROOT);
            if (upper.startsWith("CREATE INDEX") || upper.startsWith("CREATE UNIQUE INDEX")) {
                int where = upper.indexOf(" WHERE ");
                if (where >= 0) {
                    stmt = stmt.substring(0, where).strip();
                }
            }
            statements.add(stmt);
        }
        return statements;
    }

    private static void applyAll(Connection conn, List<String> statements) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            for (String statement : statements) {
                stmt.execute(statement);
            }
        }
    }

    private static boolean tableExists(Connection conn, String table) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = ?")) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.columns "
                        + "WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            ps.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean constraintExists(Connection conn, String name) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.table_constraints "
                        + "WHERE lower(constraint_name) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private static boolean indexExists(Connection conn, String name) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.indexes WHERE lower(index_name) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
