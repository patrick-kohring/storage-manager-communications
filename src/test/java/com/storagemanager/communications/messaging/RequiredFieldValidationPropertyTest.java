package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.messaging.EventEnvelope.ActorType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotNull;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterContainer;
import net.jqwik.api.lifecycle.BeforeContainer;

/**
 * Property-based test for required-field rejection on the generic ingest path.
 *
 * <p>Feature: messaging, Property 10: Missing required field is rejected without a row — for any
 * {@code EventEnvelope} with exactly one required field omitted ({@code eventId}, {@code eventType},
 * {@code occurredAt}, {@code organizationId}, {@code entityType}, {@code entityId},
 * {@code actorType}, or {@code payload}), the Ingest_Endpoint responds {@code 400} and writes no
 * {@code inbox} row.
 *
 * <p>Verification level — this property proves the {@code @NotNull} contract on
 * {@link EventEnvelope} at the Bean Validation layer, which is exactly what drives the controller's
 * {@code 400}: a {@code @Valid EventEnvelope} with any required field null fails bean validation
 * before {@code InboundRecordingService.record} is ever called, so no {@code inbox} row is written.
 * The end-to-end HTTP {@code 400} + no-inbox-row behavior is covered by the MockMvc test
 * {@code InboundEventControllerMockMvcTest} (task 11.3, {@code missingRequiredFieldReturns400}); this
 * jqwik property is the fast, deterministic proof of the universal invariant across all eight
 * required fields that underlies it.
 *
 * <p>Strategy: {@link #omittedRequiredFields()} yields one of the eight required field names;
 * {@link #validParts()} supplies arbitrary valid values for every field; the test constructs an
 * otherwise-valid envelope with exactly the chosen field nulled, validates it, and asserts there is
 * at least one violation whose property path is the omitted field and whose constraint is
 * {@code @NotNull}. The complementary property asserts a fully valid envelope has zero violations.
 *
 * **Validates: Requirements 3.4**
 */
class RequiredFieldValidationPropertyTest {

    /** The eight required-field names carrying {@code @NotNull} on {@link EventEnvelope}. */
    private static final String[] REQUIRED_FIELDS = {
            "eventId", "eventType", "occurredAt", "organizationId",
            "entityType", "entityId", "actorType", "payload"
    };

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeContainer
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterContainer
    static void tearDownValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    // ── Arbitraries ──

    @Provide
    Arbitrary<String> omittedRequiredFields() {
        return Arbitraries.of(REQUIRED_FIELDS);
    }

