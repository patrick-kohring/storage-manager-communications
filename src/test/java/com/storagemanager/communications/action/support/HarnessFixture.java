package com.storagemanager.communications.action.support;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storagemanager.communications.action.ActionProcessor;
import com.storagemanager.communications.action.DefaultActionProcessor;
import com.storagemanager.communications.service.TemplatePersonalizer;

/**
 * One-stop assembly of a {@link DefaultActionProcessor} wired to the shared harness fakes, for the
 * {@code ActionProcessor} property tests (lead-communication-action spec).
 *
 * <p>Bundles the four collaborators the processor needs behind an in-memory, DB-free facade:
 * <ul>
 *   <li>{@link FakeCommunicationTemplateRepository} — seed-able template store;</li>
 *   <li>{@link FakeEntityPropertyMappingRepository} — seed-able {@code LEAD} placeholder mappings;</li>
 *   <li>{@link ConfigurableLeadReadPort} — seed-able / throwable lead read seam;</li>
 *   <li>{@link CountingSendSeam} — counting + capturing (+ optionally throwing) send seam.</li>
 * </ul>
 * The real {@link TemplatePersonalizer} (a pure function) and a plain {@link ObjectMapper} are used
 * so the full resolve-personalize-dispatch pipeline runs end-to-end against the fakes.
 *
 * <p>Typical use:
 * <pre>{@code
 * HarnessFixture harness = new HarnessFixture();
 * harness.installSuccessfulCase(generatedCase);      // seeds template + mappings + lead
 * ActionResult result = harness.processor().process(generatedCase.envelope());
 * assertThat(harness.send().invocationCount()).isEqualTo(1);
 * }</pre>
 *
 * <p>Each component is also exposed directly (via {@link #templates()}, {@link #mappings()},
 * {@link #leads()}, {@link #send()}) so a test that needs bespoke seeding — missing template,
 * throwing port, etc. — can configure exactly what it needs.
 */
public final class HarnessFixture {

    private final FakeCommunicationTemplateRepository templates = new FakeCommunicationTemplateRepository();
    private final FakeEntityPropertyMappingRepository mappings = new FakeEntityPropertyMappingRepository();
    private final ConfigurableLeadReadPort leads = new ConfigurableLeadReadPort();
    private final CountingSendSeam send = new CountingSendSeam();
    private final ActionProcessor processor;

    public HarnessFixture() {
        this.processor = new DefaultActionProcessor(
                new ObjectMapper(),
                leads,
                templates.mock(),
                mappings.mock(),
                new TemplatePersonalizer(),
                send.mock());
    }

    public ActionProcessor processor() {
        return processor;
    }

    public FakeCommunicationTemplateRepository templates() {
        return templates;
    }

    public FakeEntityPropertyMappingRepository mappings() {
        return mappings;
    }

    public ConfigurableLeadReadPort leads() {
        return leads;
    }

    public CountingSendSeam send() {
        return send;
    }

    /**
     * Seeds everything required for a generated {@link SuccessfulActionCase} to process successfully:
     * the resolving template at its scope, the {@code LEAD} placeholder mappings, and the lead view.
     *
     * @param testCase the fully-valid case produced by {@link ActionArbitraries#successfulActionCases()}
     */
    public void installSuccessfulCase(SuccessfulActionCase testCase) {
        // Seed the resolving template at the correct scope.
        if (testCase.templateIsFacility()) {
            templates.seedFacilityTemplate(
                    testCase.leadView().organizationId(),
                    testCase.leadView().facilityId(),
                    testCase.templateName(),
                    testCase.emailContent(),
                    testCase.smsContent());
        } else {
            templates.seedOrgTemplate(
                    testCase.leadView().organizationId(),
                    testCase.templateName(),
                    testCase.emailContent(),
                    testCase.smsContent());
        }

        // Seed the LEAD placeholder -> jsonProperty mappings.
        for (Map.Entry<String, String> entry : testCase.placeholderToJsonProperty().entrySet()) {
            mappings.seedLeadMapping(entry.getKey(), entry.getValue());
        }

        // Seed the resolvable lead.
        leads.register(testCase.leadId(), testCase.leadView());
    }
}
