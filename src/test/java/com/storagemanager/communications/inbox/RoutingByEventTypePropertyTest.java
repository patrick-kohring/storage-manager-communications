package com.storagemanager.communications.inbox;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.messaging.EventEnvelope.ActorType;
import com.storagemanager.messaging.api.InboundMessage;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.PropertyDefaults;
import net.jqwik.api.Provide;

/**
 * jqwik property-based test for the design's
 * <b>Correctness Property 1 — Routing is by eventType only, invokes exactly the matched handler
 * exactly once</b> (inbox-action-dispatch spec, Task 6.1).
 *
 * <p><b>Feature: inbox-action-dispatch, Property 1: Routing is by eventType only, invokes exactly
 * the matched handler exactly once</b> — for any set of registered {@link InboxHandler}s with
 * distinct {@code eventType}s and for any {@link InboundMessage}, the {@link DispatchingHandler}
 * invokes the handler whose declared {@code eventType} equals the message {@code eventType} exactly
 * once and invokes no other registered handler; when no registered handler matches the message
 * {@code eventType}, it invokes no handler at all; and for two messages that share an
 * {@code eventType} but differ arbitrarily in payload, scope, correlation id, and headers, the
 * selected handler is identical (selection never inspects the payload).
 *
 * <p><b>Validates: Requirements 2.2, 2.3, 2.4, 6.3</b>
 *
 * <h2>Approach</h2>
 * This is a <em>pure</em> property (no Spring context, no database). The {@link DispatchingHandler}
 * is built directly in-test from a real {@link HandlerRegistry} (over generated counting stub
 * handlers with distinct {@code eventType}s) and a real {@link EnvelopeReconstructor}. Counting
 * stubs increment a per-handler counter on every {@code handle(...)} call. Three arms drive the
 * three facets of the property:
 * <ul>
 *   <li><b>matched</b> — the message carries a registered {@code eventType} (and a valid scope +
 *       headers so reconstruction succeeds and the handler is actually invoked): the matched stub's
 *       count is exactly 1 and every other stub's count is 0 (Req 2.2, 2.3);</li>
 *   <li><b>unmatched</b> — the message carries an {@code eventType} no handler declares: every
 *       stub's count stays 0 (Req 6.3);</li>
 *   <li><b>payload-independence</b> — two messages share the matched {@code eventType} but differ
 *       arbitrarily in payload, scope, correlationId, and headers: the same handler is selected in
 *       both, so selection never inspects the payload (Req 2.4).</li>
 * </ul>
 */
@PropertyDefaults(tries = 100, generation = GenerationMode.RANDOMIZED)
@Label("Feature: inbox-action-dispatch, Property 1: Routing is by eventType only, invokes exactly the matched handler exactly once (Validates: Requirements 2.2, 2.3, 2.4, 6.3)")
class RoutingByEventTypePropertyTest {

