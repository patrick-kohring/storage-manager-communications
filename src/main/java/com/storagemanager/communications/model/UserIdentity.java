package com.storagemanager.communications.model;

import java.util.UUID;

/**
 * Claims-derived identity for the current request. Unlike storage-manager-api (which persists a
 * {@code user_identities} table), the communications service does not own user records; it is an
 * OAuth2 resource server that derives the caller's role and organization directly from the
 * validated JWT claims. This value object is attached as the authentication details by
 * {@code CustomJwtAuthenticationConverter} and read by {@code AuthorizationService}.
 */
public class UserIdentity {

    private final String sub;
    private final UserRole role;
    private final UUID organizationId;

    public UserIdentity(String sub, UserRole role, UUID organizationId) {
        this.sub = sub;
        this.role = role;
        this.organizationId = organizationId;
    }

    public String getSub() { return sub; }

    public UserRole getRole() { return role; }

    public UUID getOrganizationId() { return organizationId; }
}
