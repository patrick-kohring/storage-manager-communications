package com.storagemanager.communications.entity;

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
 * Smoke test for the host Flyway migration {@code V3__create_entity_property_mappings.sql}.
 *
 * <p>A plain JUnit 5 test (no Spring context) that applies the communications service's whole
 * Flyway history ({@code V1} then {@code V2} then {@code V3}) against a fresh in-memory H2 database
 * in PostgreSQL compatibility mode and asserts the {@code entity_property_mappings} schema contract
 * of Requirements 1.1 and 3.1-3.4 / 12.4.
 *
 * <p>Unlike {@code V1} (partial unique index for NULL-distinctness) and {@code V2} (H2 {@code JSONB}
 * domain shim), the {@code entity_property_mappings} table uses only {@code UUID} / {@code VARCHAR}
 * / {@code TIMESTAMP} with two NOT NULL unique-constrained columns, so the {@code V3} DDL runs
 * identically under PostgreSQL (prod) and H2 PostgreSQL mode (tests) with <strong>no</strong>
 * {@code JSONB} domain or partial-index neutralization. The {@code V2} statement splitter is reused
 * so the preceding history (which does carry those Postgres-only niceties) still applies cleanly on
 * top of H2 before {@code V3} is layered on.
 *
 * <p>Keeping {@code V1} and {@code V2} applying first mirrors the real Flyway run (the whole history
 * is replayed) and proves {@code V3} composes cleanly on top of the existing schema. The datasource
 * carries the {@code JSONB} domain INIT clause only because {@code V2} needs it; {@code V3} itself
 * requires nothing extra.
 */
class V3EntityMappingMigrationSmokeTest {

    private static final String V1_MIGRATION = "db/migration/V1__create_communication_templates.sql";
    private static final String V2_MIGRATION = "db/migration/V2__messaging_inbox_outbox.sql";
    private static final String V3_MIGRATION = "db/migration/V3__create_entity_property_mappings.sql";

    /**
     * A fresh H2 datasource in PostgreSQL mode. {@code DATABASE_TO_LOWER=TRUE} matches Postgres
     * identifier folding so the INFORMATION_SCHEMA probes are case-stable. The JSONB domain INIT
     * clause is present solely so the preceding {@code V2} migration applies; {@code V3} needs no
     * shim of its own.
     */
    private static String freshUrl(String name) {
        return "jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON";
    }

    // Req 1.1, 3.1-3.4, 12.4: the whole history (V1 then V2 then V3) applies unchanged on H2
    // PostgreSQL mode, creating the entity_property_mappings table with the expected columns, the
    // uq_ composite unique constraint on (entity_type, placeholder), and the idx_ newest-first
    // index — while NOT constraining json_property.
    @Test
    void v3AppliesOnH2AndCreatesEntityPropertyMappingsTable() throws Exception {
        List<String> history = new ArrayList<>();
        history.addAll(migrationStatements(V1_MIGRATION));
        history.addAll(migrationStatements(V2_MIGRATION));
        history.addAll(migrationStatements(V3_MIGRATION));

        try (Connection conn = DriverManager.getConnection(freshUrl("comm_v3"), "sa", "")) {
            assertThatCode(() -> applyAll(conn, history))
                    .as("V1+V2+V3 migrations must apply unchanged on H2 PostgreSQL mode")
                    .doesNotThrowAnyException();

            // Req 1.1 / 3.2: the table exists.
            assertThat(tableExists(conn, "entity_property_mappings"))
                    .as("entity_property_mappings table should exist").isTrue();

            // Req 3.2: all expected columns exist.
            for (String column : List.of(
                    "id", "entity_type", "json_property", "placeholder", "created_at", "updated_at")) {
                assertThat(columnExists(conn, "entity_property_mappings", column))
                        .as("entity_property_mappings.%s should exist", column).isTrue();
            }

            // Req 3.2: the NOT NULL columns are declared NOT NULL.
            for (String column : List.of(
                    "entity_type", "json_property", "placeholder", "created_at", "updated_at")) {
                assertThat(columnIsNotNull(conn, "entity_property_mappings", column))
                        .as("entity_property_mappings.%s should be NOT NULL", column).isTrue();
            }

            // Req 3.3 / 12.4: the uq_ composite unique constraint on (entity_type, placeholder).
            assertThat(constraintExists(conn, "uq_entity_prop_mapping_type_placeholder"))
                    .as("unique constraint uq_entity_prop_mapping_type_placeholder should exist").isTrue();
            assertThat(uniqueConstraintColumns(conn, "uq_entity_prop_mapping_type_placeholder"))
                    .as("uq_entity_prop_mapping_type_placeholder should cover exactly (entity_type, placeholder)")
                    .containsExactlyInAnyOrder("entity_type", "placeholder");

            // Req 3.4: the idx_ newest-first list index.
            assertThat(indexExists(conn, "idx_entity_prop_mapping_type_created_at"))
                    .as("index idx_entity_prop_mapping_type_created_at should exist").isTrue();

            // Req 1.9 / 12.2: there is NO unique constraint on json_property.
            assertThat(hasUniqueConstraintOnColumn(conn, "entity_property_mappings", "json_property"))
                    .as("json_property must NOT carry any unique constraint").isFalse();
        }
    }