    /**
     * Feature: inbox-action-dispatch, Property 1 (matched arm): routing a message whose
     * {@code eventType} is registered invokes exactly the matched handler exactly once and no other
     * registered handler.
     *
     * <p><b>Validates: Requirements 2.2, 2.3, 6.3</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void matchedMessageInvokesExactlyTheMatchedHandlerOnce(@ForAll("routingCases") RoutingCase routingCase) {

        List<CountingStubHandler> handlers = routingCase.handlers();
        CountingStubHandler target = handlers.get(routingCase.targetIndex());

        DispatchingHandler dispatcher = newDispatcher(handlers);

        // A message carrying the target's registered eventType, with a valid scope + headers so
        // reconstruction succeeds and the matched handler is genuinely invoked.
        InboundMessage message = routingCase.messageWithEventType(target.eventType());

        dispatcher.accept(message);

        // Req 2.3: the matched handler is invoked exactly once.
        assertThat(target.count())
                .as("matched handler for eventType '%s' is invoked exactly once", target.eventType())
                .isEqualTo(1);

        // Req 2.2: no other registered handler is invoked.
        for (CountingStubHandler handler : handlers) {
            if (handler != target) {
                assertThat(handler.count())
                        .as("non-matched handler for eventType '%s' is never invoked",
                                handler.eventType())
                        .isZero();
            }
        }
    }

    /**
     * Feature: inbox-action-dispatch, Property 1 (unmatched arm): routing a message whose
     * {@code eventType} no registered handler declares invokes no handler at all.
     *
     * <p><b>Validates: Requirements 6.3</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void unmatchedMessageInvokesNoHandler(@ForAll("routingCases") RoutingCase routingCase) {

        List<CountingStubHandler> handlers = routingCase.handlers();
        DispatchingHandler dispatcher = newDispatcher(handlers);

        // An eventType guaranteed absent from the registered set.
        InboundMessage message = routingCase.messageWithEventType(routingCase.unmatchedEventType());

        dispatcher.accept(message);

        // Req 6.3: no registered handler is invoked for an unmatched eventType.
        for (CountingStubHandler handler : handlers) {
            assertThat(handler.count())
                    .as("handler for eventType '%s' is not invoked for an unmatched message",
                            handler.eventType())
                    .isZero();
        }
    }

    /**
     * Feature: inbox-action-dispatch, Property 1 (payload-independence arm): two messages that share
     * the matched {@code eventType} but differ arbitrarily in payload, scope, correlationId, and
     * headers select the identical handler — selection never inspects the payload.
     *
     * <p><b>Validates: Requirements 2.4</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void selectionIsByEventTypeOnlyIndependentOfPayload(@ForAll("routingCases") RoutingCase routingCase) {

        List<CountingStubHandler> handlers = routingCase.handlers();
        CountingStubHandler target = handlers.get(routingCase.targetIndex());

        // Two independent dispatchers over equivalent (but separately-counting) stub sets so each
        // message's selection is observed in isolation.
        List<CountingStubHandler> handlersA = copyOfStubs(handlers);
        List<CountingStubHandler> handlersB = copyOfStubs(handlers);
        DispatchingHandler dispatcherA = newDispatcher(handlersA);
        DispatchingHandler dispatcherB = newDispatcher(handlersB);

        // Same eventType, arbitrarily different payload/scope/correlationId/headers.
        InboundMessage messageA = routingCase.messageWithEventType(target.eventType());
        InboundMessage messageB = routingCase.variantMessageWithEventType(target.eventType());

        dispatcherA.accept(messageA);
        dispatcherB.accept(messageB);

        int selectedA = indexOfInvoked(handlersA);
        int selectedB = indexOfInvoked(handlersB);

        // Req 2.4: identical selection despite differing non-eventType fields.
        assertThat(selectedA)
                .as("selection is identical for two messages sharing an eventType but differing in "
                        + "payload/scope/correlationId/headers")
                .isEqualTo(selectedB);
        assertThat(selectedA)
                .as("the selected handler is the one declaring the shared eventType")
                .isEqualTo(routingCase.targetIndex());
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static DispatchingHandler newDispatcher(List<CountingStubHandler> handlers) {
        List<InboxHandler> asHandlers = new ArrayList<>(handlers);
        return new DispatchingHandler(new HandlerRegistry(asHandlers), new EnvelopeReconstructor());
    }

    private static List<CountingStubHandler> copyOfStubs(List<CountingStubHandler> handlers) {
        List<CountingStubHandler> copy = new ArrayList<>(handlers.size());
        for (CountingStubHandler handler : handlers) {
            copy.add(new CountingStubHandler(handler.eventType()));
        }
        return copy;
    }

    /** @return the index of the single invoked stub, or -1 if none was invoked. */
    private static int indexOfInvoked(List<CountingStubHandler> handlers) {
        int found = -1;
        for (int i = 0; i < handlers.size(); i++) {
            if (handlers.get(i).count() > 0) {
                if (found != -1) {
                    throw new IllegalStateException("more than one handler was invoked");
                }
                found = i;
            }
        }
        return found;
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /**
     * A routing case: a set of distinct-eventType counting stubs, the index of the target handler to
     * route to, an eventType guaranteed absent from the set (the unmatched arm), and the raw material
     * to build messages carrying any chosen eventType.
     */
    @Provide
    Arbitrary<RoutingCase> routingCases() {
        Arbitrary<List<String>> distinctEventTypes = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12)
                .list()
                .ofMinSize(1)
                .ofMaxSize(6)
                .uniqueElements();

        return distinctEventTypes.flatMap(eventTypes -> {
            Arbitrary<Integer> targetIndex = Arbitraries.integers().between(0, eventTypes.size() - 1);
            Arbitrary<UUID> organizationIds = Arbitraries.create(UUID::randomUUID);
            return Combinators.combine(targetIndex, organizationIds)
                    .as((idx, organizationId) -> {
                        List<CountingStubHandler> handlers = new ArrayList<>(eventTypes.size());
                        for (String eventType : eventTypes) {
                            handlers.add(new CountingStubHandler(eventType));
                        }
                        // An eventType that cannot collide with any generated token: the token
                        // charset is 'a'..'z', so a value containing other characters is unmatched.
                        String unmatched = "__unmatched__" + UUID.randomUUID();
                        return new RoutingCase(handlers, idx, unmatched, organizationId);
                    });
        });
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    /**
     * A routing fixture bundling the registered stubs, the target handler index, a guaranteed-absent
     * eventType, and an organizationId used to build valid messages.
     */
    static final class RoutingCase {
        private final List<CountingStubHandler> handlers;
        private final int targetIndex;
        private final String unmatchedEventType;
        private final UUID organizationId;

        RoutingCase(List<CountingStubHandler> handlers, int targetIndex, String unmatchedEventType,
                UUID organizationId) {
            this.handlers = handlers;
            this.targetIndex = targetIndex;
            this.unmatchedEventType = unmatchedEventType;
            this.organizationId = organizationId;
        }

        List<CountingStubHandler> handlers() {
            return handlers;
        }

        int targetIndex() {
            return targetIndex;
        }

        String unmatchedEventType() {
            return unmatchedEventType;
        }

        /**
         * A message carrying {@code eventType} with a valid scope ({@code organization_id} present)
         * and the required metadata headers, so reconstruction succeeds and a matched handler is
         * genuinely invoked.
         */
        InboundMessage messageWithEventType(String eventType) {
            LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
            scope.put("organization_id", organizationId);
            scope.put("facility_id", UUID.randomUUID());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("field", "value");

            return new InboundMessage(
                    UUID.randomUUID(), eventType, payload, scope, UUID.randomUUID(), validHeaders());
        }

        /**
         * A second message carrying the same {@code eventType} but differing arbitrarily in payload,
         * scope, correlationId, and headers — used to assert payload-independent selection (Req 2.4).
         */
        InboundMessage variantMessageWithEventType(String eventType) {
            LinkedHashMap<String, UUID> scope = new LinkedHashMap<>();
            // Different organization scope, no facility scope (null preserved).
            scope.put("organization_id", UUID.randomUUID());
            scope.put("facility_id", null);

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("totally", "different");
            payload.put("count", 42);

            LinkedHashMap<String, String> headers = validHeaders();
            headers.put("schema_version", "2");
            headers.put("entity_type", "Tenant");
            headers.put("actor_type", ActorType.USER.name());
            headers.put("actor_id", UUID.randomUUID().toString());

            // Null correlationId, different everything else; same eventType only.
            return new InboundMessage(UUID.randomUUID(), eventType, payload, scope, null, headers);
        }
    }

    /** The required metadata headers, mirroring InboundRecordingService.toInboundMessage. */
    private static LinkedHashMap<String, String> validHeaders() {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put("schema_version", "1");
        headers.put("occurred_at", Instant.now().truncatedTo(ChronoUnit.MICROS).toString());
        headers.put("entity_type", "Lead");
        headers.put("entity_id", UUID.randomUUID().toString());
        headers.put("actor_type", ActorType.SYSTEM.name());
        return headers;
    }

    /**
     * A minimal {@link InboxHandler} stub declaring a single {@code eventType} that counts every
     * {@code handle(...)} invocation so the routing property can assert exactly-once dispatch.
     */
    static final class CountingStubHandler implements InboxHandler {
        private final String eventType;
        private final AtomicInteger count = new AtomicInteger();

        CountingStubHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(EventEnvelope envelope) {
            count.incrementAndGet();
        }

        int count() {
            return count.get();
        }
    }
}
