package com.storagemanager.communications.action.lead;

/**
 * Signals a <em>transient</em> failure while reading lead data through a {@link LeadReadPort}
 * (for example a network error or an upstream {@code storage-manager-api} outage).
 *
 * <p>This exception represents a retryable condition only. The {@code ActionProcessor} translates
 * it into a retryable processing error so the originating message remains eligible for redelivery
 * (Requirement 8.4). It must <strong>not</strong> be used to signal that no lead exists for the
 * given id: the <em>lead-not-found</em> case is represented by {@link LeadReadPort#findLead(java.util.UUID)}
 * returning {@link java.util.Optional#empty()} (Requirement 8.3), which the processor treats as a
 * terminal validation rejection rather than a retryable failure.
 */
public class LeadReadException extends RuntimeException {

    public LeadReadException(String message) {
        super(message);
    }

    public LeadReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
