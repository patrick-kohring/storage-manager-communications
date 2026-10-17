package com.storagemanager.communications.inbox;

import java.lang.reflect.Field;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.util.AopTestUtils;

import com.storagemanager.communications.action.ActionProcessor;
import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.service.InboundRecordingService;
import com.storagemanager.messaging.api.IdempotentInbox;
import com.storagemanager.messaging.api.InboundMessage;
import com.storagemanager.messaging.autoconfigure.MessagingAutoConfiguration;

/**
 * Integration/context test for the inbox seam wiring (Req 2.1): the one cross-spec touch wired in
 * task 8.1 ({@code InboundRecordingService} now constructor-injects a {@code Consumer<InboundMessage>}
 * seam) must resolve to this spec's {@link DispatchingHandler} as the sole {@code @Component} of that
 * type.
 *
 * <p>This is an {@link ApplicationContextRunner} slice — mirroring the module's
 * {@code MessagingWiringSmokeTest} and this package's {@code DuplicateHandlerContextRefreshSmokeTest}
 * — booting the starter {@link MessagingAutoConfiguration} over an embedded H2 (PostgreSQL mode with
 * the {@code JSONB} domain alias) so the real {@link IdempotentInbox} bean forms, then layering the
 * real dispatch chain ({@link DispatchingHandler}, {@link HandlerRegistry}, {@link EnvelopeReconstructor},
 * {@link SendCommunicationBinding}) over a stub {@link ActionProcessor} and the real
 * {@link InboundRecordingService}. No external database, JWKS fetch, or transport is involved.
 *
 * <p>The assertion is twofold: (1) the only {@code Consumer<InboundMessage>} bean in the context is
 * the {@link DispatchingHandler} (the primary seam bean type), and (2) the injected seam that
 * {@link InboundRecordingService} actually holds is that same {@link DispatchingHandler} instance —
 * confirming the service is wired with the dispatcher, not the messaging base no-op.
 *
 * <p>Validates: Requirements 2.1
 *
 * <p>Feature: inbox-action-dispatch, integration/context for Req 2.1 (seam wiring:
 * InboundRecordingService &rarr; DispatchingHandler as the Consumer&lt;InboundMessage&gt; seam).
 */
class SeamWiringContextTest {

    /**
     * Embedded H2 in PostgreSQL mode with the {@code JSONB} domain alias so the starter's
     * repository-backed {@link IdempotentInbox} forms cleanly. Mirrors {@code MessagingWiringSmokeTest}:
     * inbox half on, outbox half off (no {@code OutboxTransport} needed for this inbox-only wiring
     * assertion), so startup does not demand a host transport bean.
     */
    private static final String[] H2_PROPERTIES = {
            "spring.datasource.url=jdbc:h2:mem:comm-seam-wiring;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;"
                    + "DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;"
                    + "INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS JSON",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.jpa.show-sql=false",
            "spring.flyway.enabled=false",
            // Inbox half on (the seam under test); outbox half off so no host transport is required.
            "storagemanager.messaging.inbox.enabled=true",
            "storagemanager.messaging.outbox.enabled=false",
            "storagemanager.messaging.enrichment.enabled=true",
            "storagemanager.messaging.scope-fields[0]=organization_id",
            "storagemanager.messaging.scope-fields[1]=facility_id"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    DataSourceTransactionManagerAutoConfiguration.class,
                    TransactionAutoConfiguration.class,
                    HibernateJpaAutoConfiguration.class,
                    MessagingAutoConfiguration.class))
            .withPropertyValues(H2_PROPERTIES)
            .withUserConfiguration(DispatchWiringConfiguration.class);

    /**
     * The real dispatch chain this spec owns, plus the real {@link InboundRecordingService} and a
     * stub {@link ActionProcessor} to satisfy {@link SendCommunicationBinding}'s dependency. These
     * are declared as beans (rather than component-scanned) to keep the slice minimal and hermetic.
     */
    @Configuration(proxyBeanMethods = false)
    static class DispatchWiringConfiguration {

        @Bean
        ActionProcessor stubActionProcessor() {
            // Never invoked in this wiring assertion; present only so SendCommunicationBinding forms.
            return envelope -> ActionResult.processedResult();
        }

        @Bean
        EnvelopeReconstructor envelopeReconstructor() {
            return new EnvelopeReconstructor();
        }

        @Bean
        SendCommunicationBinding sendCommunicationBinding(ActionProcessor actionProcessor) {
            return new SendCommunicationBinding(actionProcessor);
        }

        @Bean
        HandlerRegistry handlerRegistry(java.util.List<InboxHandler> handlers) {
            return new HandlerRegistry(handlers);
        }

        @Bean
        DispatchingHandler dispatchingHandler(HandlerRegistry registry,
                                              EnvelopeReconstructor reconstructor) {
            return new DispatchingHandler(registry, reconstructor);
        }

        @Bean
        InboundRecordingService inboundRecordingService(IdempotentInbox inbox,
                                                        Consumer<InboundMessage> inboxHandler) {
            return new InboundRecordingService(inbox, inboxHandler);
        }
    }

    // Req 2.1: the sole Consumer<InboundMessage> seam bean is the DispatchingHandler, and the
    // InboundRecordingService is wired with exactly that instance (not the messaging base no-op).
    @Test
    void inboundRecordingServiceIsWiredWithDispatchingHandlerAsTheSeam() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();

            // The real starter inbox bean formed (so this is genuine wiring, not a mock).
            assertThat(context).hasSingleBean(IdempotentInbox.class);

            // (1) The primary seam bean type resolvable as Consumer<InboundMessage> is the dispatcher.
            DispatchingHandler dispatcher = context.getBean(DispatchingHandler.class);
            assertThat(context).hasSingleBean(DispatchingHandler.class);

            @SuppressWarnings("unchecked")
            Consumer<InboundMessage> seam =
                    (Consumer<InboundMessage>) context.getBean(Consumer.class);
            assertThat(seam)
                    .as("the Consumer<InboundMessage> inbox seam bean is the DispatchingHandler")
                    .isSameAs(dispatcher);

            // (2) The InboundRecordingService actually holds that dispatcher as its injected seam.
            InboundRecordingService service = context.getBean(InboundRecordingService.class);
            Consumer<InboundMessage> injectedSeam = extractInboxHandler(service);
            assertThat(injectedSeam)
                    .as("InboundRecordingService is wired with the DispatchingHandler seam (Req 2.1)")
                    .isSameAs(dispatcher);
        });
    }

    /**
     * Reflectively read the private {@code inboxHandler} seam the service was constructed with.
     * {@code InboundRecordingService} is {@code @Transactional}, so Spring may hand back a proxy
     * whose declared field is empty — unwrap to the target instance first so we read the real seam.
     */
    @SuppressWarnings("unchecked")
    private static Consumer<InboundMessage> extractInboxHandler(InboundRecordingService service)
            throws Exception {
        InboundRecordingService target = AopTestUtils.getTargetObject(service);
        Field field = InboundRecordingService.class.getDeclaredField("inboxHandler");
        field.setAccessible(true);
        return (Consumer<InboundMessage>) field.get(target);
    }
}
