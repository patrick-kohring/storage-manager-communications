package com.storagemanager.communications.action.lead;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.storagemanager.communications.model.MessageType;

/**
 * Spring context smoke test confirming the {@link LeadReadPort} wiring under the test/default
 * profile (Requirement 8.2).
 *
 * <p>Boots the full application via a bare {@link SpringBootTest} (the same lightweight pattern as
 * {@code StorageManagerCommunicationsApplicationTests}, driven by the test
 * {@code application.properties} default profile) and asserts that:
 * <ul>
 *   <li>the injected {@link LeadReadPort} bean is the in-memory {@link StubLeadReadPort} — the only
 *       active implementation in this spec (the live {@code storage-manager-api} adapter is the
 *       cross-repository assumption A3);</li>
 *   <li>a {@link LeadView} seeded via {@link StubLeadReadPort#register(UUID, LeadView)} is returned
 *       by {@link LeadReadPort#findLead(UUID)};</li>
 *   <li>an unknown lead id resolves to {@link Optional#empty()}.</li>
 * </ul>
 *
 * <p>This is a wiring/smoke check only; recipient resolution, template resolution, and dispatch are
 * exercised by the dedicated property and integration tests.
 *
 * <p>Validates: Requirement 8.2
 */
@SpringBootTest
class StubLeadReadPortWiringSmokeTest {

    @Autowired
    private LeadReadPort leadReadPort;

    @Test
    void leadReadPortBeanIsTheStubImplementation() {
        assertThat(leadReadPort).isInstanceOf(StubLeadReadPort.class);
    }

    @Test
    void stubReturnsConfiguredLeadAndEmptyForUnknownId() {
        UUID leadId = UUID.randomUUID();
        LeadView configured = new LeadView(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "lead@example.com",
                "+15551234567",
                MessageType.EMAIL,
                Map.of("firstName", "Dana"));

        StubLeadReadPort stub = (StubLeadReadPort) leadReadPort;
        stub.clear();
        stub.register(leadId, configured);

        assertThat(leadReadPort.findLead(leadId)).contains(configured);
        assertThat(leadReadPort.findLead(UUID.randomUUID())).isEmpty();
    }
}
