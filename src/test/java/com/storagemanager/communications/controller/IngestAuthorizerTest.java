package com.storagemanager.communications.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;

/**
 * Unit tests for {@link IngestAuthorizer}, mirroring the examples of Property 8
 * (Service-to-service organization resolution).
 *
 * <p>Validates: Requirements 4.3, 4.4, 4.5, 9.1</p>
 */
class IngestAuthorizerTest {

    private static final String INGEST_SCOPE = "events/ingest";

    private final IngestAuthorizer authorizer = new IngestAuthorizer(INGEST_SCOPE);

    // --- Req 4.3 / 9.1: Trusted_Ingest_Caller uses the envelope org unchanged ---

    @Test
    void trustedCaller_returnsEnvelopeOrganization_evenWhenClaimDiffers() {
        UUID envelopeOrg = UUID.randomUUID();
        UUID differentClaimOrg = UUID.randomUUID();

        EventEnvelope envelope = envelopeWithOrganization(envelopeOrg);

        // Trusted caller: carries SCOPE_events/ingest and an unrelated organizationId claim.
        Jwt jwt = jwtWithOrganizationClaim(differentClaimOrg);
        JwtAuthenticationToken token = new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("SCOPE_" + INGEST_SCOPE)));

        UUID resolved = authorizer.resolveOrganizationScope(envelope, token);

        // Authoritative envelope org, no comparison to the differing claim.
        assertThat(resolved).isEqualTo(envelopeOrg);
    }

    // --- Req 4.4: End_User_Token confined to caller org, accepted on match ---

    @Test
    void endUser_withMatchingOrganization_returnsThatOrganization() {
        UUID org = UUID.randomUUID();
        EventEnvelope envelope = envelopeWithOrganization(org);

        UserIdentity identity = new UserIdentity("end-user-sub", UserRole.STAFF, org);
        JwtAuthenticationToken token = endUserToken(jwtWithOrganizationClaim(org), identity);

        UUID resolved = authorizer.resolveOrganizationScope(envelope, token);

        assertThat(resolved).isEqualTo(org);
    }

    // --- Req 4.5: End_User_Token rejected when envelope org differs ---

    @Test
    void endUser_withMismatchedOrganization_throwsAccessDenied() {
        UUID envelopeOrg = UUID.randomUUID();
        UUID callerOrg = UUID.randomUUID();
        EventEnvelope envelope = envelopeWithOrganization(envelopeOrg);

        UserIdentity identity = new UserIdentity("end-user-sub", UserRole.STAFF, callerOrg);
        JwtAuthenticationToken token = endUserToken(jwtWithOrganizationClaim(callerOrg), identity);

        assertThatThrownBy(() -> authorizer.resolveOrganizationScope(envelope, token))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- Req 4.4/4.5: End_User_Token with no resolvable org is rejected ---

    @Test
    void endUser_withNoResolvableOrganization_throwsAccessDenied() {
        EventEnvelope envelope = envelopeWithOrganization(UUID.randomUUID());

        // UserIdentity org null AND no organizationId claim on the JWT.
        UserIdentity identity = new UserIdentity("end-user-sub", UserRole.STAFF, null);
        JwtAuthenticationToken token = endUserToken(jwtWithoutOrganizationClaim(), identity);

        assertThatThrownBy(() -> authorizer.resolveOrganizationScope(envelope, token))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- helpers ---

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

    private static Jwt jwtWithOrganizationClaim(UUID organizationId) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("s")
                .claim("organizationId", organizationId.toString())
                .build();
    }

    private static Jwt jwtWithoutOrganizationClaim() {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("s")
                .claim("scope", "openid")
                .build();
    }

    private static JwtAuthenticationToken endUserToken(Jwt jwt, UserIdentity identity) {
        // End-user cases carry a ROLE_* authority, never SCOPE_events/ingest.
        List<GrantedAuthority> authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + identity.getRole().name()));
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities);
        token.setDetails(identity);
        return token;
    }
}
