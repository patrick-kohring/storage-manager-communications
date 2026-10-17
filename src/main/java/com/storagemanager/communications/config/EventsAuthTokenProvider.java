package com.storagemanager.communications.config;

import org.springframework.stereotype.Component;

/**
 * Service-to-service auth seam for the communications -> events outbox transport.
 *
 * <p>This is the Phase-0 token seam described in the design ("Security seam" / "Host transport
 * wiring and config"). In local Phase 0 ({@code storagemanager.messaging.events.auth.enabled=false})
 * the {@code MessagingTransportConfig} transport attaches no bearer token, so this provider is never
 * consulted and the no-op {@link Disabled} default is sufficient. When s2s auth is enabled
 * (Req 7.5), the transport attaches an {@code events/ingest}-scoped service bearer token obtained
 * from {@link #currentToken()}; a real client-credentials implementation then replaces the default
 * bean. Token production is platform-owned (External Assumption A4) — communications owns only
 * attachment on outbound delivery.
 *
 * <p>Requirements: 7.5, 7.6.
 */
public interface EventsAuthTokenProvider {

    /**
     * Returns the current {@code events/ingest}-scoped service bearer token to attach to outbound
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
    class Disabled implements EventsAuthTokenProvider {

        @Override
        public String currentToken() {
            return null;
        }
    }
}
