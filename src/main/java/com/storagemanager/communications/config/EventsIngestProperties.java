package com.storagemanager.communications.config;

import java.time.Duration;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Configuration properties for the communications -> events ingest transport.
 *
 * <p>Holds the {@code storagemanager.messaging.events.*} values: the events service base URL, the
 * connect/read timeouts applied to the {@code RestClient} used by {@code EventsIngestTransport},
 * and the Phase-0 auth seam toggle.
 *
 * <p>This class is a plain POJO on purpose. It is bound via
 * {@code @ConfigurationProperties(prefix = "storagemanager.messaging.events")} on the {@code @Bean}
 * method declared in {@code MessagingTransportConfig} (see the "Host transport wiring and config"
 * section of the design), rather than through component scanning here. Keeping the binding on the
 * bean method avoids registering this type twice.
 *
 * <p>Requirements: 7.6.
 */
public class EventsIngestProperties {

    /** Base URL of the events service (e.g. {@code http://localhost:8081}). */
    private String baseUrl;

    /** Connect timeout for requests to the events service. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Read timeout for requests to the events service. */
    private Duration readTimeout = Duration.ofSeconds(5);

    /** Service-to-service auth settings (Phase-0 seam, disabled by default). */
    private final Auth auth = new Auth();

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Auth getAuth() {
        return auth;
    }

    /**
     * Builds a {@link ClientHttpRequestFactory} with the configured connect/read timeouts,
     * suitable for {@code RestClient.builder().requestFactory(...)}. Keeping this here lets the
     * transport wiring stay a one-liner and ensures a hung events service never blocks the outbox
     * drain thread indefinitely.
     *
     * <p>Uses {@link SimpleClientHttpRequestFactory}, whose {@code Duration}-accepting timeout
     * setters are available in Spring Framework 6.1 / Spring Boot 3.3.x.
     */
    public ClientHttpRequestFactory clientRequestFactoryWithTimeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return factory;
    }

    /**
     * Service-identity auth settings. When {@link #enabled} is {@code false} (the Phase-0 default)
     * the transport attaches no bearer token (Req 7.6). When {@code true}, the transport attaches
     * an {@code events/ingest}-scoped service bearer token via the {@code EventsAuthTokenProvider}
     * seam (Req 7.5); token production is platform-owned (External Assumption A4).
     */
    public static class Auth {

        /** When {@code true}, the transport attaches a service-identity bearer token. */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
