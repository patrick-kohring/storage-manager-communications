package com.storagemanager.communications.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.UserIdentity;

@Component
public class IngestAuthorizer {

    private final String ingestScope; // "events/ingest" (Ingest_Scope)

    public IngestAuthorizer(@Value("${app.security.jwt.ingest-scope:events/ingest}") String ingestScope) {
        this.ingestScope = ingestScope;
    }

    /**
     * Trusted_Ingest_Caller (authority SCOPE_<ingestScope>): use envelope.organizationId
     * unchanged, with NO comparison to any organizationId claim (Req 4.3).
     * Otherwise End_User_Token: resolve the caller org from the token (Req 4.4) and require
     * envelope.organizationId == caller org (Req 4.5), else 403.
     * An authenticated token with no resolvable org is rejected (Req 4.4/4.5 -> 403).
     */
    public UUID resolveOrganizationScope(EventEnvelope envelope, Authentication authentication) {
        boolean trusted = authentication.getAuthorities().stream()
                .anyMatch(a -> ("SCOPE_" + ingestScope).equals(a.getAuthority()));
        if (trusted) {
            return envelope.organizationId(); // any org, authoritative (Req 4.3, 9.1)
        }

        UUID callerOrg = callerOrganization(authentication);
        if (callerOrg == null) {
            throw new AccessDeniedException("No organization scope for end-user token");
        }
        if (!callerOrg.equals(envelope.organizationId())) {
            throw new AccessDeniedException("Envelope organizationId does not match caller org");
        }
        return callerOrg; // confined to caller org (Req 4.4, 4.5)
    }

    private UUID callerOrganization(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken jwtToken) {
            Object details = jwtToken.getDetails();
            if (details instanceof UserIdentity identity && identity.getOrganizationId() != null) {
                return identity.getOrganizationId();
            }
            String claim = jwtToken.getToken().getClaimAsString("organizationId");
            return claim == null ? null : UUID.fromString(claim);
        }
        return null;
    }
}
