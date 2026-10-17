package com.storagemanager.communications.migration;

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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the host Flyway migration {@code V2__messaging_inbox_outbox.sql}.
 *
 * <p>A plain JUnit 5 test (no Spring context) that applies the communications service's whole
 * Flyway history ({@code V1} then {@code V2}) against a fresh in-memory H2 database in PostgreSQL
 * compatibility mode and asserts the enriched {@code inbox}/{@code outbox} schema contract of
 * Req 2.2-2.9. Ported from the api's proven {@code V18MessagingMigrationSmokeTest} and renumbered
 * to the communications linear history.
 *
 * <p>The test datasource JDBC URL carries the {@code INIT} clause
 * {@code CREATE DOMAIN IF NOT EXISTS JSONB AS JSON} (Req 2.10), registering a {@code JSONB} domain
 * aliased to H2's native {@code JSON} type so the enriched {@code payload}/{@code headers}
 * {@code JSONB} columns and the {@code V2} DDL validate under H2 in {@code MODE=PostgreSQL}. This
 * JSONB-domain + Flyway-equivalent datasource is scoped to this smoke test only (a dedicated local
 * {@link DriverManager} connection), so the shared {@code application.properties}
 * (which disables Flyway and runs {@code ddl-auto=create-drop} for Spring-context slices) is left
 * completely untouched — no other communications test is affected.
 *
 * <p>Two Postgres-only niceties the shipped DDL retains for the real Postgres migration are
 * neutralized before applying on H2, exactly as the api reference test does: the
 * {@code DEFAULT gen_random_uuid()} clause (UUIDs are app-assigned in tests) and the partial-index
 * {@code WHERE ...} predicate on {@code CREATE INDEX} (H2 does not support partial indexes; the
 * index on the same column still materializes and validates).
 *
 * <p>Keeping {@code V1} applying first mirrors the real Flyway run (the whole history is replayed)
 * and proves {@code V2} composes cleanly on top of the existing {@code communication_templates}
 * schema.
 */
class V2MessagingMigrationSmokeTest {

    private static final String V1_MIGRATION = "db/migration/V1__create_communication_templates.sql";
    private static final String V2_MIGRATION = "db/migration/V2__messaging_inbox_outbox.sql";

    /**
     * A fresh H2 datasource in PostgreSQL mode with the JSONB domain alias registered via the
     * INIT clause (Req 2.10). {@code DATABASE_TO_LOWER=TRUE} matches Postgres identifier folding
     * so the INFORMATION_SCHEMA probes are case-stable.
     */
    private static String freshUrl(String name) {
        return "jdbc:h2:mem:" + name + "_" + System.nanoTime()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
                + ";INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON";
    }

    // Req 2.2-2.9: the whole history (V1 then V2) applies unchanged on H2 PostgreSQL mode with the
    // JSONB domain present, creating both the inbox and outbox tables with the enriched physical
    // scope_0..scope_15 columns, JSONB payload/headers, mechanics columns, the uq_ unique
    // constraints and the idx_ indexes that ddl-auto=validate relies on in production.
    @Test
    void v2AppliesOnH2AndCreatesEnrichedInboxAndOutboxTables() throws Exception {
        List<String> history = new ArrayList<>();
        history.addAll(migrationStatements(V1_MIGRATION));
        history.addAll(migrationStatements(V2_MIGRATION));

        try (Connection conn = DriverManager.getConnection(freshUrl("comm_v2"), "sa", "")) {
            assertThatCode(() -> applyAll(conn, history))
                    .as("V1+V2 migrations must apply unchanged on H2 PostgreSQL mode with the JSONB domain")
                    .doesNotThrowAnyException();

            // Both tables exist (Req 2.2 inbox, Req 2.3 outbox).
            assertThat(tableExists(conn, "inbox")).as("inbox table should exist").isTrue();
            assertThat(tableExists(conn, "outbox")).as("outbox table should exist").isTrue();

            // Physical scope_0..scope_15 columns on BOTH tables (Req 2.4).
            for (String table : List.of("inbox", "outbox")) {
                for (int i = 0; i <= 15; i++) {
                    assertThat(columnExists(conn, table, "scope_" + i))
                            .as("%s.scope_%d should exist", table, i).isTrue();
                }
                // JSONB payload + headers materialized -> proves the JSONB domain is registered (Req 2.5, 2.10).
                assertThat(columnExists(conn, table, "payload"))
                        .as("%s.payload (JSONB) should exist", table).isTrue();
                assertThat(columnExists(conn, table, "headers"))
                        .as("%s.headers (JSONB) should exist", table).isTrue();
                assertThat(columnExists(conn, table, "correlation_id"))
                        .as("%s.correlation_id should exist", table).isTrue();
            }

            // Outbox mechanics columns (Req 2.8).
            for (String column : List.of("created_at", "published_at", "attempts", "next_attempt_at")) {
                assertThat(columnExists(conn, "outbox", column))
                        .as("outbox.%s should exist", column).isTrue();
            }
            // Inbox mechanics columns (Req 2.8).
            for (String column : List.of("received_at", "processed_at")) {
                assertThat(columnExists(conn, "inbox", column))
                        .as("inbox.%s should exist", column).isTrue();
            }

            // Unique constraints (Req 2.6 / 2.7).
            assertThat(constraintExists(conn, "uq_inbox_message_id")).as("uq_inbox_message_id").isTrue();
            assertThat(constraintExists(conn, "uq_outbox_event_id")).as("uq_outbox_event_id").isTrue();

            // idx_ indexes (Req 2.9). The partial-index predicate is neutralized for H2 but the
            // index on the same column still materializes.
            for (String index : List.of(
                    "idx_outbox_unpublished", "idx_outbox_scope_0", "idx_outbox_scope_1",
                    "idx_outbox_correlation_id", "idx_inbox_unprocessed", "idx_inbox_scope_0",
                    "idx_inbox_scope_1", "idx_inbox_correlation_id")) {
                assertThat(indexExists(conn, index)).as("index %s should exist", index).isTrue();
            }
        }
    }

