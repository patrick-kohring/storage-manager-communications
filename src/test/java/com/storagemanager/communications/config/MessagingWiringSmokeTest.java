package com.storagemanager.communications.config;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.OutboxTransport;
import com.storagemanager.messaging.api.OutboxWriter;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;
import com.storagemanager.messaging.internal.MessagingProperties;
import com.storagemanager.messaging.internal.OutboxPublisher;

/**
 * Wiring smoke tests for the communications messaging base (task 13.3).
 *
 * <p>These are {@link ApplicationContextRunner}-based slice tests that assert the one-time wiring
 * and configuration contract of Requirement 1 and the local-issuer config of Requirement 4.7,
 * without booting the full application (no Postgres, no JWKS fetch). They mirror the starter's own
 * {@code MessagingAutoConfigurationTest} approach: a minimal embedded-H2 JPA context lets the
 * starter's repository-backed beans ({@code OutboxWriter}, {@code OutboxPublisher},
 * {@code IdempotentInbox}) actually form, while the conditional wiring under test lives in
 * {@link MessagingAutoConfiguration} and the host {@link MessagingTransportConfig}.
 *
 * <ul>
 *   <li>Both halves enabled + host transport present: all starter beans and the host
 *       {@link OutboxTransport} bean are present (Req 1.2, 1.3, 1.6).</li>
 *   <li>Fail-fast: {@code outbox.enabled=true} with no {@link OutboxTransport} bean fails startup
 *       with a {@code NoSuchBeanDefinitionException} naming {@link OutboxTransport} (Req 1.7).</li>
 *   <li>Defaults: unsupplied starter properties resolve to the documented defaults (Req 1.8).</li>
 *   <li>Local issuer: {@code application-local.properties} points issuer/JWKS at the api local
 *       issuer {@code http://localhost:8080/api/v1/local} (Req 4.7).</li>
 * </ul>
 *
 * <p>Validates: Requirements 1.2, 1.3, 1.6, 1.7, 1.8, 4.7
 */
class MessagingWiringSmokeTest {

    /**
     * Embedded H2 in PostgreSQL mode with the JSONB domain alias so the starter's enriched
     * {@code OutboxEntry}/{@code InboxEntry} map cleanly and the repository-backed beans form.
     * Mirrors the starter's own {@code MessagingAutoConfigurationTest} datasource. No external DB
     * is involved; this keeps the slice fast and hermetic.
     */
    private static final String[] H2_PROPERTIES = {
            "spring.datasource.url=jdbc:h2:mem:comm-wiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
                    + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.show-sql=false",
            "spring.flyway.enabled=false",
            // Both halves on + enrichment on, matching production application.properties.
            "storagemanager.messaging.inbox.enabled=true",
            "storagemanager.messaging.outbox.enabled=true",
            "storagemanager.messaging.enrichment.enabled=true",
            "storagemanager.messaging.scope-fields[0]=organization_id",
            "storagemanager.messaging.scope-fields[1]=facility_id",
            // Push the scheduled drain far out so it does not fire during these short wiring
            // contexts — we assert bean topology, not drain behavior.
            "storagemanager.messaging.outbox.poll-interval-ms=3600000"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    MessagingAutoConfiguration.class))
            .withPropertyValues(H2_PROPERTIES);

    /**
     * Host configuration mirroring production: {@link MessagingTransportConfig} supplies the
     * {@link OutboxTransport} bean (and {@code EventsIngestProperties}); the {@code auth.enabled}
     * default is {@code false}, so the no-op {@link EventsAuthTokenProvider.Disabled} seam suffices.
     */
    @Configuration(proxyBeanMethods = false)
    static class HostTransportConfiguration {

        @Bean
        MessagingTransportConfig messagingTransportConfig() {
            return new MessagingTransportConfig();
        }

        @Bean
        EventsIngestProperties eventsIngestProperties(MessagingTransportConfig config) {
            // Bind via the production bean method; set the base-url the production config requires.
            EventsIngestProperties props = config.eventsIngestProperties();
            props.setBaseUrl("http://localhost:8081");
            return props;
        }

        @Bean
        EventsAuthTokenProvider eventsAuthTokenProvider() {
            return new EventsAuthTokenProvider.Disabled();
        }

        @Bean
        OutboxTransport eventsIngestTransport(MessagingTransportConfig config,
                                              EventsIngestProperties props,
                                              EventsAuthTokenProvider tokenProvider) {
            return config.eventsIngestTransport(props, tokenProvider);
        }
    }

    // Req 1.2, 1.3, 1.6: context loads with both halves enabled and the host transport bean.
    @Test
    void contextLoadsWithBothHalvesAndHostTransport() {
        runner.withUserConfiguration(HostTransportConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // Inbox half (Req 1.2).
                    assertThat(context).hasSingleBean(IdempotentInbox.class);
                    // Outbox half (Req 1.3).
                    assertThat(context).hasSingleBean(OutboxWriter.class);
                    assertThat(context).hasSingleBean(OutboxPublisher.class);
                    // Host-provided transport satisfies the publisher dependency (Req 1.6).
                    assertThat(context).hasSingleBean(OutboxTransport.class);
                });
    }

    // Req 1.7: outbox enabled but NO OutboxTransport bean -> fail-fast NoSuchBeanDefinitionException
    // naming OutboxTransport. The host transport configuration is deliberately withheld here.
    @Test
    void outboxEnabledWithoutTransportFailsFastNamingOutboxTransport() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .isNotNull()
                    .hasRootCauseInstanceOf(
                            org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining(OutboxTransport.class.getName());
        });
    }

    // Req 1.8: unsupplied starter properties resolve to documented defaults. Bind the starter's
    // MessagingProperties in a context with no overrides and assert the default values.
    @Test
    void unsuppliedStarterPropertiesResolveToDocumentedDefaults() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DefaultsProbeConfig.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MessagingProperties props = context.getBean(MessagingProperties.class);

                    // Outbox drain tuning (Req 1.8).
                    assertThat(props.getOutbox().getPollIntervalMs()).isEqualTo(1000L);
                    assertThat(props.getOutbox().getBatchSize()).isEqualTo(100);
                    // Dead-letter threshold (Req 1.8).
                    assertThat(props.getMaxAttempts()).isEqualTo(10);
                    // Backoff schedule (Req 1.8): base=1s, max=5m, multiplier=2.
                    assertThat(props.getBackoff().getBase()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(props.getBackoff().getMax()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(props.getBackoff().getMultiplier()).isEqualTo(2.0);
                });
    }

    /** Minimal config that only binds the starter {@link MessagingProperties} (no overrides). */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(MessagingProperties.class)
    static class DefaultsProbeConfig {
    }

    // Req 4.7: the local profile points the resource-server issuer-uri / jwk-set-uri at the api
    // local issuer. A lightweight properties-loading assertion over application-local.properties.
    @Test
    void localProfileIssuerPointsAtApiLocalIssuer() throws Exception {
        Properties local = new Properties();
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("application-local.properties")) {
            assertThat(in).as("application-local.properties must be on the classpath").isNotNull();
            local.load(in);
        }

        assertThat(local.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"))
                .as("local issuer-uri (Req 4.7)")
                .isEqualTo("http://localhost:8080/api/v1/local");
        assertThat(local.getProperty("spring.security.oauth2.resourceserver.jwt.jwk-set-uri"))
                .as("local jwk-set-uri (Req 4.7)")
                .isEqualTo("http://localhost:8080/api/v1/local/.well-known/jwks.json");
    }
}