    // --- DDL loading / splitting -------------------------------------------------------------

    /** Migration statements for a resource, with the Postgres-only niceties neutralized for H2. */
    private static List<String> migrationStatements(String resource) throws Exception {
        return splitStatements(readResource(resource));
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream in = V3EntityMappingMigrationSmokeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("migration must be readable from the classpath: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Strip {@code --} line comments, split on {@code ;}, drop {@code DEFAULT gen_random_uuid()}
     * and the Postgres-only partial-index {@code WHERE ...} predicate so the preceding history
     * applies on H2. The {@code V3} DDL carries none of these niceties, so it passes through
     * unchanged.
     */
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
            String stmt = part.strip().replace(" DEFAULT gen_random_uuid()", "");
            if (stmt.isEmpty()) {
                continue;
            }
            if (stmt.regionMatches(true, 0, "CREATE INDEX", 0, "CREATE INDEX".length())
                    || stmt.regionMatches(true, 0, "CREATE UNIQUE INDEX", 0, "CREATE UNIQUE INDEX".length())) {
                int where = stmt.toUpperCase(Locale.ROOT).indexOf(" WHERE ");
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

    // --- INFORMATION_SCHEMA probes -----------------------------------------------------------

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

    private static boolean columnIsNotNull(Connection conn, String table, String column) throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            ps.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).as("column %s.%s should exist for nullability probe", table, column).isTrue();
                return "NO".equalsIgnoreCase(rs.getString(1));
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

    /** The column names covered by a named unique/primary constraint, lower-cased. */
    private static List<String> uniqueConstraintColumns(Connection conn, String constraintName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (var ps = conn.prepareStatement(
                "SELECT column_name FROM information_schema.key_column_usage "
                        + "WHERE lower(constraint_name) = ?")) {
            ps.setString(1, constraintName.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        return columns;
    }

    /** True if any UNIQUE constraint on the table covers the given column. */
    private static boolean hasUniqueConstraintOnColumn(Connection conn, String table, String column)
            throws SQLException {
        try (var ps = conn.prepareStatement(
                "SELECT count(*) FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON lower(tc.constraint_name) = lower(kcu.constraint_name) "
                        + "WHERE tc.constraint_type = 'UNIQUE' "
                        + "  AND lower(kcu.table_name) = ? "
                        + "  AND lower(kcu.column_name) = ?")) {
            ps.setString(1, table.toLowerCase(Locale.ROOT));
            ps.setString(2, column.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
}
