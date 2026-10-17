package com.storagemanager.communications.action.lead;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * In-memory stub {@link LeadReadPort} for testing and local development (Requirement 8.2).
 *
 * <p>Backs the read seam with a {@link ConcurrentHashMap} of configured {@link LeadView} instances
 * keyed by lead id. It makes <strong>no</strong> calls to {@code storage-manager-api}: it returns
 * only the lead data that tests (or local wiring) have seeded via {@link #register(UUID, LeadView)}.
 * Unknown lead ids resolve to {@link Optional#empty()}, which the {@code ActionProcessor} treats as
 * a terminal lead-not-found rejection (Requirement 8.3).
 *
 * <p><strong>Profile registration.</strong> This stub is registered under the {@code local},
 * {@code test}, and {@code default} profiles so that it is the only active {@link LeadReadPort}
 * implementation in this spec — tests and @SpringBootTest slices run under the {@code default}
 * profile, and local runs activate the {@code local} profile. The live service-to-service
 * implementation against {@code storage-manager-api} is a cross-repository assumption (A3) and,
 * when it exists, will be registered under the non-local runtime profile instead of this stub.
 */
@Component
@Profile({"local", "test", "default"})
public class StubLeadReadPort implements LeadReadPort {

    private final Map<UUID, LeadView> leadsById = new ConcurrentHashMap<>();

    /**
     * Seeds the stub with a {@link LeadView} for the given lead id, replacing any previously
     * configured view for that id. Intended for tests and local wiring to configure lead data
     * before exercising the {@code ActionProcessor}.
     *
     * @param leadId   the lead id to configure; never {@code null}
     * @param leadView the lead view to return for that id; never {@code null}
     * @return this port, to allow fluent seeding of multiple leads
     */
    public StubLeadReadPort register(UUID leadId, LeadView leadView) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        Objects.requireNonNull(leadView, "leadView must not be null");
        leadsById.put(leadId, leadView);
        return this;
    }

    /**
     * Removes all configured lead data. Useful for isolating tests that share a stub instance.
     */
    public void clear() {
        leadsById.clear();
    }

    @Override
    public Optional<LeadView> findLead(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        return Optional.ofNullable(leadsById.get(leadId));
    }
}
