package com.storagemanager.communications.action.support;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.storagemanager.communications.action.lead.LeadReadException;
import com.storagemanager.communications.action.lead.LeadReadPort;
import com.storagemanager.communications.action.lead.LeadView;

/**
 * Shared configurable {@link LeadReadPort} for the {@code ActionProcessor} property-test harness
 * (lead-communication-action spec).
 *
 * <p>Extends the production {@link com.storagemanager.communications.action.lead.StubLeadReadPort}
 * notion with two extra test capabilities the sibling property tests need:
 * <ul>
 *   <li><strong>seed a {@link LeadView}</strong> per lead id (Req 8.1/8.2) — unknown ids resolve to
 *       {@link Optional#empty()}, exercising the lead-not-found rejection (Req 8.3, Property 8);</li>
 *   <li><strong>make the port throw a {@link LeadReadException}</strong> for a given id (or for all
 *       reads) so the retryable-read-failure branch (Req 8.4, Property 9) can be driven.</li>
 * </ul>
 *
 * <p>The real {@code StubLeadReadPort} is intentionally not subclassed (it is {@code final}-ish in
 * spirit and lives under {@code main}); this hand-rolled fake mirrors its in-memory behavior while
 * adding the throw seam, keeping the harness self-contained.
 *
 * <p>Usage from a sibling test:
 * <pre>{@code
 * ConfigurableLeadReadPort leads = new ConfigurableLeadReadPort();
 * leads.register(leadId, leadView);                 // resolvable lead
 * leads.failFor(otherId, "upstream timeout");       // throws LeadReadException (retryable)
 * }</pre>
 */
public final class ConfigurableLeadReadPort implements LeadReadPort {

    private final Map<UUID, LeadView> leadsById = new ConcurrentHashMap<>();
    private final Map<UUID, RuntimeException> failuresById = new ConcurrentHashMap<>();
    private volatile RuntimeException failAll;

    /** Seeds a resolvable {@link LeadView} for a lead id. */
    public ConfigurableLeadReadPort register(UUID leadId, LeadView leadView) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        Objects.requireNonNull(leadView, "leadView must not be null");
        leadsById.put(leadId, leadView);
        return this;
    }

    /** Makes {@link #findLead(UUID)} throw a {@link LeadReadException} for the given id (Req 8.4). */
    public ConfigurableLeadReadPort failFor(UUID leadId, String message) {
        return failFor(leadId, new LeadReadException(message));
    }

    /** Makes {@link #findLead(UUID)} throw the supplied exception for the given id (Req 8.4). */
    public ConfigurableLeadReadPort failFor(UUID leadId, RuntimeException exception) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        failuresById.put(leadId, Objects.requireNonNull(exception, "exception must not be null"));
        return this;
    }

    /** Makes every read throw the supplied exception, regardless of id (Req 8.4). */
    public ConfigurableLeadReadPort failAll(RuntimeException exception) {
        this.failAll = Objects.requireNonNull(exception, "exception must not be null");
        return this;
    }

    /** Clears all seeded leads and failure configuration. */
    public void clear() {
        leadsById.clear();
        failuresById.clear();
        failAll = null;
    }

    @Override
    public Optional<LeadView> findLead(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        if (failAll != null) {
            throw failAll;
        }
        RuntimeException failure = failuresById.get(leadId);
        if (failure != null) {
            throw failure;
        }
        return Optional.ofNullable(leadsById.get(leadId));
    }
}
