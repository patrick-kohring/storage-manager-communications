package com.storagemanager.communications.action.lead;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.storagemanager.communications.model.MessageType;

/**
 * Live {@link LeadReadPort} that reads a single lead from {@code storage-manager-api}'s
 * {@code GET /api/v1/leads/{id}} endpoint over HTTP and projects the response onto a
 * {@link LeadView} (Requirements 1.1, 1.3, 2.1–2.7).
 *
 * <p><strong>Profile.</strong> Registered under {@code @Profile("!local & !test & !default")} so it
 * is the single active {@link LeadReadPort} in the non-local runtime profile; the in-memory
 * {@link StubLeadReadPort} (which is {@code @Profile({"local","test","default"})}) remains active
 * for local development and tests. The two profile expressions are complementary, so exactly one
 * {@link LeadReadPort} bean is active in any given profile (Requirements 5.1, 5.2).
 *
 * <p><strong>Transport.</strong> The constructor is injected with the api {@link RestClient}
 * (base URL + timeouts + optional s2s bearer), qualified as {@code apiReadRestClient} to avoid
 * ambiguity with any other {@link RestClient} bean in the context.
 *
 * <p><strong>Outcome mapping</strong> (Requirement 3):
 * <ul>
 *   <li>404 → {@link Optional#empty()} (terminal lead-not-found; never throws). (Req 3.1)</li>
 *   <li>2xx with a valid body → {@link Optional#of(Object)} with a fully mapped {@link LeadView}.
 *       (Req 3.2)</li>
 *   <li>5xx / 401 / 403 / any other error status → {@link LeadReadException} with the status
 *       recorded in the detail (retryable). (Req 3.3, 3.5)</li>
 *   <li>transport error / timeout / unparseable body / a 200 missing the required
 *       {@code organizationId} → {@link LeadReadException} (retryable). (Req 3.4, 2.1)</li>
 * </ul>
 * The adapter never translates outcomes into {@code ActionValidationException} /
 * {@code ActionRetryableException}; that classification stays in the {@code ActionProcessor}
 * (Req 3.6).
 */
@Component
@Profile("!test & !default")
public class HttpLeadReadPort implements LeadReadPort {

    private final RestClient restClient;

    public HttpLeadReadPort(@Qualifier("apiReadRestClient") RestClient restClient) {
        this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
    }

    @Override
    public Optional<LeadView> findLead(UUID leadId) {
        Objects.requireNonNull(leadId, "leadId must not be null");
        try {
            LeadReadResponse body = restClient.get()
                    .uri("/api/v1/leads/{id}", leadId)
                    .retrieve()
                    .body(LeadReadResponse.class);
            return Optional.of(toLeadView(body));
        } catch (HttpClientErrorException.NotFound nf) {
            // 404 -> terminal lead-not-found (Req 3.1). NotFound is a subtype of
            // RestClientResponseException, so this catch must precede it.
            return Optional.empty();
        } catch (RestClientResponseException e) {
            // 5xx, 401, 403, any other error status -> retryable, status in detail (Req 3.3, 3.5).
            throw new LeadReadException("lead read failed (" + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            // transport error / timeout / unparseable body -> retryable (Req 3.4).
            throw new LeadReadException("lead read transport error: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a 200 {@link LeadReadResponse} onto a {@link LeadView} (Requirement 2).
     *
     * <p>The required {@code organizationId} is read from the nested {@code facility} object (the
     * api exposes it there, not at the top level). A null body, a missing {@code facility}/
     * {@code facility.organizationId}, or a missing {@code preferredContactMethod} are all treated
     * as a malformed body and surfaced as a {@link LeadReadException} (Req 2.1, 3.4).
     *
     * <p>{@code LeadView.properties} is assembled here from the lead's own returned fields, keyed
     * by their JSON property names — the same keys an admin configures as an
     * {@code EntityPropertyMapping.jsonProperty} — so template placeholders resolve against real
     * lead fields. Only non-null fields are included; a placeholder mapped to an absent field
     * resolves to empty during personalization (its lookup misses the map).
     */
    private LeadView toLeadView(LeadReadResponse body) {
        if (body == null) {
            throw new LeadReadException("lead read returned an empty body");
        }
        if (body.facility() == null || body.facility().organizationId() == null) {
            throw new LeadReadException(
                    "lead read response missing required facility.organizationId");
        }
        if (body.preferredContactMethod() == null) {
            throw new LeadReadException("lead read response missing required preferredContactMethod");
        }

        MessageType preferredMethod = MessageType.valueOf(body.preferredContactMethod().name());

        return new LeadView(
                body.facility().organizationId(),
                body.facilityId(),
                body.email(),
                body.mobilePhone(),
                preferredMethod,
                buildProperties(body));
    }

    /**
     * Assemble the personalization {@code properties} map from the lead's returned fields, keyed by
     * their JSON property names (matching {@code EntityPropertyMapping.jsonProperty} values). Null
     * fields are omitted so a placeholder mapped to an absent field resolves to empty rather than a
     * literal {@code "null"}. The map is deliberately built here (communications) rather than on
     * the api: the api returns the lead's fields, and communications owns which of them are
     * personalizable.
     */
    private static Map<String, Object> buildProperties(LeadReadResponse body) {
        Map<String, Object> properties = new LinkedHashMap<>();
        putIfPresent(properties, "firstName", body.firstName());
        putIfPresent(properties, "lastName", body.lastName());
        putIfPresent(properties, "email", body.email());
        putIfPresent(properties, "homePhone", body.homePhone());
        putIfPresent(properties, "mobilePhone", body.mobilePhone());
        putIfPresent(properties, "city", body.city());
        putIfPresent(properties, "state", body.state());
        putIfPresent(properties, "postalCode", body.postalCode());
        putIfPresent(properties, "dateOfBirth", body.dateOfBirth());
        putIfPresent(properties, "source", body.source());
        // The facility name is nested on the api response (facility.name) and is keyed here under
        // the dotted "facility.name" so it matches the EntityPropertyMapping.jsonProperty for the
        // "facility-name" placeholder.
        if (body.facility() != null) {
            putIfPresent(properties, "facility.name", body.facility().name());
        }
        if (body.preferredContactMethod() != null) {
            properties.put("preferredContactMethod", body.preferredContactMethod().name());
        }
        return properties;
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }
}
