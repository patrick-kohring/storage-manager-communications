package com.storagemanager.communications.filter;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;

/**
 * Unit tests for {@link CustomJwtAuthenticationConverter}'s scope-without-role behavior
 * (design "Security extension: scope-without-role").
 *
 * <p>Covers the Trusted_Ingest_Caller (scope-only, no role) authenticating with a null-role
 * {@link UserIdentity} and the {@code SCOPE_events/ingest} authority, the preserved
 * {@code ROLE_<role>} mapping for interactive end users, both authorities granted when a token
 * carries a role and the ingest scope, and the rejection paths for an unrecognized role and for a
 * token carrying neither a role nor the ingest scope.
 *
 * Validates: Requirements 4.1, 4.3
 */
class CustomJwtAuthenticationConverterTest {

    private static final String ROLE_CLAIM = "custom:role";
    private static final String INGEST_SCOPE = "events/ingest";

    private final CustomJwtAuthenticationConverter converter =
            new CustomJwtAuthenticationConverter(ROLE_CLAIM, INGEST_SCOPE);

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject("sub-123");
    }

    @Test
    void scopeOnlyIngestTokenAuthenticatesWithNullRole() {
        Jwt jwt = baseJwt()
                .claim("scope", "events/ingest")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .contains(new SimpleGrantedAuthority("SCOPE_events/ingest"));

        UserIdentity identity = (UserIdentity) token.getDetails();
        assertThat(identity).isNotNull();
        assertThat(identity.getRole()).isNull();
    }

    @Test
    void roleOnlyTokenGrantsRoleAuthority() {
        Jwt jwt = baseJwt()
                .claim(ROLE_CLAIM, UserRole.ADMIN.name())
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .contains(new SimpleGrantedAuthority("ROLE_" + UserRole.ADMIN.name()));

        UserIdentity identity = (UserIdentity) token.getDetails();
        assertThat(identity).isNotNull();
        assertThat(identity.getRole()).isEqualTo(UserRole.ADMIN);
    }

    @Test
    void tokenWithBothRoleAndIngestScopeGrantsBothAuthorities() {
        Jwt jwt = baseJwt()
                .claim(ROLE_CLAIM, UserRole.OWNER.name())
                .claim("scope", "events/ingest")
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .contains(
                        new SimpleGrantedAuthority("SCOPE_events/ingest"),
                        new SimpleGrantedAuthority("ROLE_" + UserRole.OWNER.name()));
    }

    @Test
    void unrecognizedRoleClaimIsRejected() {
        Jwt jwt = baseJwt()
                .claim(ROLE_CLAIM, "NOT_A_ROLE")
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void tokenWithNoRoleAndNoIngestScopeIsRejected() {
        Jwt jwt = baseJwt()
                .claim("scope", "")
                .build();

        assertThatThrownBy(() -> converter.convert(jwt))
                .isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    void organizationIdClaimIsParsedIntoUserIdentity() {
        UUID organizationId = UUID.randomUUID();
        Jwt jwt = baseJwt()
                .claim("scope", "events/ingest")
                .claim("organizationId", organizationId.toString())
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        UserIdentity identity = (UserIdentity) token.getDetails();
        assertThat(identity).isNotNull();
        assertThat(identity.getOrganizationId()).isEqualTo(organizationId);
    }

    @Test
    void scpArrayClaimIsHonoredForIngestScope() {
        Jwt jwt = baseJwt()
                .claim("scp", List.of("events/ingest"))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getAuthorities())
                .contains(new SimpleGrantedAuthority("SCOPE_events/ingest"));
        UserIdentity identity = (UserIdentity) token.getDetails();
        assertThat(identity.getRole()).isNull();
    }
}
