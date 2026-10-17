package com.storagemanager.communications.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.SentCommunication;

/**
 * Spring Data JPA repository for {@link SentCommunication}.
 *
 * <p>Provides the paginated, newest-first list finders backing the list endpoint and the
 * {@code publicId}-uniqueness pre-check used by the service when generating a public id.
 *
 * <p>Every list finder carries the {@code OrderByDateSentDescIdDesc} suffix, encoding the
 * deterministic newest-first ordering (by {@code dateSent} descending) with the {@code id} as a
 * stable tiebreaker so results are deterministic across repeated identical calls (Requirement 4.2).
 * The {@link Pageable} carries page/size only; the sort is baked into the method name and is
 * supported by the {@code (facility_id, date_sent)} index and the primary key.
 *
 * <p>Two finder families are exposed:
 * <ul>
 *   <li>Admin (non-org-scoped) finders returning entries across all organizations, optionally
 *       filtered by {@code facilityId} and/or {@code messageType} (Requirements 4.3, 4.4, 4.6, 7.6).</li>
 *   <li>OWNER org-confined variants ({@code organizationId}-leading) restricting results to the
 *       caller's own organization, optionally filtered by {@code facilityId} and/or
 *       {@code messageType} (Requirements 4.5, 7.5).</li>
 * </ul>
 */
public interface SentCommunicationRepository extends JpaRepository<SentCommunication, UUID> {

    /**
     * Service-level uniqueness pre-check for a generated {@code publicId}.
     */
    boolean existsByPublicId(String publicId);

    /**
     * Admin, unfiltered list, ordered newest-first by {@code dateSent} with {@code id} tiebreak.
     */
    Page<SentCommunication> findAllByOrderByDateSentDescIdDesc(Pageable p);

    /**
     * Admin, {@code facilityId}-filtered list, ordered newest-first by {@code dateSent} with
     * {@code id} tiebreak.
     */
    Page<SentCommunication> findByFacilityIdOrderByDateSentDescIdDesc(UUID facilityId, Pageable p);

    /**
     * Admin, {@code messageType}-filtered list, ordered newest-first by {@code dateSent} with
     * {@code id} tiebreak.
     */
    Page<SentCommunication> findByMessageTypeOrderByDateSentDescIdDesc(MessageType type, Pageable p);

    /**
     * Admin, {@code facilityId}- and {@code messageType}-filtered list, ordered newest-first by
     * {@code dateSent} with {@code id} tiebreak.
     */
    Page<SentCommunication> findByFacilityIdAndMessageTypeOrderByDateSentDescIdDesc(
            UUID facilityId, MessageType type, Pageable p);

    /**
     * OWNER, org-confined unfiltered list, ordered newest-first by {@code dateSent} with
     * {@code id} tiebreak.
     */
    Page<SentCommunication> findByOrganizationIdOrderByDateSentDescIdDesc(UUID orgId, Pageable p);

    /**
     * OWNER, org-confined {@code facilityId}-filtered list, ordered newest-first by
     * {@code dateSent} with {@code id} tiebreak.
     */
    Page<SentCommunication> findByOrganizationIdAndFacilityIdOrderByDateSentDescIdDesc(
            UUID orgId, UUID facilityId, Pageable p);

    /**
     * OWNER, org-confined {@code messageType}-filtered list, ordered newest-first by
     * {@code dateSent} with {@code id} tiebreak.
     */
    Page<SentCommunication> findByOrganizationIdAndMessageTypeOrderByDateSentDescIdDesc(
            UUID orgId, MessageType type, Pageable p);

    /**
     * OWNER, org-confined {@code facilityId}- and {@code messageType}-filtered list, ordered
     * newest-first by {@code dateSent} with {@code id} tiebreak.
     */
    Page<SentCommunication> findByOrganizationIdAndFacilityIdAndMessageTypeOrderByDateSentDescIdDesc(
            UUID orgId, UUID facilityId, MessageType type, Pageable p);
}
