package com.storagemanager.communications.config;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Real {@link ApiAuthTokenProvider} that obtains a service-identity token through the OAuth2
 * client-credentials flow (carrying the configured scope, {@code leads/read} by default) and caches
 * it until shortly before expiry.
 *
 * <p>This is the communications -> storage-manager-api outbound analog of the events -> communications
 * {@code ClientCredentialsCommunicationsAuthTokenProvider} and the api -> events
 * {@code ClientCredentialsEventsAuthTokenProvider}. The {@code ActionProcessor} reads a lead on an
 * inbox-dispatch thread with no end-user security context, so the {@code HttpLeadReadPort}
 * authenticates to the api as the communications service's own service identity. It is wired in
 * place of {@link ApiAuthTokenProvider.Disabled} by {@link ApiReadTransportConfig} when
 * {@code storagemanager.api.auth.enabled=true}.
 *
 * <p>Caching bounds token-endpoint calls to one per expiry window: a valid cached token is returned
 * without contacting the endpoint; a refresh happens only when the cache is empty, expired, or
 * within the configured refresh skew of expiry, and a double-checked {@code synchronized} block
 * guarantees at most one in-flight refresh under concurrent callers.
 *
 * <p>A token-endpoint failure propagates out of {@link #currentToken()} (it is not swallowed); the
 * {@code HttpLeadReadPort} turns that into a retryable lead-read failure so the action is retried.
 */
public class ClientCredentialsApiAuthTokenProvider implements ApiAuthTokenProvider {

    private final RestClient tokenClient;
    private final ApiReadProperties.Auth auth;

    private final Object refreshLock = new Object();
    private volatile CachedToken cached;

    public ClientCredentialsApiAuthTokenProvider(RestClient tokenClient,
                                                 ApiReadProperties.Auth auth) {
        this.tokenClient = tokenClient;
        this.auth = auth;
    }

    @Override
    public String currentToken() {
        CachedToken current = cached;
        if (current != null && !current.isWithin(auth.getRefreshSkew())) {
            return current.value();
        }
        synchronized (refreshLock) {
            // Re-check under lock: another thread may have refreshed while we waited.
            if (cached != null && !cached.isWithin(auth.getRefreshSkew())) {
                return cached.value();
            }
            CachedToken fresh = fetch();
            this.cached = fresh;
            return fresh.value();
        }
    }

    /**
     * Perform the client-credentials exchange. POSTs an
     * {@code application/x-www-form-urlencoded} body requesting exactly the configured scope and
     * returns the parsed token plus its computed absolute expiry. Transport exceptions are allowed
     * to propagate.
     */
    private CachedToken fetch() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("scope", auth.getScope());
        form.add("client_id", auth.getClientId());
        form.add("client_secret", auth.getClientSecret());

        TokenResponse body = tokenClient.post()
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(TokenResponse.class);

        if (body == null || body.accessToken() == null) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        Instant expiresAt = Instant.now().plusSeconds(body.expiresIn());
        return new CachedToken(body.accessToken(), expiresAt);
    }

    /** A cached service token and its absolute expiry. */
    record CachedToken(String value, Instant expiresAt) {
        /** True when the token is already within {@code skew} of (or past) its expiry. */
        boolean isWithin(Duration skew) {
            return Instant.now().plus(skew).isAfter(expiresAt);
        }
    }

    /** The client-credentials token response shape. */
    record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn) {
    }
}