    // Req 2.5 / 2.10: the JSONB-typed columns are actually usable (not just declared) and the
    // physical scope columns hold the organization/facility ids: a row with JSONB payload and
    // headers inserts and reads back, which is what the enriched OutboxWriter needs.
    @Test
    void jsonbPayloadAndHeadersAreWritableAndReadableUnderH2() throws Exception {
        try (Connection conn = DriverManager.getConnection(freshUrl("comm_v2_jsonb"), "sa", "")) {
            applyAll(conn, migrationStatements(V1_MIGRATION));
            applyAll(conn, migrationStatements(V2_MIGRATION));

            UUID eventId = UUID.randomUUID();
            UUID organizationId = UUID.randomUUID();
            UUID facilityId = UUID.randomUUID();
            UUID correlationId = UUID.randomUUID();

            exec(conn, "INSERT INTO outbox "
                    + "(id, event_id, event_type, payload, created_at, attempts, "
                    + "scope_0, scope_1, correlation_id, headers) VALUES ("
                    + "'" + UUID.randomUUID() + "', '" + eventId + "', 'GenericEvent', "
                    + "'{\"entityId\":\"" + facilityId + "\",\"source\":\"TEST\"}', CURRENT_TIMESTAMP, 0, "
                    + "'" + organizationId + "', '" + facilityId + "', '" + correlationId + "', "
                    + "'{\"schema_version\":\"1\",\"entity_type\":\"Generic\"}')");

            assertThat(scalar(conn, "SELECT count(*) FROM outbox WHERE event_id = '" + eventId + "'"))
                    .as("JSONB-bearing outbox row persisted").isEqualTo(1L);
            assertThat(scalar(conn, "SELECT scope_0 FROM outbox WHERE event_id = '" + eventId + "'").toString())
                    .as("scope_0 holds the organization id").isEqualTo(organizationId.toString());
            assertThat(scalar(conn, "SELECT scope_1 FROM outbox WHERE event_id = '" + eventId + "'").toString())
                    .as("scope_1 holds the facility id").isEqualTo(facilityId.toString());
        }
    }

    // Req 2.6 / 2.7: the uq_ unique constraints are enforced by behavior — a duplicate event_id in
    // outbox and a duplicate message_id in inbox are both rejected.
    @Test
    void uniqueConstraintsRejectDuplicateIdentityKeys() throws Exception {
        try (Connection conn = DriverManager.getConnection(freshUrl("comm_v2_uq"), "sa", "")) {
            applyAll(conn, migrationStatements(V1_MIGRATION));
            applyAll(conn, migrationStatements(V2_MIGRATION));

            UUID eventId = UUID.randomUUID();
            exec(conn, outboxInsert(eventId));
            assertThatThrownBy(() -> exec(conn, outboxInsert(eventId)))
                    .as("duplicate outbox event_id must violate uq_outbox_event_id")
                    .isInstanceOf(SQLException.class);

            UUID messageId = UUID.randomUUID();
            exec(conn, inboxInsert(messageId));
            assertThatThrownBy(() -> exec(conn, inboxInsert(messageId)))
                    .as("duplicate inbox message_id must violate uq_inbox_message_id")
                    .isInstanceOf(SQLException.class);
        }
    }

    private static String outboxInsert(UUID eventId) {
        return "INSERT INTO outbox (id, event_id, event_type, payload, created_at, attempts) VALUES ("
                + "'" + UUID.randomUUID() + "', '" + eventId + "', 'GenericEvent', "
                + "'{\"k\":\"v\"}', CURRENT_TIMESTAMP, 0)";
    }

    private static String inboxInsert(UUID messageId) {
        return "INSERT INTO inbox (id, message_id, payload, received_at) VALUES ("
                + "'" + UUID.randomUUID() + "', '" + messageId + "', '{\"k\":\"v\"}', CURRENT_TIMESTAMP)";
    }

    // --- DDL loading / splitting -------------------------------------------------------------

    /** Migration statements for a resource, with the Postgres-only niceties neutralized for H2. */
    private static List<String> migrationStatements(String resource) throws Exception {
        return splitStatements(readResource(resource));
    }

    private static String readResource(String resource) throws Exception {
        try (InputStream in = V2MessagingMigrationSmokeTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("migration must be readable from the classpath: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Strip {@code --} line comments, split on {@code ;}, drop {@code DEFAULT gen_random_uuid()}
     * and the Postgres-only partial-index {@code WHERE ...} predicate so the DDL applies on H2.
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

    private static void exec(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
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

    private static Object scalar(Connection conn, String sql) throws SQLException {
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }
}
