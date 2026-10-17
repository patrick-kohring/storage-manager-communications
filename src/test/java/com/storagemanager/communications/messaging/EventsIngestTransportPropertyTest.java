package com.storagemanager.communications.messaging;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import org.springframework.web.client.RestClient;

import com.storagemanager.messaging.api.EnrichedMessage;
import com.storagemanager.messaging.api.TransportResult;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * jqwik property-based test for {@link EventsIngestTransport} — the wire-side HTTP-outcome →
 * {@link TransportResult} mapping.
 *
 * <p><b>Feature: messaging, Property 5: Transport-result mapping over arbitrary HTTP outcomes</b> —
 * over arbitrary events HTTP outcomes, {@code EventsIngestTransport.send} maps every {@code 2xx}
 * (ACCEPTED or DUPLICATE) to {@code Success}; {@code 400}/{@code 403} (and any other {@code 4xx})
 * to {@code PermanentFailure}; and any {@code 5xx}, network error, or timeout to
 * {@code RetryableFailure}.
 *
 * <p><b>Validates: Requirements 7.2, 7.3, 7.4</b>
 *
 * <p>Each iteration builds an independent {@link RestClient} so there is no cross-iteration state:
 * the status-code property binds a fresh {@link MockRestServiceServer} per try; the
 * network/timeout property binds a fresh request factory that throws per try.
 */
class EventsIngestTransportPropertyTest {

    private static final String BASE_URL = "http://events.test";
    private static final String EVENTS_PATH = BASE_URL + "/api/v1/events";

    /**
     * Feature: messaging, Property 5: Transport-result mapping over arbitrary HTTP outcomes.
     *
     * <p>For an arbitrary resolvable HTTP status code the events ingest endpoint may return, the
     * transport maps the outcome deterministically: {@code 2xx} → {@link TransportResult.Success};
     * {@code 5xx} → {@link TransportResult.RetryableFailure}; every {@code 4xx} (explicitly
     * including {@code 400} and {@code 403}) → {@link TransportResult.PermanentFailure}.
     *
     * <p><b>Validates: Requirements 7.2, 7.3, 7.4</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    @Label("Feature: messaging, Property 5: Transport-result mapping over arbitrary HTTP outcomes (Validates: Requirements 7.2, 7.3, 7.4)")
    void property5_transportResultMapping_overHttpStatusOutcomes(
            @ForAll("httpStatusCodes") int statusCode) {

        // Independent client/server per iteration (each expects exactly one request).
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        EventsIngestTransport transport = new EventsIngestTransport(builder.build());

        server.expect(requestTo(EVENTS_PATH))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.valueOf(statusCode)));

        TransportResult result = transport.send(sampleMessage());
        server.verify();

        if (statusCode >= 200 && statusCode < 300) {
            assertThat(result)
                    .as("2xx (%d) must map to Success", statusCode)
                    .isInstanceOf(TransportResult.Success.class);
        } else if (statusCode >= 500) {
            assertThat(result)
                    .as("5xx (%d) must map to RetryableFailure", statusCode)
                    .isInstanceOf(TransportResult.RetryableFailure.class);
        } else {
            // Every 4xx — 400 and 403 explicitly, plus any other 4xx — is a permanent failure.
            assertThat(result)
                    .as("4xx (%d) must map to PermanentFailure", statusCode)
                    .isInstanceOf(TransportResult.PermanentFailure.class);
        }
    }

    /**
     * Feature: messaging, Property 5: Transport-result mapping over arbitrary HTTP outcomes.
     *
     * <p>For an arbitrary low-level transport failure — a connection error (generic
     * {@link IOException}) or a timeout ({@link SocketTimeoutException}) raised by the HTTP client
     * before any status is observed — the transport maps the outcome to
     * {@link TransportResult.RetryableFailure}, so the outbox row is retried rather than dropped.
     *
     * <p><b>Validates: Requirement 7.4</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    @Label("Feature: messaging, Property 5: network/timeout outcomes map to RetryableFailure (Validates: Requirement 7.4)")
    void property5_transportResultMapping_networkAndTimeoutErrors(@ForAll("transportErrors") boolean timeout) {
        // A fresh RestClient whose request factory throws before producing a status (connection
        // refused / timeout). RestClient wraps this in a ResourceAccessException (a
        // RestClientException that is NOT a RestClientResponseException), exercising the
        // network/timeout branch.
        ClientHttpRequestFactory throwingFactory = (uri, httpMethod) -> {
            throw timeout
                    ? new SocketTimeoutException("read timed out")
                    : new IOException("connection refused");
        };
        RestClient restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(throwingFactory)
                .build();
        EventsIngestTransport transport = new EventsIngestTransport(restClient);

        TransportResult result = transport.send(sampleMessage());

        assertThat(result)
                .as("network error / timeout (timeout=%s) must map to RetryableFailure", timeout)
                .isInstanceOf(TransportResult.RetryableFailure.class);
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /** A valid, fully-enriched generic message; its content is immaterial to the mapping. */
    private static EnrichedMessage sampleMessage() {
        UUID entityId = UUID.randomUUID();

        LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
        scope.put("organization_id", UUID.randomUUID());
        scope.put("facility_id", UUID.randomUUID());

        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityId", entityId.toString());

        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().toString());
        headers.put("entity_type", "Thing");
        headers.put("entity_id", entityId.toString());
        headers.put("actor_type", "SYSTEM");

        return new EnrichedMessage(
                UUID.randomUUID(), "SomethingHappened", payload, scope, UUID.randomUUID(), headers);
    }

    // ------------------------------------------------------------------------
    // Generators (smart: constrained to the mapping's defined input space)
    // ------------------------------------------------------------------------

    /**
     * Representative, resolvable HTTP status codes across the mapping's defined input space:
     * {@code 2xx}, {@code 4xx}, and {@code 5xx}. The transport contract (design "TransportResult
     * mapping" table, Req 7.2–7.4) is defined only over success ({@code 2xx}) and error
     * ({@code 4xx}/{@code 5xx}) responses; {@code 3xx} redirects are outside the contract and an
     * events ingest endpoint does not return them, so they are deliberately excluded. {@code 400}
     * and {@code 403} are mixed in explicitly so those named branches (Req 7.3) are always
     * exercised.
     */
    @Provide
    Arbitrary<Integer> httpStatusCodes() {
        Arbitrary<Integer> explicit =
                Arbitraries.of(200, 201, 202, 204, 400, 403, 404, 409, 422, 500, 502, 503, 504);
        Arbitrary<Integer> twoXx = Arbitraries.integers().between(200, 299)
                .filter(EventsIngestTransportPropertyTest::isResolvableStatus);
        Arbitrary<Integer> fourXx = Arbitraries.integers().between(400, 499)
                .filter(EventsIngestTransportPropertyTest::isResolvableStatus);
        Arbitrary<Integer> fiveXx = Arbitraries.integers().between(500, 599)
                .filter(EventsIngestTransportPropertyTest::isResolvableStatus);
        return Arbitraries.oneOf(explicit, twoXx, fourXx, fiveXx);
    }

    /** Both low-level failure shapes: {@code false} = connection error, {@code true} = timeout. */
    @Provide
    Arbitrary<Boolean> transportErrors() {
        return Arbitraries.of(Boolean.TRUE, Boolean.FALSE);
    }

    private static boolean isResolvableStatus(@IntRange(min = 200, max = 599) int code) {
        try {
            HttpStatus.valueOf(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
