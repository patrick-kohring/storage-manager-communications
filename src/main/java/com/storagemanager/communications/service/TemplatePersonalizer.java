package com.storagemanager.communications.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Pure function that personalizes {@code Communication_Template} content by replacing every
 * {@code {{inner-name}}} occurrence with the lead property value selected by the matching
 * {@code Entity_Property_Mapping} (Requirement 6).
 *
 * <p>Totality: each placeholder is either substituted or the whole call fails with
 * {@link UnresolvedPlaceholderException} naming the first unmapped inner name (Req 6.3). The method
 * performs no I/O, which makes it a pure function of {@code (content, placeholderToJsonProperty,
 * leadProperties)}.
 *
 * <p>Owned by the {@code lead-communication-action} spec (TemplatePersonalizer, service package).
 */
@Component
public final class TemplatePersonalizer {

    /**
     * Locates {@code {{inner-name}}} occurrences where the inner name is the kebab-case
     * {@code Entity_Property_Mapping.placeholder}. Optional surrounding whitespace inside the
     * braces is tolerated; the captured group is the trimmed inner name.
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z0-9-]+)\\s*\\}\\}");

    /**
     * Replaces every {@code {{inner-name}}} occurrence in {@code content} with the lead property
     * value selected by the matching {@code Entity_Property_Mapping}.
     *
     * <ul>
     *   <li>No placeholders → {@code content} is returned unchanged (Req 6.4).</li>
     *   <li>For each occurrence, the inner name is looked up in {@code placeholderToJsonProperty};
     *       an inner name with no mapping throws {@link UnresolvedPlaceholderException} naming the
     *       first such placeholder (Req 6.3).</li>
     *   <li>When a mapping is present, {@code leadProperties.get(jsonProperty)} is read; an absent or
     *       null value is substituted with the empty string (Req 6.5), otherwise its string form
     *       ({@code String.valueOf(value)}).</li>
     *   <li>The same inner name repeated resolves to the identical value every time (Req 6.6),
     *       because resolution is a pure function of the inner name.</li>
     * </ul>
     *
     * @param content                   template content ({@code emailContent} or {@code smsContent})
     * @param placeholderToJsonProperty (entityType == LEAD) placeholder inner-name → jsonProperty
     * @param leadProperties            jsonProperty → value from the {@code LeadView} (may hold null/absent)
     * @return the personalized content
     * @throws UnresolvedPlaceholderException if any placeholder inner name has no mapping (Req 6.3)
     */
    public String personalize(String content,
                              Map<String, String> placeholderToJsonProperty,
                              Map<String, Object> leadProperties) {
        if (content == null || content.isEmpty()) {
            return content;
        }

        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String innerName = matcher.group(1);
            if (placeholderToJsonProperty == null || !placeholderToJsonProperty.containsKey(innerName)) {
                throw new UnresolvedPlaceholderException(innerName);
            }
            String jsonProperty = placeholderToJsonProperty.get(innerName);
            String replacement = resolveValue(jsonProperty, leadProperties);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Resolves the string form of the lead property named by {@code jsonProperty}. An absent or null
     * value yields the empty string (Req 6.5).
     */
    private String resolveValue(String jsonProperty, Map<String, Object> leadProperties) {
        if (jsonProperty == null || leadProperties == null) {
            return "";
        }
        Object value = leadProperties.get(jsonProperty);
        return value == null ? "" : String.valueOf(value);
    }
}
