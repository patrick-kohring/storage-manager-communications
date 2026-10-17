package com.storagemanager.communications.util;

import java.util.regex.Pattern;

/**
 * Shared, pragmatic email/phone format predicates used to classify a literal contact value as an
 * email address or a phone number.
 *
 * <p><strong>Channel-check consistency (design "Channel-check consistency").</strong> The
 * {@code lead-communication-action} {@code ActionProcessor} classifies an explicit-recipient
 * literal into an {@code EMAIL} or {@code SMS} channel, and the {@code communications-api}
 * {@code SendService} independently validates the recipient contact again inside its send flow. If
 * the two used different predicates, a value the processor classified as (say) {@code EMAIL} could
 * later be rejected by the send as a malformed email. To avoid that drift, both sides share the
 * <em>same</em> predicates defined here. These mirror the exact regexes and digit bounds previously
 * inlined in {@code SendService} (Requirement 2.9 of the {@code communications-api} spec); the
 * public behavior of {@code SendService} is preserved unchanged.
 */
public final class ContactFormat {

    /**
     * Pragmatic email syntax check: a non-whitespace local part, an {@code @}, a dotted domain with
     * a 2+ character TLD. Kept deliberately simple and documented rather than attempting full
     * RFC 5322 coverage.
     */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)*\\.[^\\s@.]{2,}$");

    /**
     * Pragmatic phone syntax check: an optional leading {@code +} followed by 7 to 15 digits,
     * allowing spaces, hyphens, dots, and parentheses as separators between digits. Kept simple and
     * documented.
     */
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("^\\+?[0-9][0-9().\\-\\s]{5,}[0-9]$");

    /** Minimum/maximum count of digit characters a valid phone number may contain. */
    private static final int MIN_PHONE_DIGITS = 7;
    private static final int MAX_PHONE_DIGITS = 15;

    private ContactFormat() {
    }

    /**
     * @param email the candidate value (must be non-null)
     * @return {@code true} when {@code email} matches the pragmatic email pattern
     */
    public static boolean isValidEmail(String email) {
        return email != null && EMAIL_PATTERN.matcher(email).matches();
    }

    /**
     * @param phone the candidate value (must be non-null)
     * @return {@code true} when {@code phone} matches the pragmatic phone pattern and carries a
     *         digit count within the inclusive {@value #MIN_PHONE_DIGITS}..{@value #MAX_PHONE_DIGITS}
     *         bounds
     */
    public static boolean isValidPhone(String phone) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            return false;
        }
        long digits = phone.chars().filter(Character::isDigit).count();
        return digits >= MIN_PHONE_DIGITS && digits <= MAX_PHONE_DIGITS;
    }
}
