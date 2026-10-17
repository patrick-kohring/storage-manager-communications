package com.storagemanager.communications.exception;

public class EntityNotFoundException extends RuntimeException {

    public EntityNotFoundException(String message) {
        super(message);
    }

    public EntityNotFoundException(String entityName, Object id) {
        super(entityName + " not found with id: " + id);
    }
}
