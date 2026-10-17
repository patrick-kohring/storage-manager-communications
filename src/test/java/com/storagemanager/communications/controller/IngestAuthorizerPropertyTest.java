package com.storagemanager.communications.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.communications.model.UserIdentity;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property tests for {@link IngestAuthorizer} (messaging spec, Task 9.3).
 *
 * <p><strong>Feature: messaging, Property 8: Service-to-service organization resolution.</strong>
 * For any envelope {@code organizationId}, a {@code Trusted_Ingest_Caller} (JWT carrying
 * {@code SCOPE_events/ingest}) has its resolved Organization_Scope equal to the envelope
 * {@code organizationId} with no comparison to any claim; and for any
 * {@code (envelopeOrg, tokenOrg)} pair, an {@code End_User_Token} is accepted with
 * Organization_Scope = {@code tokenOrg} iff {@code envelopeOrg == tokenOrg}, otherwise the request
 * is rejected with {@link AccessDeniedException} (403) and no inbox row is written.
 *
 * <p>Validates: Requirements 4.3, 4.4, 4.5, 9.1
 */
class IngestAuthorizerPropertyTest {

    private static final String INGEST_SCOPE = "events/ingest";

    private final IngestAuthorizer authorizer = new IngestAuthorizer(INGEST_SCOPE);

    // ------------------------------------------------------------------------
    // Property 8a: Trusted_Ingest_Caller resolves to the envelope org, no comparison (Req 4.3, 9.1)
    // ------------------------------------------------------------------------

    /**
     * For an arbitrary envelope {@code organizationId}, a trusted caller resolves to that value
     * with no claim comparison — even when the token itself carries a <em>different</em>
     * {@code organizationId} claim and {@code UserIdentity} org, proving no comparison occurs.
     *
     * <p>Validates: Requirements 4.3, 9.1
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void trustedCaller_resolvesEnvelopeOrg_withNoClaimComparison(@ForAll("uuids") UUID envelopeOrg) {
        // Deliberately diverge the token org from the envelope org to prove no comparison happens.
        UUID differentTokenOrg = otherThan(envelopeOrg);

        EventEnvelope envelope = envelopeWithOrganization(envelopeOrg);
        JwtAuthenticationToken trusted = trustedCaller(differentTokenOrg);

        UUID resolved = authorizer.resolveOrganizationScope(envelope, trusted);

        // Authoritative envelope org regardless of the differing token org (no comparison).
        assertThat(resolved).isEqualTo(envelopeOrg);
    }

    // ------------------------------------------------------------------------
    // Property 8b: End_User_Token accepted iff envelopeOrg == tokenOrg, else 403 (Req 4.4, 4.5)
    // ------------------------------------------------------------------------

    /**
     * For an arbitrary {@code (envelopeOrg, tokenOrg)} pair (mixing equal and unequal cases via a
     * generated {@code makeEqual} flag), an end-user token is accepted and resolves to
     * {@code tokenOrg} iff {@code envelopeOrg == tokenOrg}; otherwise the authorizer rejects with
     * {@link AccessDeniedException} (403).
     *
     * <p>Validates: Requirements 4.4, 4.5
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void endUser_acceptedIffEnvelopeOrgEqualsTokenOrg(
            @ForAll("uuids") UUID envelopeOrg,
            @ForAll("uuids") UUID candidateTokenOrg,
            @ForAll boolean makeEqual) {

        UUID tokenOrg = makeEqual ? envelopeOrg : otherThan(envelopeOrg, candidateTokenOrg);

        EventEnvelope envelope = envelopeWithOrganization(envelopeOrg);
        JwtAuthenticationToken endUser = endUserToken(tokenOrg);

        if (envelopeOrg.equals(tokenOrg)) {
            UUID resolved = authorizer.resolveOrganizationScope(envelope, endUser);
            assertThat(resolved).isEqualTo(tokenOrg);
        } else {
            assertThatThrownBy(() -> authorizer.resolveOrganizationScope(envelope, endUser))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    // ------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------

    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.create(UUID::randomUUID);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** A fresh UUID guaranteed to differ from {@code value}. */
    private static UUID otherThan(UUID value) {
        UUID candidate;
        do {
            candidate = UUID.randomUUID();
        } while (candidate.equals(value));
        return candidate;
    }

    /** A UUID differing from {@code value}, preferring {@code preferred} when it already differs. */
    private static UUID otherThan(UUID value, UUID preferred) {
        return preferred.equals(value) ? otherThan(value) : preferred;
    }

    private static EventEnvelope envelopeWithOrganization(UUID organizationId) {
        return new EventEnvelope(
                UUID.randomUUID(),      // eventId
                "GenericEvent",         // eventType
                1,                      // schemaVersion
                Instant.parse("2024-01-01T00:00:00Z"),
                organizationId,
                null,                   // facilityId
                "Lead",                 // entityType
                UUID.randomUUID(),      // entityId
                ActorType.SYSTEM,
                null,                   // actorId
                null,                   // correlationId
                null,                   // causationId
                Map.of());              // payload
    }

    /** Trusted caller: carries SCOPE_events/ingest plus an (ignored) differing org claim. */
    private static JwtAuthenticationToken trustedCaller(UUID tokenOrg) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("service-sub")
                .claim("organizationId", tokenOrg.toString())
                .claim("scope", INGEST_SCOPE)
                .build();
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("SCOPE_" + INGEST_SCOPE)));
        // Even the UserIdentity org diverges from the envelope — it must never be consulted.
        token.setDetails(new UserIdentity("service-sub", null, tokenOrg));
        return token;
    }

    /** End-user: no ingest-scope authority; org resolved from the attached UserIdentity. */
    private static JwtAuthenticationToken endUserToken(UUID tokenOrg) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("end-user-sub")
                .claim("organizationId", tokenOrg.toString())
                .build();
        // No SCOPE_events/ingest authority -> classified as End_User_Token.
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, List.of());
        token.setDetails(new UserIdentity("end-user-sub", null, tokenOrg));
        return token;
    }
}
