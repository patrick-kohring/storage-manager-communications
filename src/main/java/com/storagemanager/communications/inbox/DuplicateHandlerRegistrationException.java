package com.storagemanager.communications.inbox;

/**
 * Thrown during context refresh when two InboxHandler beans declare the same eventType, naming the
 * conflicting value so startup fails diagnosably (Req 1.6).
 */
public class DuplicateHandlerRegistrationException extends RuntimeException {

    public DuplicateHandlerRegistrationException(String eventType, InboxHandler a, InboxHandler b) {
        super("Duplicate Inbox_Handler registration for eventType '" + eventType + "': "
                + a.getClass().getName() + " and " + b.getClass().getName());
    }
}
