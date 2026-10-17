package com.storagemanager.communications.inbox;

/**
 * Terminal reconstruction failure: a required EventEnvelope field could not be populated from the
 * InboundMessage because a header/scope value was absent or unparseable (Req 3.6). Carries the
 * offending field name and reason so the consumed message is diagnosable (Req 3.7). Caught inside
 * the Dispatching_Handler, which records the reason and completes normally (processed_at stamped).
 */
public class ReconstructionException extends RuntimeException {

    private final String field;

    public ReconstructionException(String field, String reason) {
        this(field, reason, null);
    }

    public ReconstructionException(String field, String reason, Throwable cause) {
        super("reconstruction failed for field '" + field + "': " + reason, cause);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
