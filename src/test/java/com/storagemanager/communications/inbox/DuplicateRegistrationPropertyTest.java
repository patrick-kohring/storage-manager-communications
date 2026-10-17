package com.storagemanager.communications.inbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.messaging.EventEnvelope;

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
 * <b>Correctness Property 7 — Duplicate registration fails startup naming the conflicting
 * eventType</b>.
 *
 * <p><b>Feature: inbox-action-dispatch, Property 7: Duplicate registration fails startup naming the
 * conflicting eventType</b> — for any list of {@link InboxHandler}s that contains at least two
 * handlers declaring the same {@code eventType}, constructing the {@link HandlerRegistry} fails with
 * a {@link DuplicateHandlerRegistrationException} whose message contains the conflicting
 * {@code eventType} value; and for any list of handlers whose {@code eventType}s are all distinct,
 * construction succeeds and every handler is retrievable by its {@code eventType} via
 * {@link HandlerRegistry#lookup(String)}.
 *
 * <p><b>Validates: Requirements 1.4, 1.6</b>
 *
 * <h2>Approach</h2>
 * This is a <em>pure</em> property (no Spring context, no database): the collision check lives in the
 * {@link HandlerRegistry} constructor (Req 1.6), so it is exercised directly with
 * jqwik-generated {@link InboxHandler} stubs. Two generators drive the two arms:
 * <ul>
 *   <li><b>duplicate lists</b> — a base list of distinct-eventType stubs with at least one extra
 *       stub re-using an eventType already present, shuffled so the collision is not always adjacent;
 *       constructing the registry must throw and the message must name the conflicting value.</li>
 *   <li><b>all-distinct lists</b> — every stub declares a unique eventType; construction must succeed
 *       and {@code lookup} must return each handler by its own eventType (Req 1.4).</li>
 * </ul>
 */
@PropertyDefaults(tries = 100, generation = GenerationMode.RANDOMIZED)
@Label("Feature: inbox-action-dispatch, Property 7: Duplicate registration fails startup naming the conflicting eventType (Validates: Requirements 1.4, 1.6)")
class DuplicateRegistrationPropertyTest {

    /**
     * Feature: inbox-action-dispatch, Property 7 (duplicate arm): a list containing at least two
     * handlers sharing an {@code eventType} makes the {@link HandlerRegistry} constructor throw a
     * {@link DuplicateHandlerRegistrationException} whose message contains the conflicting value.
     *
     * <p><b>Validates: Requirements 1.6</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void duplicateEventTypeFailsConstructionNamingTheConflictingValue(
            @ForAll("duplicateHandlerLists") DuplicateCase testCase) {

        assertThatThrownBy(() -> new HandlerRegistry(testCase.handlers()))
                .as("constructing a registry with a duplicate eventType must fail")
                .isInstanceOf(DuplicateHandlerRegistrationException.class)
                .hasMessageContaining(testCase.conflictingEventType());
    }

    /**
     * Feature: inbox-action-dispatch, Property 7 (distinct arm): a list whose {@code eventType}s are
     * all distinct constructs successfully and every handler is retrievable by its {@code eventType}.
     *
     * <p><b>Validates: Requirements 1.4</b>
     */
    @Property(tries = 100, generation = GenerationMode.RANDOMIZED)
    void allDistinctEventTypesConstructAndAreRetrievable(
            @ForAll("distinctHandlerLists") List<StubHandler> handlers) {

        HandlerRegistry registry = new HandlerRegistry(new ArrayList<>(handlers));

        for (StubHandler handler : handlers) {
            assertThat(registry.lookup(handler.eventType()))
                    .as("handler for eventType '%s' is retrievable", handler.eventType())
                    .containsSame(handler);
        }

        // An eventType nobody declared has no handler (Req 1.4 keying semantics).
        assertThat(registry.lookup("__no_such_event_type__"))
                .as("an unregistered eventType yields empty")
                .isEmpty();
    }

    // =====================================================================
    // Generators
    // =====================================================================

    /**
     * A list of stubs whose eventTypes are all distinct. Size 0..6; each eventType is a distinct
     * non-blank token so no accidental collision occurs.
     */
    @Provide
    Arbitrary<List<StubHandler>> distinctHandlerLists() {
        return distinctEventTypes(0, 6)
                .map(DuplicateRegistrationPropertyTest::toStubs);
    }

    /**
     * A list that is guaranteed to contain at least two handlers sharing one eventType. We draw a
     * base set of distinct eventTypes (at least one), pick one of them as the conflicting value, add
     * a second handler re-using it, then shuffle so the duplicate is not always adjacent.
     */
    @Provide
    Arbitrary<DuplicateCase> duplicateHandlerLists() {
        Arbitrary<List<String>> bases = distinctEventTypes(1, 5);
        return bases.flatMap(base -> {
            Arbitrary<Integer> conflictIndex = Arbitraries.integers().between(0, base.size() - 1);
            // 1..3 extra copies of the chosen conflicting eventType, so the duplicate may appear
            // more than twice.
            Arbitrary<Integer> extraCopies = Arbitraries.integers().between(1, 3);
            Arbitrary<Long> seeds = Arbitraries.longs();
            return Combinators.combine(conflictIndex, extraCopies, seeds)
                    .as((idx, copies, seed) -> {
                        String conflicting = base.get(idx);
                        List<String> eventTypes = new ArrayList<>(base);
                        for (int i = 0; i < copies; i++) {
                            eventTypes.add(conflicting);
                        }
                        Collections.shuffle(eventTypes, new java.util.Random(seed));
                        return duplicateCase(eventTypes, conflicting);
                    });
        });
    }

    /** A list of {@code min..max} pairwise-distinct non-blank eventType tokens. */
    private Arbitrary<List<String>> distinctEventTypes(int min, int max) {
        Arbitrary<String> token = Arbitraries.strings()
                .withCharRange('a', 'z')
                .ofMinLength(1)
                .ofMaxLength(12);
        return token.list().ofMinSize(min).ofMaxSize(max).uniqueElements();
    }

    private static List<StubHandler> toStubs(List<String> eventTypes) {
        List<StubHandler> stubs = new ArrayList<>(eventTypes.size());
        for (String eventType : eventTypes) {
            stubs.add(new StubHandler(eventType));
        }
        return stubs;
    }

    /**
     * Build a {@link DuplicateCase}, asserting the generator genuinely produced the claimed
     * collision so the property cannot pass vacuously.
     */
    private static DuplicateCase duplicateCase(List<String> eventTypes, String conflictingEventType) {
        Set<String> seen = new HashSet<>();
        boolean collides = false;
        for (String eventType : eventTypes) {
            if (!seen.add(eventType)) {
                collides = true;
            }
        }
        if (!collides) {
            throw new IllegalStateException("generator produced a non-colliding duplicate case");
        }
        List<InboxHandler> handlers = new ArrayList<>(toStubs(eventTypes));
        return new DuplicateCase(List.copyOf(handlers), conflictingEventType);
    }

    // =====================================================================
    // Fixtures
    // =====================================================================

    /** A duplicate-list case bundling the handler list with the eventType known to collide. */
    record DuplicateCase(List<InboxHandler> handlers, String conflictingEventType) {
    }

    /** A minimal {@link InboxHandler} stub declaring a single eventType; {@code handle} is a no-op. */
    static final class StubHandler implements InboxHandler {
        private final String eventType;

        StubHandler(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public String eventType() {
            return eventType;
        }

        @Override
        public void handle(EventEnvelope envelope) {
            // no-op: Property 7 only exercises registration/lookup, never dispatch
        }
    }
}
