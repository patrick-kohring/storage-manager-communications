package com.storagemanager.communications.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Wires the {@link RestClient} used by {@code HttpLeadReadPort} to read leads from
 * {@code storage-manager-api} ({@code GET /api/v1/leads/{id}}).
 *
 * <p><strong>Profile.</strong> Guarded by {@code @Profile("!local & !test & !default")} — the same
 * guard as {@code HttpLeadReadPort} — so the api {@link RestClient} bean only exists in the
 * non-local runtime profile. Under {@code local}/{@code test}/{@code default} the in-memory
 * {@code StubLeadReadPort} is active and the api base URL is never needed, so neither this
 * configuration nor its client is created (Req 5.1).
 *
 * <p>The structure deliberately mirrors the comms -> events outbound transport
 * ({@code MessagingTransportConfig} / {@code EventsIngestProperties} /
 * {@code EventsAuthTokenProvider}); only the direction and target differ (here communications calls
 * storage-manager-api).
 *
 * <p>This configuration provides <em>only</em> the {@code apiReadRestClient} bean (and enables the
 * {@link ApiReadProperties} binding). It does <em>not</em> declare an {@code HttpLeadReadPort} bean:
 * that adapter is a {@code @Component} under the same profile guard and self-registers, so declaring
 * it here would double-register it.
 *
 * <p>Requirements: 4.1, 4.2, 4.3, 5.1.
 */
@Configuration
@EnableConfigurationProperties(ApiReadProperties.class)
@Profile("!local & !test & !default")
public class ApiReadTransportConfig {

    private final ApiReadProperties props;
    private final ApiAuthTokenProvider tokenProvider;

    public ApiReadTransportConfig(ApiReadProperties props, ApiAuthTokenProvider tokenProvider) {
        this.props = props;
        this.tokenProvider = tokenProvider;
    }

    /**
     * Builds the api {@link RestClient} (base URL + connect/read timeouts, plus the optional s2s
     * bearer interceptor) consumed by {@code HttpLeadReadPort}. The bean is named
     * {@code apiReadRestClient} because {@code HttpLeadReadPort}'s constructor injects
     * {@code @Qualifier("apiReadRestClient")}.
     *
     * <p>Fails fast with a clear message when {@code storagemanager.api.base-url} is blank (Req 4.1).
     * When {@code auth.enabled=true} a request interceptor attaches a {@code leads/read}-scoped
     * service bearer token from {@link ApiAuthTokenProvider}; when disabled (the Phase-0 default) no
     * interceptor is added and requests carry no {@code Authorization} header (Req 4.3).
     */
    @Bean
    public RestClient apiReadRestClient() {
        if (!StringUtils.hasText(props.getBaseUrl())) {
            throw new IllegalStateException(
                    "storagemanager.api.base-url must be configured (no default host); "
                            + "set it to the storage-manager-api base URL");
        }
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(props.clientRequestFactoryWithTimeouts()); // connect/read timeouts (Req 4.2)
        if (props.getAuth().isEnabled()) {
            // WHERE s2s auth enabled: attach a leads/read-scoped service bearer token (Req 4.3).
            builder = builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(tokenProvider.currentToken());
                return execution.execute(request, body);
            });
        }
        // WHERE disabled (Phase-0 default): no interceptor, no Authorization header (Req 4.3).
        return builder.build();
    }
}
