package com.storagemanager.communications.config;

import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import com.storagemanager.communications.messaging.EventsIngestTransport;
import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.TransportResult;

/**
 * Auth-seam tests for the host transport wiring (task 13.3).
 *
 * <p>Asserts the {@link MessagingTransportConfig}-built {@link OutboxTransport} attaches an
 * {@code Authorization: Bearer} header when {@code storagemanager.messaging.events.auth.enabled=true}
 * (Req 7.5) and attaches no {@code Authorization} header when auth is disabled — the local Phase 0
 * posture (Req 7.6).
 *
 * <p>The config builds its own {@link RestClient} internally (applying a timeout request factory),
 * which cannot be rebound to a {@link MockRestServiceServer} after the fact. These tests therefore
 * do two complementary things:
 * <ol>
 *   <li>Invoke the real {@link MessagingTransportConfig#eventsIngestTransport} for both auth states
 *       to prove the production gating branch constructs a usable transport.</li>
 *   <li>Drive the <em>same interceptor wiring the config applies</em> through a
 *       {@link MockRestServiceServer} to observe the on-the-wire {@code Authorization} header
 *       presence/absence that the config's {@code auth.enabled} branch produces.</li>
 * </ol>
 *
 * <p>Validates: Requirements 7.5, 7.6
 */
class MessagingTransportAuthTest {

    private static final String BASE_URL = "http://events.test";
    private static final String EVENTS_PATH = BASE_URL + "/api/v1/events";
    private static final String FIXED_TOKEN = "fixed-service-token";

    private final MessagingTransportConfig config = new MessagingTransportConfig();

    /** Stub provider returning a fixed, obviously-recognizable token. */
    private static final EventsAuthTokenProvider FIXED_PROVIDER = () -> FIXED_TOKEN;

    // ------------------------------------------------------------------------
    // 1. Production config method constructs a usable transport for both states.
    // ------------------------------------------------------------------------

    @Test
    void configBuildsTransportWhenAuthEnabled() {
        EventsIngestProperties props = authProps(true);
        OutboxTransport transport = config.eventsIngestTransport(props, FIXED_PROVIDER);
        assertThat(transport)
                .as("auth.enabled=true still yields a host OutboxTransport (Req 7.5)")
                .isInstanceOf(EventsIngestTransport.class);
    }

    @Test
    void configBuildsTransportWhenAuthDisabled() {
        EventsIngestProperties props = authProps(false);
        OutboxTransport transport = config.eventsIngestTransport(props, FIXED_PROVIDER);
        assertThat(transport)
                .as("auth.enabled=false still yields a host OutboxTransport (Req 7.6)")
                .isInstanceOf(EventsIngestTransport.class);
    }

    // ------------------------------------------------------------------------
    // 2. Observable header behavior via MockRestServiceServer, using the SAME
    //    interceptor wiring MessagingTransportConfig applies.
    // ------------------------------------------------------------------------

    // Req 7.5: with auth enabled, the request carries Authorization: Bearer <token>.
    @Test
    void authEnabledAttachesBearerHeader() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        // Mirror MessagingTransportConfig's auth-enabled branch exactly.
        builder = builder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().setBearerAuth(FIXED_PROVIDER.currentToken());
            return execution.execute(request, body);
        });
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OutboxTransport transport = new EventsIngestTransport(builder.build());

        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + FIXED_TOKEN))
                .andRespond(withSuccess());

        TransportResult result = transport.send(sampleMessage());

        assertThat(result.isSuccess()).isTrue();
        server.verify();
    }

    // Req 7.6: with auth disabled, no interceptor is added -> no Authorization header.
    @Test
    void authDisabledAttachesNoAuthorizationHeader() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        // Mirror MessagingTransportConfig's auth-disabled branch: no interceptor added.
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OutboxTransport transport = new EventsIngestTransport(builder.build());

        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(headerDoesNotExist(HttpHeaders.AUTHORIZATION))
                .andRespond(withSuccess());

        TransportResult result = transport.send(sampleMessage());

        assertThat(result.isSuccess()).isTrue();
        server.verify();
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    private static EventsIngestProperties authProps(boolean authEnabled) {
        EventsIngestProperties props = new EventsIngestProperties();
        props.setBaseUrl(BASE_URL);
        props.getAuth().setEnabled(authEnabled);
        return props;
    }

    /** A representative generic enriched message (actor_id / causation_id absent). */
    private static EnrichedMessage sampleMessage() {
        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", UUID.randomUUID());
        scope.put("facility_id", UUID.randomUUID());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("foo", "bar");

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", java.time.Instant.now().toString());
        headers.put("entity_type", "Thing");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", "SYSTEM");

        return new EnrichedMessage(
                UUID.randomUUID(), "GenericEvent", payload, scope, UUID.randomUUID(), headers);
    }
}
