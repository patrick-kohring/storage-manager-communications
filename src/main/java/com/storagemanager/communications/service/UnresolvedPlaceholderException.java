package com.storagemanager.communications.service;

/**
 * Raised by {@link TemplatePersonalizer} when template content contains a {@code {{placeholder}}}
 * whose inner name has no {@code Entity_Property_Mapping} for entity type {@code LEAD}.
 *
 * <p>This is a pure-function failure signalling an authoring/configuration defect: the template
 * references a placeholder that is not defined. The {@code ActionProcessor} translates it into a
 * terminal {@code ActionValidationException} that names the unresolved placeholder and invokes the
 * send seam zero times (Requirement 6.3).
 *
 * <p>Owned by the {@code lead-communication-action} spec (TemplatePersonalizer).
 */
public class UnresolvedPlaceholderException extends RuntimeException {

    private final String placeholder;

    /**
     * @param placeholder the first unmapped placeholder inner name (without the {@code {{}} delimiters)
     */
    public UnresolvedPlaceholderException(String placeholder) {
        super("No LEAD property mapping for placeholder: " + placeholder);
        this.placeholder = placeholder;
    }

    /** @return the first unmapped placeholder inner name encountered during personalization. */
    public String getPlaceholder() {
        return placeholder;
    }
}
