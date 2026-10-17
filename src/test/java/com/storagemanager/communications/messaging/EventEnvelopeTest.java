package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.storagemanager.communications.messaging.EventEnvelope.ActorType;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Unit tests for the jakarta.validation annotations on {@link EventEnvelope} (Req 3.4).
 * <p>
 * Verifies that the eight required fields carry {@code @NotNull} and that the four nullable
 * fields ({@code facilityId}, {@code actorId}, {@code correlationId}, {@code causationId})
 * carry no constraint, so a missing required field surfaces as a validation violation on
 * exactly that property while a null nullable field produces none.
 */
class EventEnvelopeTest {

    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void tearDownValidator() {
        if (validatorFactory != null) {
            validatorFactory.close();
        }
    }

    /** Builds a fully-populated, valid envelope with every field (required and nullable) set. */
    private static EventEnvelope validEnvelope() {
        return new EventEnvelope(
                UUID.randomUUID(),          // eventId
                "SomeEvent",                // eventType
                1,                          // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"), // occurredAt
                UUID.randomUUID(),          // organizationId
                UUID.randomUUID(),          // facilityId (nullable, populated here)
                "Lead",                     // entityType
                UUID.randomUUID(),          // entityId
                ActorType.SYSTEM,           // actorType
                UUID.randomUUID(),          // actorId (nullable, populated here)
                UUID.randomUUID(),          // correlationId (nullable, populated here)
                UUID.randomUUID(),          // causationId (nullable, populated here)
                Map.of("k", "v"));          // payload
    }

    @Test
    void fullyPopulatedEnvelopeHasNoViolations() {
        Set<ConstraintViolation<EventEnvelope>> violations = validator.validate(validEnvelope());

        assertThat(violations).isEmpty();
    }

    @Test
    void missingEventIdProducesNotNullViolationOnEventId() {
        EventEnvelope envelope = new EventEnvelope(
                null, "SomeEvent", 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "Lead", UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "eventId");
    }

    @Test
    void missingEventTypeProducesNotNullViolationOnEventType() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), null, 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "Lead", UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "eventType");
    }

    @Test
    void missingOccurredAtProducesNotNullViolationOnOccurredAt() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, null, UUID.randomUUID(), UUID.randomUUID(),
                "Lead", UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "occurredAt");
    }

    @Test
    void missingOrganizationIdProducesNotNullViolationOnOrganizationId() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), null, UUID.randomUUID(),
                "Lead", UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "organizationId");
    }

    @Test
    void missingEntityTypeProducesNotNullViolationOnEntityType() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                null, UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "entityType");
    }

    @Test
    void missingEntityIdProducesNotNullViolationOnEntityId() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "Lead", null, ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "entityId");
    }

    @Test
    void missingActorTypeProducesNotNullViolationOnActorType() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "Lead", UUID.randomUUID(), null, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), Map.of("k", "v"));

        assertSingleNotNullViolationOn(envelope, "actorType");
    }

    @Test
    void missingPayloadProducesNotNullViolationOnPayload() {
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                "Lead", UUID.randomUUID(), ActorType.SYSTEM, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null);

        assertSingleNotNullViolationOn(envelope, "payload");
    }

    @Test
    void nullNullableFieldsProduceNoViolations() {
        // Otherwise-valid envelope with every nullable field (facilityId, actorId,
        // correlationId, causationId) set to null — must validate cleanly.
        EventEnvelope envelope = new EventEnvelope(
                UUID.randomUUID(), "SomeEvent", 1, Instant.now(), UUID.randomUUID(),
                null,               // facilityId
                "Lead", UUID.randomUUID(), ActorType.SYSTEM,
                null,               // actorId
                null,               // correlationId
                null,               // causationId
                Map.of("k", "v"));

        Set<ConstraintViolation<EventEnvelope>> violations = validator.validate(envelope);

        assertThat(violations).isEmpty();
    }

    private static void assertSingleNotNullViolationOn(EventEnvelope envelope, String property) {
        Set<ConstraintViolation<EventEnvelope>> violations = validator.validate(envelope);

        assertThat(violations)
                .as("exactly one violation on property '%s'", property)
                .hasSize(1);
        ConstraintViolation<EventEnvelope> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
        assertThat(violation.getConstraintDescriptor().getAnnotation())
                .isInstanceOf(jakarta.validation.constraints.NotNull.class);
    }
}
