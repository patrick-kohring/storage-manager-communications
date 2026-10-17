package com.storagemanager.communications.action.lead;

import java.util.Optional;
import java.util.UUID;

/**
 * Read seam that insulates the {@code storage-manager-communications} service from the Lead,
 * Organization, Facility, and user-identity tables, which it does not own (Requirement 8).
 *
 * <p>Exposes, for a lead id, the lead's organization id, facility id, contact values, preferred
 * contact method, and the lead property values used for personalization — all projected onto a
 * single {@link LeadView} (Requirement 8.1).
 *
 * <p><strong>Lead-not-found vs transient failure.</strong> These are two distinct outcomes:
 * <ul>
 *   <li>When no lead exists for the id, {@link #findLead(UUID)} returns {@link Optional#empty()}
 *       (Requirement 8.3). The {@code ActionProcessor} treats this as a terminal validation
 *       rejection and does not retry. Per the design, lead-not-found is represented by an empty
 *       {@code Optional} rather than a dedicated exception.</li>
 *   <li>When a transient error prevents the read (for example a network failure), the
 *       implementation throws {@link LeadReadException} (Requirement 8.4). The
 *       {@code ActionProcessor} surfaces this as a retryable processing error so the originating
 *       message remains eligible for redelivery.</li>
 * </ul>
 *
 * <p>This spec provides an in-memory stub implementation for testing; the live service-to-service
 * implementation against {@code storage-manager-api} is a cross-repository assumption (A3).
 */
public interface LeadReadPort {

    /**
     * Reads the {@link LeadView} for the given lead id.
     *
     * @param leadId the lead to read; never {@code null}
     * @return the lead view, or {@link Optional#empty()} if no lead exists for the id
     *         (Requirement 8.3)
     * @throws LeadReadException on a transient read failure, signalling a retryable condition
     *         (Requirement 8.4)
     */
    Optional<LeadView> findLead(UUID leadId);
}
