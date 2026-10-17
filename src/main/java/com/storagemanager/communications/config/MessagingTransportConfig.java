package com.storagemanager.communications.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.storagemanager.communications.messaging.EventsIngestTransport;
import com.storagemanager.messaging.api.OutboxTransport;

/**
 * Supplies the host {@link OutboxTransport} bean (satisfying the starter fail-fast "outbox enabled
 * requires an OutboxTransport bean" rule, Req 1.6/1.7) and the events ingest connection properties.
 *
 * <p>The transport is built on a {@link RestClient} configured with the events base URL and the
 * connect/read timeouts from {@link EventsIngestProperties}. When s2s auth is enabled
 * ({@code auth.enabled=true}, Req 7.5) a request interceptor attaches an {@code events/ingest}-scoped
 * service bearer token resolved from the {@link EventsAuthTokenProvider} seam; when disabled (local
 * Phase 0, Req 7.6) no interceptor is added and requests carry no {@code Authorization} header.
 *
 * <p>Requirements: 1.6, 1.7, 7.5, 7.6.
 */
@Configuration
public class MessagingTransportConfig {

    @Bean
    public OutboxTransport eventsIngestTransport(EventsIngestProperties props,
                                                 EventsAuthTokenProvider tokenProvider) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(props.clientRequestFactoryWithTimeouts()); // connect/read timeouts
        if (props.getAuth().isEnabled()) {
            // WHERE s2s auth enabled: attach an events/ingest-scoped service bearer token (Req 7.5).
            builder = builder.requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(tokenProvider.currentToken());
                return execution.execute(request, body);
            });
        }
        // WHERE disabled (local Phase 0): deliver without a token (Req 7.6).
        return new EventsIngestTransport(builder.build());
    }

    @Bean
    @ConfigurationProperties(prefix = "storagemanager.messaging.events")
    public EventsIngestProperties eventsIngestProperties() {
        return new EventsIngestProperties();
    }
}
