package com.storagemanager.communications.config;

import org.springframework.stereotype.Component;

/**
 * Service-to-service auth seam for the communications -> storage-manager-api read transport.
 *
 * <p>This is the Phase-0 token seam described in the design ("Configuration — ApiAuthTokenProvider").
 * In the local Phase-0 posture ({@code storagemanager.api.auth.enabled=false}) the
 * {@code ApiReadTransportConfig} RestClient attaches no bearer token, so this provider is never
 * consulted and the no-op {@link Disabled} default is sufficient. When s2s auth is enabled
 * (Req 4.3), the transport attaches a {@code leads/read}-scoped service bearer token obtained from
 * {@link #currentToken()}; a real client-credentials implementation (mirroring the api-side
 * {@code ClientCredentialsEventsAuthTokenProvider}) then replaces the default bean. Token production
 * is platform-owned (External Assumption A1) — communications owns only attachment on outbound
 * lead reads.
 *
 * <p>Requirements: 4.3, 4.4.
 */
public interface ApiAuthTokenProvider {

    /**
     * Returns the current {@code leads/read}-scoped service bearer token to attach to outbound
     * requests, or {@code null} when no token is available.
     *
     * @return the bearer token value, or {@code null} when disabled / unavailable
     */
    String currentToken();

    /**
     * No-op default provider registered as a bean for the Phase-0 disabled posture
     * ({@code auth.enabled=false}). It returns {@code null} because the transport only consults the
     * provider when auth is enabled; keeping it a quiet no-op makes the seam injectable without
     * forcing a real token source to exist in local development.
     */
    @Component
    class Disabled implements ApiAuthTokenProvider {

        @Override
        public String currentToken() {
            return null;
        }
    }
}
