package com.storagemanager.communications.config;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.storagemanager.communications.action.lead.HttpLeadReadPort;
import com.storagemanager.communications.action.lead.LeadReadPort;
import com.storagemanager.communications.action.lead.StubLeadReadPort;

/**
 * Profile / wiring context test for the live-lead-read-port spec (tasks.md task 5.1; design
 * "Testing strategy" / "Decisions D1"; Requirements 5.1, 5.2, 4.1).
 *
 * <p>Asserts the three wiring guarantees the design depends on:
 *
 * <ol>
 *   <li><strong>Default/test profile uses the stub.</strong> Under the default profile (no active
 *       profile, as the module's {@code @SpringBootTest} slices run) the single active
 *       {@link LeadReadPort} is the in-memory {@link StubLeadReadPort}, and the live
 *       {@link HttpLeadReadPort} is not wired (Req 5.1, 5.2).</li>
 *   <li><strong>Runtime profile wires the live adapter.</strong> Under a non-local runtime profile
 *       ({@code runtime}, which satisfies {@code !local & !test & !default}) the live
 *       {@link HttpLeadReadPort} and its {@code apiReadRestClient} are created, and the stub is not
 *       (Req 5.1).</li>
 *   <li><strong>Fail-fast on a blank base URL.</strong> Under the runtime profile a blank
 *       {@code storagemanager.api.base-url} fails context startup with a clear
 *       {@link IllegalStateException} (Req 4.1).</li>
 * </ol>
 *
 * <p><strong>Approach.</strong> Uses a lightweight {@link ApplicationContextRunner} rather than a
 * full {@code @SpringBootTest} for all three cases, mirroring the events-direction
 * {@code CommunicationsTransportConfigContextTest} / {@code PublisherWiringContextTest}. It
 * registers exactly the beans under test — both {@link LeadReadPort} implementations plus the live
 * transport configuration ({@link ApiReadTransportConfig}, {@link ApiReadProperties}) and a no-op
 * {@link ApiAuthTokenProvider} seam — and lets Spring evaluate the {@code @Profile} guards on those
 * bean definitions per run. Driving the profile via {@code spring.profiles.active}
 * keeps every case hermetic (no datasource, no JWKS fetch, no scheduled drain) and asserts the
 * profile gating directly, which is less brittle than booting the whole engine just to read back a
 * single bean.
 *
 * <p>Requirements: 5.1, 5.2, 4.1.
 */
class LeadReadPortWiringContextTest {

    /**
     * Registers both {@link LeadReadPort} implementations and the live transport wiring. The
     * {@code @Profile} guards on {@link StubLeadReadPort} ({@code local|test|default}) and on
     * {@link HttpLeadReadPort} / {@link ApiReadTransportConfig} ({@code !local & !test & !default})
     * decide which of them actually forms in any given run, so the same runner drives every case.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(
                    StubLeadReadPort.class,
                    HttpLeadReadPort.class,
                    ApiReadTransportConfig.class,
                    AuthProviderConfig.class));

    /**
     * Default profile (no active profile): the single active {@link LeadReadPort} is the
     * {@link StubLeadReadPort}, and the live {@link HttpLeadReadPort} / {@code apiReadRestClient}
     * are not wired. This is the posture the module's {@code @SpringBootTest} slices and local
     * development run under (Req 5.1, 5.2).
     */
    @Test
    void defaultProfileActiveLeadReadPortIsTheStub() {
        contextRunner.run(context -> {
            assertThat(context)
                    .as("context starts under the default profile")
                    .hasNotFailed();
            // Exactly one LeadReadPort is active (no ambiguous-bean failure) — Req 5.2.
            assertThat(context).hasSingleBean(LeadReadPort.class);
            // ...and it is the in-memory stub — Req 5.1.
            assertThat(context.getBean(LeadReadPort.class))
                    .as("the active LeadReadPort under the default profile is the StubLeadReadPort")
                    .isInstanceOf(StubLeadReadPort.class);
            // The live adapter and its api RestClient are not wired under the default profile.
            assertThat(context).doesNotHaveBean(HttpLeadReadPort.class);
            assertThat(context).doesNotHaveBean("apiReadRestClient");
        });
    }

    /**
     * Non-local runtime profile ({@code runtime} satisfies {@code !local & !test & !default}): the
     * live {@link HttpLeadReadPort} and its {@code apiReadRestClient} are created, the stub is not,
     * and there is still exactly one active {@link LeadReadPort} (Req 5.1, 5.2). A non-blank
     * {@code base-url} lets the transport build.
     */
    @Test
    void runtimeProfileWiresTheLiveHttpAdapter() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=runtime",
                        "storagemanager.api.base-url=http://localhost:8080")
                .run(context -> {
                    assertThat(context)
                            .as("context starts under the runtime profile with a base-url")
                            .hasNotFailed();
                    // Exactly one active LeadReadPort (no ambiguity) — Req 5.2.
                    assertThat(context).hasSingleBean(LeadReadPort.class);
                    // ...and it is the live HTTP adapter — Req 5.1.
                    assertThat(context.getBean(LeadReadPort.class))
                            .as("the active LeadReadPort under the runtime profile is the HttpLeadReadPort")
                            .isInstanceOf(HttpLeadReadPort.class);
                    // The api RestClient the adapter depends on is wired by ApiReadTransportConfig.
                    assertThat(context).hasBean("apiReadRestClient");
                    // ...and the stub is not active under the runtime profile.
                    assertThat(context).doesNotHaveBean(StubLeadReadPort.class);
                });
    }

    /**
     * A blank {@code storagemanager.api.base-url} under the runtime profile fails context startup:
     * the {@code apiReadRestClient} factory method throws {@link IllegalStateException} naming the
     * offending key (Req 4.1), so the context never starts.
     */
    @Test
    void runtimeProfileBlankBaseUrlFailsFast() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=runtime",
                        "storagemanager.api.base-url=")
                .run(context -> {
                    assertThat(context)
                            .as("context fails to start when base-url is blank under the runtime profile")
                            .hasFailed();
                    assertThat(context)
                            .getFailure()
                            .as("the startup failure root cause is a clear IllegalStateException")
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("storagemanager.api.base-url");
                });
    }

    /**
     * Supplies the no-op {@link ApiAuthTokenProvider} seam the {@link ApiReadTransportConfig}
     * constructor requires. With auth disabled (the default) the provider is never consulted, and in
     * the blank-base-url case the fail-fast guard trips before any request is built, so a quiet
     * no-op is sufficient for every case here.
     */
    @Configuration(proxyBeanMethods = false)
    static class AuthProviderConfig {

        @Bean
        ApiAuthTokenProvider apiAuthTokenProvider() {
            return new ApiAuthTokenProvider.Disabled();
        }
    }
}
