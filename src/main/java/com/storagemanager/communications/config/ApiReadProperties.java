package com.storagemanager.communications.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * Configuration properties for the communications -> storage-manager-api read transport.
 *
 * <p>Holds the {@code storagemanager.api.*} values: the api service base URL, the connect/read
 * timeouts applied to the {@code RestClient} used by {@code HttpLeadReadPort}, and the Phase-0
 * s2s auth seam toggle. This group mirrors the {@code storagemanager.messaging.events.*}
 * convention used by the comms -> events outbound transport, only the target differs (here
 * communications calls storage-manager-api).
 *
 * <p>Annotated {@link ConfigurationProperties}({@code "storagemanager.api"}) so it is bindable by
 * the {@code @EnableConfigurationProperties(ApiReadProperties.class)} declaration on
 * {@code ApiReadTransportConfig} (see the "Configuration (ApiReadTransportConfig)" section of the
 * design): {@code @EnableConfigurationProperties} registers the type as a bound bean and requires
 * the {@link ConfigurationProperties} annotation to be present on it. The binding stays enabled by
 * the configuration (not by component scanning here) so the type is registered exactly once, under
 * the same runtime-profile guard as the transport.
 *
 * <p>Requirements: 4.1, 4.2, 4.5.
 */
@ConfigurationProperties("storagemanager.api")
public class ApiReadProperties {

    /** Base URL of the storage-manager-api service (e.g. {@code http://localhost:8080}). */
    private String baseUrl;

    /** Connect timeout for requests to the api service. */
    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Read timeout for requests to the api service. */
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
     * transport wiring stay a one-liner and ensures a hung api service never blocks the
     * processing thread indefinitely (Req 4.2).
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
     * the transport attaches no bearer token (Req 4.3). When {@code true}, the transport attaches
     * a {@code leads/read}-scoped service bearer token via the {@code ApiAuthTokenProvider} seam
     * (Req 4.3); token production is platform-owned (External Assumption A1).
     */
    public static class Auth {

        /** When {@code true}, the transport attaches a service-identity bearer token. */
        private boolean enabled = false;

        /** OAuth2 client-credentials token endpoint (Cognito {@code /oauth2/token} or local issuer). */
        private String tokenUri;

        /** Client-credentials client identifier. */
        private String clientId;

        /** Client-credentials client secret (supplied via environment/secret storage). */
        private String clientSecret;

        /** The single scope requested for the service token. The api accepts {@code leads/read}. */
        private String scope = "leads/read";

        /** Refresh the token this far ahead of its expiry. */
        private Duration refreshSkew = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTokenUri() {
            return tokenUri;
        }

        public void setTokenUri(String tokenUri) {
            this.tokenUri = tokenUri;
        }

        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        public String getClientSecret() {
            return clientSecret;
        }

        public void setClientSecret(String clientSecret) {
            this.clientSecret = clientSecret;
        }

        public String getScope() {
            return scope;
        }

        public void setScope(String scope) {
            this.scope = scope;
        }

        public Duration getRefreshSkew() {
            return refreshSkew;
        }

        public void setRefreshSkew(Duration refreshSkew) {
            this.refreshSkew = refreshSkew;
        }
    }
}
