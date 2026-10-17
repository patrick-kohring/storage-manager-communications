package com.storagemanager.communications.messaging;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/**
 * Communications-side copy of the events-canonical 13-field EventEnvelope wire contract
 * (External Assumption A3; owned by events + the starter, reproduced here for ingest).
 * Required fields carry @NotNull so a missing field surfaces as 400 via GlobalExceptionHandler
 * (Req 3.4). actorId / correlationId / causationId are nullable by contract.
 */
public record EventEnvelope(
        @NotNull UUID eventId,
        @NotNull String eventType,
        int schemaVersion,
        @NotNull Instant occurredAt,
        @NotNull UUID organizationId,
        UUID facilityId,
        @NotNull String entityType,
        @NotNull UUID entityId,
        @NotNull ActorType actorType,   // SYSTEM | USER
        UUID actorId,
        UUID correlationId,
        UUID causationId,
        @NotNull Map<String, Object> payload) {

    public enum ActorType { SYSTEM, USER }
}
