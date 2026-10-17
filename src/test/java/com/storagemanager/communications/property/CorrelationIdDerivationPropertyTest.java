package com.storagemanager.communications.property;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import com.storagemanager.communications.action.ActionResult;
import com.storagemanager.communications.action.support.ActionArbitraries;
import com.storagemanager.communications.action.support.ActionEnvelopes;
import com.storagemanager.communications.action.support.HarnessFixture;
import com.storagemanager.communications.action.support.SuccessfulActionCase;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.SendCommunicationRequest;

import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test for the correlation-id-derivation behavior of the {@code ActionProcessor}
 * (lead-communication-action spec, Task 7.2).
 *
 * <p>Feature: lead-communication-action, Property 11: Correlation id derivation
 *
 * <p>For any {@code EventEnvelope}, the correlation id the processor derives and carries to the send
 * equals the envelope's {@code correlationId} when it is non-null and the envelope's {@code eventId}
 * when the {@code correlationId} is null.
 *
 * <p>The processor is driven against the shared harness
 * ({@link com.storagemanager.communications.action.support}): fake template / mapping repositories,
 * a configurable {@code LeadReadPort}, and a counting/capturing send seam. Each case starts from a
 * fully-valid {@link SuccessfulActionCase} (so a send actually happens and the captured request's
 * {@code correlationId} can be inspected), but its envelope is rebuilt with a <em>controlled</em>
 * {@code eventId} and a {@code correlationId} that is deliberately either a fresh, distinct UUID or
 * {@code null}. The two ids are generated distinct so the assertion unambiguously distinguishes the
 * present-correlation case from the null-fallback case.
 *
 * <p>Validates: Requirements 2.2
 */
class CorrelationIdDerivationPropertyTest {

    /**
     * For any envelope, the correlation id carried to the send equals {@code correlationId} when it
     * is non-null, otherwise {@code eventId}.
     *
     * <p>Validates: Requirements 2.2
     */
    @Property(tries = 100)
    void correlationId_derivedFromEnvelopeCorrelationIdElseEventId(
            @ForAll("correlationCases") CorrelationCase testCase) {

        HarnessFixture harness = new HarnessFixture();
        harness.installSuccessfulCase(testCase.base());

        ActionResult result = harness.processor().process(testCase.envelope());

        // The action still processes successfully (so a single send happens and we can inspect it).
        assertThat(result).as("case still processes to a send").isNotNull();
        assertThat(result.processed()).as("action reported as processed").isTrue();
        assertThat(harness.send().invocationCount())
                .as("exactly one send so the carried correlation id is observable")
                .isEqualTo(1);

        SendCommunicationRequest request = harness.send().singleRequest();

        UUID expected = testCase.correlationId() != null
                ? testCase.correlationId()
                : testCase.eventId();

        assertThat(request.correlationId())
                .as("derived correlation id is the envelope correlationId when non-null, else the eventId")
                .isEqualTo(expected);
    }

    @Provide
    Arbitrary<CorrelationCase> correlationCases() {
        Arbitrary<SuccessfulActionCase> baseArb = ActionArbitraries.successfulActionCases();
        // hasCorrelationId toggles the present-vs-null correlationId so both branches of Req 2.2 run.
        Arbitrary<Boolean> hasCorrelationIdArb = net.jqwik.api.Arbitraries.of(true, false);

        return Combinators.combine(baseArb, hasCorrelationIdArb).as((base, hasCorrelationId) -> {
            // Rebuild the envelope with a controlled eventId and correlationId, reusing the base
            // case's valid payload so the action still resolves, personalizes, and sends.
            Map<String, Object> payload = base.envelope().payload();
            UUID eventId = UUID.randomUUID();
            // Keep correlationId distinct from eventId so the two branches are distinguishable.
            UUID correlationId = hasCorrelationId ? UUID.randomUUID() : null;
            EventEnvelope controlled =
                    ActionEnvelopes.envelopeWithPayload(payload, eventId, correlationId);
            return new CorrelationCase(base, controlled, eventId, correlationId);
        });
    }

    /**
     * A fully-valid base case whose envelope has been rebuilt with a controlled {@code eventId} and
     * a {@code correlationId} that is either a distinct UUID or {@code null}.
     *
     * @param base          the fully-valid case supplying the seedable payload / lead / template
     * @param envelope      the rebuilt envelope carrying the controlled ids
     * @param eventId       the controlled envelope {@code eventId}
     * @param correlationId the controlled envelope {@code correlationId}, or {@code null}
     */
    record CorrelationCase(SuccessfulActionCase base, EventEnvelope envelope, UUID eventId,
                           UUID correlationId) {
    }
}
