package com.storagemanager.communications.messaging;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Secret-free guard for messaging payloads (Requirement 10.2).
 *
 * <p>The messaging base is domain-agnostic: it carries whatever payload it is given opaquely and
 * never <em>adds</em> payment secrets, authentication secrets, or PII to an enriched/emitted
 * payload. This guard provides the complementary detection half — it flags any payload key whose
 * name matches a documented denylist of secret / PII field names, so a producing or consuming
 * domain spec can assert that such keys never travel on the event bus.
 *
 * <p>The denylist is a conservative, case-insensitive set of well-known secret and PII key names.
 * It is intentionally minimal and documented here rather than enumerating a concrete domain
 * payload field set (Requirement 10.3 reserves the per-event approved-field allowlist to each
 * domain spec). The guard matches on the exact key name (case-insensitively), after normalizing
 * common separator styles (camelCase vs snake_case) so that {@code apiKey} and {@code api_key},
 * or {@code dateOfBirth} and {@code date_of_birth}, are treated identically.
 */
public final class PayloadSecretGuard {

    /**
     * Documented denylist of secret / PII key names (Requirement 10.2). Entries are stored in a
     * normalized form (lowercase, separators stripped) so matching is independent of camelCase vs
     * snake_case styling.
     */
    private static final Set<String> DENYLIST = Set.of(
            // Authentication / payment secrets
            normalize("password"),
            normalize("secret"),
            normalize("token"),
            normalize("authorization"),
            normalize("apiKey"),      // also matches api_key
            normalize("creditCard"),  // also matches credit_card
            normalize("cardNumber"),  // also matches card_number
            normalize("cvv"),
            // Personally identifiable information (PII)
            normalize("ssn"),
            normalize("dateOfBirth"), // also matches date_of_birth
            normalize("dob"),
            normalize("email"),
            normalize("firstName"),   // also matches first_name
            normalize("lastName"),    // also matches last_name
            normalize("phone"),
            normalize("mobilePhone"), // also matches mobile_phone
            normalize("homePhone"),   // also matches home_phone
            normalize("addressLine1"),// also matches address_line1
            normalize("addressLine2") // also matches address_line2
    );

    private PayloadSecretGuard() {
        // utility class
    }

    /**
     * Returns the set of payload keys that match the documented secret / PII denylist
     * (Requirement 10.2). The returned keys are the original keys as they appear in the payload
     * (preserving the caller's casing/style); matching is case-insensitive and
     * separator-insensitive.
     *
     * @param payload the payload to inspect; a {@code null} payload is treated as empty
     * @return an unmodifiable set of the offending original keys; empty when the payload is
     *     secret-free
     */
    public static Set<String> findDenylistedKeys(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> flagged = new LinkedHashSet<>();
        for (String key : payload.keySet()) {
            if (key != null && DENYLIST.contains(normalize(key))) {
                flagged.add(key);
            }
        }
        return Collections.unmodifiableSet(flagged);
    }

    /**
     * Convenience predicate: {@code true} when the payload contains no denylisted secret / PII key
     * (Requirement 10.2).
     *
     * @param payload the payload to inspect; a {@code null} payload is considered secret-free
     * @return {@code true} if the payload is free of denylisted keys
     */
    public static boolean isSecretFree(Map<String, Object> payload) {
        return findDenylistedKeys(payload).isEmpty();
    }

    /**
     * Normalizes a key name to a canonical comparison form: lowercased, with {@code _} and {@code -}
     * separators removed, so camelCase and snake_case variants of the same name collapse together
     * (e.g. {@code apiKey} and {@code api_key} both normalize to {@code apikey}).
     */
    private static String normalize(String key) {
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }
}
