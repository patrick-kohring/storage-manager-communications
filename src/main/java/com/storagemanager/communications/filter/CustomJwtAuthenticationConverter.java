package com.storagemanager.communications.filter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;

/**
 * Derives the caller's identity directly from the validated JWT claims. Unlike
 * storage-manager-api, the communications service does not maintain a {@code user_identities}
 * table; it is a resource server that trusts the role and organization carried on the token.
 *
 * <p>The role is read from the configured role claim and the organization from the
 * {@code organizationId} claim. In addition, the OAuth2 {@code scope}/{@code scp} claim is read and
 * each scope value is granted as a Spring {@code SCOPE_<value>} authority so {@code SecurityConfig}
 * can authorize the ingest path for {@code SCOPE_events/ingest}.
 *
 * <p>Interactive end users carry a {@code custom:role} claim and authenticate as before: a present
 * and recognized role grants {@code ROLE_<role>} and attaches a {@code UserIdentity}; a present but
 * unrecognized role is rejected as an invalid token. A pure service token carries only the ingest
 * scope and <b>no</b> {@code custom:role}; it authenticates as a scope-only service identity whose
 * {@code UserIdentity} role is {@code null}. A token with neither a role nor the ingest scope is
 * rejected.
 */
@Component
public class CustomJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final String roleClaim;
    private final String ingestScope;

    public CustomJwtAuthenticationConverter(
            @Value("${app.security.jwt.role-claim}") String roleClaim,
            @Value("${app.security.jwt.ingest-scope:events/ingest}") String ingestScope) {
        this.roleClaim = roleClaim;
        this.ingestScope = ingestScope;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String sub = jwt.getSubject();
        String roleClaimValue = jwt.getClaimAsString(roleClaim);

        List<String> scopes = readScopes(jwt);
        boolean hasIngestScope = scopes.contains(ingestScope);

        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }

        UserRole role = null;
        if (roleClaimValue != null) {
            try {
                role = UserRole.valueOf(roleClaimValue);
            } catch (IllegalArgumentException ex) {
                throw new InvalidBearerTokenException("Unrecognized role claim: " + roleClaimValue, ex);
            }
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        } else if (!hasIngestScope) {
            // No role AND not a trusted ingest caller -> reject as before (interactive users need a role).
            throw new InvalidBearerTokenException("Missing role claim: " + roleClaim);
        }

        UUID organizationId = parseUuidClaim(jwt, "organizationId");

        UserIdentity identity = new UserIdentity(sub, role, organizationId);

        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, authorities, sub);
        token.setDetails(identity);
        return token;
    }

    /**
     * Reads the OAuth2 scope claim, supporting both the space-delimited {@code scope} string claim
     * and the {@code scp} array claim. Returns an empty list when neither claim is present.
     */
    private List<String> readScopes(Jwt jwt) {
        String scopeClaim = jwt.getClaimAsString("scope");
        if (scopeClaim != null && !scopeClaim.isBlank()) {
            List<String> scopes = new ArrayList<>();
            for (String value : scopeClaim.trim().split("\\s+")) {
                if (!value.isBlank()) {
                    scopes.add(value);
                }
            }
            return scopes;
        }

        List<String> scpClaim = jwt.getClaimAsStringList("scp");
        if (scpClaim != null) {
            List<String> scopes = new ArrayList<>();
            for (String value : scpClaim) {
                if (value != null && !value.isBlank()) {
                    scopes.add(value);
                }
            }
            return scopes;
        }

        return List.of();
    }

    private UUID parseUuidClaim(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new InvalidBearerTokenException("Invalid " + claim + " claim: " + value, ex);
        }
    }
}
