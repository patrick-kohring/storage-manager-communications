/**
 * Shared test-support harness for the {@code ActionProcessor} property tests of the
 * {@code lead-communication-action} spec.
 *
 * <p>This package is <strong>reusable test infrastructure</strong>: it is established by Task 7.13
 * (Property 1) and is designed so every sibling {@code ActionProcessor} property test —
 * 7.2 (correlation id), 7.3 (structurally invalid), 7.5 (single recipient), 7.7 (template scope),
 * 7.8 (missing lead), 7.10 (recipient/channel), 7.11 (missing content/contact), 7.14 (retryable),
 * and 7.15 (zero-send) — can reuse the same fakes and generators rather than re-deriving them.
 *
 * <h2>Components</h2>
 * <ul>
 *   <li>{@link com.storagemanager.communications.action.support.FakeCommunicationTemplateRepository}
 *       — in-memory, Mockito-backed template store exposing the two scope finders
 *       ({@code findByOrganizationIdAndFacilityIdAndTemplateName} and
 *       {@code findByOrganizationIdAndFacilityIdIsNullAndTemplateName}) plus {@code save}/seed.</li>
 *   <li>{@link com.storagemanager.communications.action.support.FakeEntityPropertyMappingRepository}
 *       — in-memory, Mockito-backed mapping store exposing
 *       {@code findByEntityTypeOrderByCreatedAtDesc(EntityType, Pageable)} (returning a {@code Page})
 *       plus seed, used to build the {@code LEAD} placeholder→jsonProperty map.</li>
 *   <li>{@link com.storagemanager.communications.action.support.ConfigurableLeadReadPort}
 *       — in-memory {@code LeadReadPort} that can seed a {@code LeadView} per id and can be made to
 *       throw {@code LeadReadException} (for the retryable-read-failure property).</li>
 *   <li>{@link com.storagemanager.communications.action.support.CountingSendSeam}
 *       — a Mockito stub of {@code SendService} whose {@code sendInternal(...)} counts invocations,
 *       captures each {@code SendCommunicationRequest}, returns a dummy {@code SentCommunication}
 *       (no DB/transaction), and can optionally throw to simulate a dispatch failure.</li>
 *   <li>{@link com.storagemanager.communications.action.support.ActionEnvelopes}
 *       — builds {@code SendCommunication} {@code EventEnvelope}s from a payload map or from
 *       individual action fields, with configurable {@code eventId}/{@code correlationId}.</li>
 *   <li>{@link com.storagemanager.communications.action.support.ActionArbitraries}
 *       — reusable jqwik {@code Arbitrary}s: valid LEAD/explicit actions, lead views, templates with
 *       or without email/sms content, email/phone/junk literals, present/null correlation ids, and
 *       the headline {@code successfulActionCases()} provider that yields a fully-valid
 *       {@link com.storagemanager.communications.action.support.SuccessfulActionCase}.</li>
 *   <li>{@link com.storagemanager.communications.action.support.HarnessFixture}
 *       — one-stop assembly of a {@code DefaultActionProcessor} wired to all four fakes, with
 *       {@code installSuccessfulCase(...)} to seed a generated happy-path case.</li>
 * </ul>
 *
 * <h2>Reuse pattern</h2>
 * <pre>{@code
 * HarnessFixture harness = new HarnessFixture();
 * harness.installSuccessfulCase(generatedCase);
 * ActionResult result = harness.processor().process(generatedCase.envelope());
 * assertThat(harness.send().invocationCount()).isEqualTo(1);
 * }</pre>
 *
 * Rejection-oriented siblings instead seed only part of the fixture (for example a missing template
 * or a throwing port) and assert {@code harness.send().invocationCount() == 0}.
 */
package com.storagemanager.communications.action.support;
