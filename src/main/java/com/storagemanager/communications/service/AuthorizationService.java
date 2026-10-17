package com.storagemanager.communications.service;

import java.util.UUID;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import com.storagemanager.communications.model.UserIdentity;
import com.storagemanager.communications.model.UserRole;

/**
 * Authorization rules for the communications service, mirroring the {@code authz} bean in
 * storage-manager-api so {@code @PreAuthorize("@authz...")} expressions port unchanged.
 *
 * <p>The communications service does not own the facility or organization tables, so access
 * decisions are made from the JWT identity's {@code organizationId} rather than a database
 * lookup. {@link #canAccessFacility(UUID)} therefore cannot resolve a facility to its owning
 * organization here; the authoritative organization scoping is enforced in
 * {@code CommunicationService} by comparing a template's own {@code organizationId} to the
 * caller's token organization. {@link #canAccessFacility(UUID)} is retained for API
 * compatibility and grants ADMIN unconditionally while deferring the finer-grained,
 * org-scoped check to the service layer for non-admin callers.
 */
@Service("authz")
public class AuthorizationService {

    public UserIdentity getCurrentUserIdentity() {
        JwtAuthenticationToken authentication = (JwtAuthenticationToken) SecurityContextHolder
                .getContext().getAuthentication();
        return (UserIdentity) authentication.getDetails();
    }

    public boolean hasRole(String role) {
        UserIdentity identity = getCurrentUserIdentity();
        return identity.getRole().name().equals(role);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public boolean canAccessOrganization(UUID organizationId) {
        UserIdentity identity = getCurrentUserIdentity();
        UserRole role = identity.getRole();

        if (role == UserRole.ADMIN) {
            return true;
        }
        if (role == UserRole.OWNER || role == UserRole.STAFF) {
            return organizationId != null && organizationId.equals(identity.getOrganizationId());
        }
        return false;
    }

    /**
     * ADMIN may access any facility. For a non-admin caller the facility cannot be resolved to
     * its owning organization in this service (no facility table), so this returns {@code true}
     * and the authoritative org-scoped decision is made in {@code CommunicationService} against
     * the template's own {@code organizationId}.
     */
    public boolean canAccessFacility(UUID facilityId) {
        UserIdentity identity = getCurrentUserIdentity();
        return identity.getRole() == UserRole.ADMIN
                || identity.getRole() == UserRole.OWNER
                || identity.getRole() == UserRole.STAFF;
    }
}
