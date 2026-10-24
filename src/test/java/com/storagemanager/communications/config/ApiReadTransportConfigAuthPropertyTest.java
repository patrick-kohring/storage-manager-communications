package com.storagemanager.communications.config;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.web.client.RestClient;

import com.sun.net.httpserver.HttpServer;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * Property test for {@link ApiReadTransportConfig} auth attachment.
 *
 * <p><strong>Feature: live-lead-read-port, Property 3: Auth attachment follows the toggle.</strong>
 * When {@code auth.enabled=true}, every outbound request the config-built {@link RestClient} issues
 * carries an {@code Authorization: Bearer <token>} header resolved from the
 * {@link ApiAuthTokenProvider} seam. When {@code auth.enabled=false}, no {@code Authorization}
 * header is sent and the provider is never consulted.
 *
 * <p>This exercises the actual {@link RestClient} the configuration produces (base URL + its own
 * timeout request factory + the optional request interceptor) by pointing it at a lightweight local
 * {@link HttpServer} stub that records the inbound {@code Authorization} header on every request.
 * A counting {@link ApiAuthTokenProvider} records how many times {@code currentToken()} is
 * consulted, so the disabled case can assert the provider is never called. Structurally mirrors the
 * events-direction {@code CommunicationsTransportConfigAuthPropertyTest}, adapted to the
 * communications -> storage-manager-api read transport.
 *
 * <p><strong>Validates: Requirements 4.3, 4.4</strong>
 */
class ApiReadTransportConfigAuthPropertyTest {

    /**
     * Enabled toggle: the config attaches a bearer header resolved from the provider on every
     * outbound request (Req 4.3), and the provider is consulted (Req 4.4).
     */
    @Property(tries = 100)
    void whenAuthEnabled_everyRequestCarriesTheProviderBearerToken(@ForAll("tokens") String token)
            throws IOException {

        AtomicReference<String> captured = new AtomicReference<>(SENTINEL_UNSET);
        HttpServer server = startRecordingServer(captured);
        try {
            ApiReadProperties props = propsFor(baseUrl(server), true);
            CountingTokenProvider tokenProvider = new CountingTokenProvider(token);

            RestClient client = new ApiReadTransportConfig(props).apiReadRestClient(tokenProvider);

            client.get().uri("/api/v1/leads/{id}", UUID.randomUUID()).retrieve().toBodilessEntity();

            // Req 4.3: the outbound request carries the expected bearer header from the provider.
            assertThat(captured.get()).isEqualTo("Bearer " + token);
            // Req 4.4: the provider seam is consulted when auth is enabled.
            assertThat(tokenProvider.calls()).isGreaterThanOrEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    /**
     * Disabled toggle: no {@code Authorization} header is sent and the provider seam is never
     * consulted (Req 4.3, 4.4 — the no-op seam is injectable but untouched when disabled).
     */
    @Property(tries = 100)
    void whenAuthDisabled_noAuthorizationHeaderAndProviderNeverConsulted(@ForAll("tokens") String token)
            throws IOException {

        AtomicReference<String> captured = new AtomicReference<>(SENTINEL_UNSET);
        HttpServer server = startRecordingServer(captured);
        try {
            ApiReadProperties props = propsFor(baseUrl(server), false);
            CountingTokenProvider tokenProvider = new CountingTokenProvider(token);

            RestClient client = new ApiReadTransportConfig(props).apiReadRestClient(tokenProvider);

            client.get().uri("/api/v1/leads/{id}", UUID.randomUUID()).retrieve().toBodilessEntity();

            // Req 4.3: no Authorization header present on the outbound request.
            assertThat(captured.get()).isNull();
            // Req 4.4: the token provider is never consulted when auth is disabled.
            assertThat(tokenProvider.calls()).isZero();
        } finally {
            server.stop(0);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Generators
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Non-blank bearer token strings. Constrained to characters valid in an HTTP header value (no
     * CR/LF/control chars) so the local stub server reliably round-trips what the interceptor sets.
     */
    @Provide
    Arbitrary<String> tokens() {
        return Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~+/=")
                .ofMinLength(1)
                .ofMaxLength(64);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Sentinel distinct from {@code null} so we can tell "no header seen" from "header absent". */
    private static final String SENTINEL_UNSET = "<<unset>>";

    private static ApiReadProperties propsFor(String baseUrl, boolean authEnabled) {
        ApiReadProperties props = new ApiReadProperties();
        props.setBaseUrl(baseUrl);
        props.setConnectTimeout(Duration.ofSeconds(2));
        props.setReadTimeout(Duration.ofSeconds(5));
        props.getAuth().setEnabled(authEnabled);
        return props;
    }

    private static String baseUrl(HttpServer server) {
        return "http://localhost:" + server.getAddress().getPort();
    }

    /**
     * Starts a local HTTP server that records the first {@code Authorization} header it sees into
     * {@code captured} (left {@code null} when the request carries no such header) and replies 200.
     */
    private static HttpServer startRecordingServer(AtomicReference<String> captured) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/leads", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            captured.set(auth); // null when the header is absent
            byte[] response = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();
        return server;
    }

    /** Counting {@link ApiAuthTokenProvider} that records how often it is consulted. */
    private static final class CountingTokenProvider implements ApiAuthTokenProvider {
        private final String token;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingTokenProvider(String token) {
            this.token = token;
        }

        @Override
        public String currentToken() {
            calls.incrementAndGet();
            return token;
        }

        int calls() {
            return calls.get();
        }
    }
}
