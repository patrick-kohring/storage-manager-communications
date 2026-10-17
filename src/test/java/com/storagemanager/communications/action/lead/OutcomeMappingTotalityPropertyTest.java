package com.storagemanager.communications.action.lead;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.web.client.RestClient;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * jqwik property-based test for {@link HttpLeadReadPort} — the HTTP-outcome → port-contract mapping.
 *
 * <p><b>Feature: live-lead-read-port, Property 2: Outcome mapping is total</b> — for any HTTP
 * outcome, {@code findLead} yields exactly one result and never returns {@code null} nor throws an
 * unmapped exception:
 * <ul>
 *   <li>404 → {@link Optional#empty()} (terminal lead-not-found; no throw).</li>
 *   <li>2xx with a valid body → a populated {@link Optional}.</li>
 *   <li>5xx / 401 / 403 / any other error status / transport error / unparseable body / a 200
 *       missing the required {@code organizationId} → {@link LeadReadException}.</li>
 * </ul>
 *
 * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 2.1</b>
 *
 * <p>Each iteration builds an independent {@link RestClient} so there is no cross-iteration state:
 * HTTP-status / body outcomes bind a fresh {@link MockRestServiceServer} per try (each expecting a
 * single request); the transport-error outcome binds a fresh request factory that throws before any
 * status is observed.
 */
class OutcomeMappingTotalityPropertyTest {

    private static final String BASE_URL = "http://api.test";
    private static final String LEAD_PATH_PREFIX = BASE_URL + "/api/v1/leads/";

    /**
     * Feature: live-lead-read-port, Property 2: Outcome mapping is total.
     *
     * <p>For an arbitrary scripted outcome family, {@link HttpLeadReadPort#findLead(UUID)} produces
     * exactly the one mapped result the contract defines (Req 3.1–3.6, 2.1), never {@code null} and
     * never an unmapped exception type.
     *
     * <p><b>Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 2.1</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    @Label("Feature: live-lead-read-port, Property 2: Outcome mapping is total (Validates: Requirements 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 2.1)")
    void property2_outcomeMappingIsTotal(@ForAll("outcomes") Outcome outcome) {
        UUID leadId = UUID.randomUUID();

        // Independent client per iteration. For transport errors we attach a throwing request
        // factory (RestClient wraps it in a ResourceAccessException, i.e. a RestClientException
        // that is NOT a RestClientResponseException); for all other outcomes we bind a fresh
        // MockRestServiceServer that expects exactly one GET to the lead path.
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = null;

        if (outcome.kind() == OutcomeKind.TRANSPORT_ERROR) {
            ClientHttpRequestFactory throwingFactory = (uri, httpMethod) -> {
                throw outcome.timeout()
                        ? new SocketTimeoutException("simulated read timeout")
                        : new IOException("simulated connection refused");
            };
            builder = builder.requestFactory(throwingFactory);
        } else {
            server = MockRestServiceServer.bindTo(builder).build();
            var response = server.expect(requestTo(LEAD_PATH_PREFIX + leadId))
                    .andExpect(method(HttpMethod.GET));
            switch (outcome.kind()) {
                case OK_VALID -> response.andRespond(
                        withSuccess(validLeadBody(), MediaType.APPLICATION_JSON));
                case MALFORMED_BODY -> response.andRespond(
                        withSuccess(outcome.body(), MediaType.APPLICATION_JSON));
                default -> response.andRespond(withStatus(HttpStatus.valueOf(outcome.statusCode())));
            }
        }

        HttpLeadReadPort port = new HttpLeadReadPort(builder.build());

        Throwable thrown = catchThrowable(() -> {
            Optional<LeadView> result = port.findLead(leadId);

            // Non-throwing outcomes: the result is never null.
            assertThat(result)
                    .as("findLead must never return null (outcome=%s)", outcome)
                    .isNotNull();

            switch (outcome.kind()) {
                case NOT_FOUND -> assertThat(result)
                        .as("404 must map to Optional.empty() (Req 3.1)")
                        .isEmpty();
                case OK_VALID -> assertThat(result)
                        .as("200 valid body must map to a populated Optional (Req 3.2)")
                        .isPresent();
                default -> { /* throwing outcomes are asserted below */ }
            }
        });

        if (server != null) {
            server.verify();
        }

        switch (outcome.kind()) {
            case NOT_FOUND, OK_VALID -> assertThat(thrown)
                    .as("non-error outcome (%s) must not throw", outcome)
                    .isNull();
            default -> assertThat(thrown)
                    .as("error outcome (%s) must throw exactly LeadReadException (Req 3.3–3.5, 2.1)",
                            outcome)
                    .isInstanceOf(LeadReadException.class);
        }
    }

    // ------------------------------------------------------------------------
    // Outcome model
    // ------------------------------------------------------------------------

    private enum OutcomeKind {
        NOT_FOUND,       // 404 -> Optional.empty()
        OK_VALID,        // 200 well-formed -> populated Optional
        SERVER_ERROR,    // 5xx -> LeadReadException
        UNAUTHORIZED,    // 401 -> LeadReadException
        FORBIDDEN,       // 403 -> LeadReadException
        TRANSPORT_ERROR, // connection/timeout -> LeadReadException
        MALFORMED_BODY   // 200 with unparseable JSON or missing organizationId -> LeadReadException
    }

    /**
     * One scripted HTTP outcome. {@code statusCode} is meaningful only for status-driven kinds;
     * {@code body} only for {@link OutcomeKind#MALFORMED_BODY}; {@code timeout} only for
     * {@link OutcomeKind#TRANSPORT_ERROR}.
     */
    private record Outcome(OutcomeKind kind, int statusCode, String body, boolean timeout) {
        static Outcome status(OutcomeKind kind, int statusCode) {
            return new Outcome(kind, statusCode, null, false);
        }

        static Outcome malformed(String body) {
            return new Outcome(OutcomeKind.MALFORMED_BODY, 200, body, false);
        }

        static Outcome transport(boolean timeout) {
            return new Outcome(OutcomeKind.TRANSPORT_ERROR, 0, null, timeout);
        }
    }

    // ------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------

    /**
     * A well-formed 200 lead body in the new api shape the adapter maps to a populated LeadView:
     * organizationId lives under a nested {@code facility} object, alongside top-level lead fields.
     */
    private static String validLeadBody() {
        return """
                {
                  "facilityId": "%s",
                  "firstName": "Jo",
                  "lastName": "Smith",
                  "email": "lead@example.test",
                  "homePhone": null,
                  "mobilePhone": "+15555550123",
                  "city": "Austin",
                  "state": "TX",
                  "postalCode": "78701",
                  "dateOfBirth": "1990-01-02",
                  "source": "WEBSITE",
                  "preferredContactMethod": "EMAIL",
                  "facility": {
                    "id": "%s",
                    "organizationId": "%s",
                    "name": "Downtown"
                  }
                }
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    // ------------------------------------------------------------------------
    // Generators (smart: constrained to the mapping's defined outcome space)
    // ------------------------------------------------------------------------

    /**
     * Every outcome family the contract maps, with status-driven kinds drawing resolvable status
     * codes across their defined ranges: {@link OutcomeKind#NOT_FOUND} pinned to 404,
     * {@link OutcomeKind#SERVER_ERROR} across 5xx, {@link OutcomeKind#UNAUTHORIZED}/
     * {@link OutcomeKind#FORBIDDEN} pinned to 401/403, plus body-shape and transport failures.
     */
    @Provide
    Arbitrary<Outcome> outcomes() {
        Arbitrary<Outcome> notFound = Arbitraries.just(Outcome.status(OutcomeKind.NOT_FOUND, 404));
        Arbitrary<Outcome> okValid = Arbitraries.just(Outcome.status(OutcomeKind.OK_VALID, 200));
        Arbitrary<Outcome> unauthorized =
                Arbitraries.just(Outcome.status(OutcomeKind.UNAUTHORIZED, 401));
        Arbitrary<Outcome> forbidden =
                Arbitraries.just(Outcome.status(OutcomeKind.FORBIDDEN, 403));

        Arbitrary<Outcome> serverError = Arbitraries.integers().between(500, 599)
                .filter(OutcomeMappingTotalityPropertyTest::isResolvableStatus)
                .map(code -> Outcome.status(OutcomeKind.SERVER_ERROR, code));

        Arbitrary<Outcome> transport =
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(Outcome::transport);

        // Malformed 200s: non-JSON, empty JSON object (missing organizationId), and a JSON object
        // explicitly missing organizationId but otherwise shaped — all must surface as
        // LeadReadException (Req 2.1, 3.4).
        Arbitrary<Outcome> malformed = malformedBodies().map(Outcome::malformed);

        return Arbitraries.oneOf(
                notFound, okValid, serverError, unauthorized, forbidden, transport, malformed);
    }

    @Provide
    Arbitrary<String> malformedBodies() {
        Arbitrary<String> fixed = Arbitraries.of(
                // Non-JSON / truncated bodies -> unparseable -> LeadReadException (Req 3.4).
                "not-json-at-all",
                "{",
                // Well-formed JSON but NO facility object -> missing facility.organizationId.
                "{}",
                "{\"facilityId\": \"" + UUID.randomUUID() + "\", \"email\": \"a@b.test\","
                        + " \"preferredContactMethod\": \"EMAIL\"}",
                // facility present but organizationId null -> missing facility.organizationId.
                "{\"preferredContactMethod\": \"EMAIL\","
                        + " \"facility\": {\"id\": \"" + UUID.randomUUID() + "\","
                        + " \"organizationId\": null, \"name\": \"Downtown\"}}",
                // facility present but WITHOUT organizationId key -> missing facility.organizationId.
                "{\"preferredContactMethod\": \"SMS\","
                        + " \"facility\": {\"id\": \"" + UUID.randomUUID() + "\", \"name\": \"Downtown\"}}",
                // facility explicitly null -> missing facility.organizationId.
                "{\"preferredContactMethod\": \"EMAIL\", \"facility\": null}");
        // A JSON object whose facility carries a null organizationId, across both methods.
        Arbitrary<String> nullOrg = Arbitraries.of("EMAIL", "SMS")
                .map(method -> "{\"preferredContactMethod\": \"" + method + "\","
                        + " \"facility\": {\"organizationId\": null}}");
        return Arbitraries.oneOf(fixed, nullOrg);
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