    /** Arbitrary, individually-valid values for each field, bundled so one of them can be nulled. */
    @Provide
    Arbitrary<EnvelopeParts> validParts() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<UUID> nullableUuids = Arbitraries.oneOf(
                Arbitraries.just((UUID) null),
                Arbitraries.create(UUID::randomUUID));
        Arbitrary<String> eventTypes = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(40);
        Arbitrary<String> entityTypes = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(30);
        Arbitrary<Integer> schemaVersions = Arbitraries.integers().between(0, 1000);
        Arbitrary<Instant> occurredAts = Arbitraries.longs()
                .between(0L, 4_102_444_800L)
                .map(Instant::ofEpochSecond);
        Arbitrary<ActorType> actorTypes = Arbitraries.of(ActorType.SYSTEM, ActorType.USER);
        Arbitrary<Map<String, Object>> payloads = Arbitraries.maps(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12),
                        Arbitraries.strings().alpha().numeric().ofMaxLength(20).map(s -> (Object) s))
                .ofMinSize(0).ofMaxSize(5);

        return net.jqwik.api.Combinators.combine(
                        uuids,            // eventId
                        eventTypes,       // eventType
                        schemaVersions,   // schemaVersion
                        occurredAts,      // occurredAt
                        uuids,            // organizationId
                        nullableUuids,    // facilityId
                        entityTypes,      // entityType
                        uuids)            // entityId
                .as(PartsHead::new)
                .flatMap(head -> net.jqwik.api.Combinators.combine(
                                actorTypes,       // actorType
                                nullableUuids,    // actorId
                                nullableUuids,    // correlationId
                                nullableUuids,    // causationId
                                payloads)         // payload
                        .as((actorType, actorId, correlationId, causationId, payload) ->
                                new EnvelopeParts(
                                        head.eventId, head.eventType, head.schemaVersion,
                                        head.occurredAt, head.organizationId, head.facilityId,
                                        head.entityType, head.entityId, actorType, actorId,
                                        correlationId, causationId, payload)));
    }

    // ═══════════════════════════════════════════════════════════════
    // Property 10: exactly one required field omitted -> a @NotNull violation on that field
    // ═══════════════════════════════════════════════════════════════

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void omittingExactlyOneRequiredFieldYieldsNotNullViolationOnThatField(
            @ForAll("omittedRequiredFields") String omitted,
            @ForAll("validParts") EnvelopeParts parts) {

        EventEnvelope envelope = parts.withOmitted(omitted);

        Set<ConstraintViolation<EventEnvelope>> violations = validator.validate(envelope);

        // A missing required field is a validation failure (the controller's @Valid turns this
        // into a 400 before any inbox row is written).
        assertThat(violations)
                .as("omitting required field '%s' must produce at least one violation", omitted)
                .isNotEmpty();

        // At least one violation names the omitted field and is a @NotNull violation.
        boolean hasNotNullViolationOnOmitted = violations.stream().anyMatch(v ->
                omitted.equals(v.getPropertyPath().toString())
                        && v.getConstraintDescriptor().getAnnotation() instanceof NotNull);
        assertThat(hasNotNullViolationOnOmitted)
                .as("expected a @NotNull violation on property '%s' (violations: %s)",
                        omitted, violations)
                .isTrue();
    }

    // ═══════════════════════════════════════════════════════════════
    // Complementary: a fully valid envelope (all required fields present) has zero violations
    // ═══════════════════════════════════════════════════════════════

    @Property(tries = 200, generation = GenerationMode.RANDOMIZED)
    void fullyPopulatedEnvelopeHasNoViolations(@ForAll("validParts") EnvelopeParts parts) {
        EventEnvelope envelope = parts.toEnvelope();

        Set<ConstraintViolation<EventEnvelope>> violations = validator.validate(envelope);

        assertThat(violations)
                .as("a fully valid envelope must have no violations")
                .isEmpty();
    }

    // ── Carriers ──

    /** Carrier for the first eight generated fields (jqwik Combinators cap at 8 arguments). */
    private record PartsHead(
            UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
            UUID organizationId, UUID facilityId, String entityType, UUID entityId) {
    }

    /**
     * All thirteen individually-valid envelope fields, with the ability to produce an envelope
     * that has exactly one required field nulled.
     */
    private record EnvelopeParts(
            UUID eventId, String eventType, int schemaVersion, Instant occurredAt,
            UUID organizationId, UUID facilityId, String entityType, UUID entityId,
            ActorType actorType, UUID actorId, UUID correlationId, UUID causationId,
            Map<String, Object> payload) {

        EventEnvelope toEnvelope() {
            return new EventEnvelope(
                    eventId, eventType, schemaVersion, occurredAt, organizationId, facilityId,
                    entityType, entityId, actorType, actorId, correlationId, causationId, payload);
        }

        EventEnvelope withOmitted(String field) {
            return new EventEnvelope(
                    "eventId".equals(field) ? null : eventId,
                    "eventType".equals(field) ? null : eventType,
                    schemaVersion,
                    "occurredAt".equals(field) ? null : occurredAt,
                    "organizationId".equals(field) ? null : organizationId,
                    facilityId,
                    "entityType".equals(field) ? null : entityType,
                    "entityId".equals(field) ? null : entityId,
                    "actorType".equals(field) ? null : actorType,
                    actorId,
                    correlationId,
                    causationId,
                    "payload".equals(field) ? null : payload);
        }
    }
}
