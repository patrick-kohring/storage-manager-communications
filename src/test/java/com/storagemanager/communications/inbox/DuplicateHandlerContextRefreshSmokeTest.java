package com.storagemanager.communications.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.storagemanager.communications.messaging.EventEnvelope;

/**
 * Context/smoke test for the duplicate-eventType registration failing Spring context refresh
 * (Req 1.6), complementing the pure {@code HandlerRegistry} unit/property logic (Property 7) with
 * real Spring wiring.
 *
 * <p>This uses an {@link ApplicationContextRunner} slice — mirroring the module's
 * {@code MessagingWiringSmokeTest} and the starter's {@code MessagingAutoConfigurationTest} — to
 * assemble a minimal context containing the real {@link HandlerRegistry} {@code @Component} plus two
 * {@link InboxHandler} beans declaring the <em>same</em> {@code eventType}. Because
 * {@code HandlerRegistry}'s constructor injects {@code List<InboxHandler>} and throws
 * {@link DuplicateHandlerRegistrationException} on a collision, bean instantiation fails and the
 * context refresh aborts: the application never boots with an ambiguous registry. No database or
 * auto-configuration is required because {@code HandlerRegistry} depends only on the handler beans.
 *
 * <p>Validates: Requirement 1.6
 *
 * <p>Feature: inbox-action-dispatch, context/smoke for Req 1.6 (duplicate-eventType context-refresh
 * failure).
 */
class DuplicateHandlerContextRefreshSmokeTest {

    private static final String DUPLICATE_EVENT_TYPE = "SendCommunication";
    private static final String DISTINCT_EVENT_TYPE = "ArchiveCommunication";

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withBean(HandlerRegistry.class);

    /** A minimal no-op {@link InboxHandler} declaring a configurable {@code eventType}. */
    static final class FixedEventTypeHandler implements InboxHandler {
        private final String eventType;

        FixedEventTypeHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(EventEnvelope envelope) {
            // no-op: these smoke handlers exist only to exercise registry assembly
        }
    }

    /** Two {@link InboxHandler} beans declaring the SAME eventType (the collision under test). */
    @Configuration(proxyBeanMethods = false)
    static class DuplicateHandlersConfiguration {
        @Bean
        InboxHandler firstHandler() {
            return new FixedEventTypeHandler(DUPLICATE_EVENT_TYPE);
        }

        @Bean
        InboxHandler secondHandler() {
            return new FixedEventTypeHandler(DUPLICATE_EVENT_TYPE);
        }
    }

    /** Two {@link InboxHandler} beans declaring DISTINCT eventTypes (the healthy baseline). */
    @Configuration(proxyBeanMethods = false)
    static class DistinctHandlersConfiguration {
        @Bean
        InboxHandler sendHandler() {
            return new FixedEventTypeHandler(DUPLICATE_EVENT_TYPE);
        }

        @Bean
        InboxHandler archiveHandler() {
            return new FixedEventTypeHandler(DISTINCT_EVENT_TYPE);
        }
    }

    // Req 1.6: two handlers sharing an eventType -> context refresh fails, rooted in
    // DuplicateHandlerRegistrationException whose message names the conflicting eventType value.
    @Test
    void duplicateEventTypeFailsContextRefreshNamingConflictingEventType() {
        runner.withUserConfiguration(DuplicateHandlersConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isNotNull()
                            .hasRootCauseInstanceOf(DuplicateHandlerRegistrationException.class);
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(DUPLICATE_EVENT_TYPE);
                });
    }

    // Baseline: distinct eventTypes refresh cleanly and the registry resolves both handlers,
    // confirming the failure above is caused by the collision and not by the wiring itself.
    @Test
    void distinctEventTypesRefreshCleanlyAndResolveHandlers() {
        runner.withUserConfiguration(DistinctHandlersConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(HandlerRegistry.class);

                    HandlerRegistry registry = context.getBean(HandlerRegistry.class);
                    assertThat(registry.lookup(DUPLICATE_EVENT_TYPE)).isPresent();
                    assertThat(registry.lookup(DISTINCT_EVENT_TYPE)).isPresent();
                });
    }
}
