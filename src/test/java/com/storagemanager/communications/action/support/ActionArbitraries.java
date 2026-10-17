package com.storagemanager.communications.action.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.storagemanager.communications.action.lead.LeadView;
import com.storagemanager.communications.messaging.EventEnvelope;
import com.storagemanager.communications.model.EntityType;
import com.storagemanager.communications.model.MessageType;
import com.storagemanager.communications.model.RecipientType;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;

/**
 * Shared jqwik {@link Arbitrary} generators for the {@code ActionProcessor} property-test harness
 * (lead-communication-action spec). Sibling property tests (7.2, 7.3, 7.5, 7.7, 7.8, 7.10, 7.11,
 * 7.13, 7.14, 7.15) {@code @ForAll} from these providers so the input-space definitions live in one
 * place.
 *
 * <p>The generators here cover the pieces named in the design's Testing Strategy: valid LEAD actions
 * (LEAD path and explicit path), lead views, templates with/without email/sms content, email-shaped
 * and phone-shaped explicit literals plus junk literals, and present/null correlation ids. The
 * headline provider is {@link #successfulActionCases()}, which assembles a fully-valid
 * {@link SuccessfulActionCase} (used directly by Property 1 and reusable wherever a passing action
 * is needed).
 *
 * <p>The individual building-block generators ({@link #kebabName()}, {@link #templateName()},
 * {@link #emailLiteral()}, {@link #phoneLiteral()}, {@link #junkLiteral()}, {@link #channel()}, …)
 * are public so the rejection-oriented siblings can compose their own cases.
 */
public final class ActionArbitraries {

    /** Mirrors the production placeholder grammar (design "TemplatePersonalizer"). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z0-9-]+)\\s*\\}\\}");

    private ActionArbitraries() {
    }

    // ------------------------------------------------------------------------
    // Building-block generators (reusable across every ActionProcessor property)
    // ------------------------------------------------------------------------

    /** Valid kebab-case placeholder inner names matching {@code ^[a-z0-9]+(-[a-z0-9]+)*$}. */
    public static Arbitrary<String> kebabName() {
        Arbitrary<String> segment =
                Arbitraries.strings().withCharRange('a', 'z').numeric().ofMinLength(1).ofMaxLength(6);
        return segment.list().ofMinSize(1).ofMaxSize(3).map(segs -> String.join("-", segs));
    }

    /** Non-blank template names (letters/digits/hyphens). */
    public static Arbitrary<String> templateName() {
        return Arbitraries.strings()
                .withCharRange('a', 'z')
                .numeric()
                .withChars('-')
                .ofMinLength(1)
                .ofMaxLength(24)
                .filter(s -> !s.isBlank());
    }

    /** {@code jsonProperty} values: flat names and dot-paths, stored/looked-up verbatim. */
    public static Arbitrary<String> jsonProperty() {
        Arbitrary<String> flat =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(16);
        Arbitrary<String> dotPath = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
                .list().ofMinSize(2).ofMaxSize(3).map(segs -> String.join(".", segs));
        return Arbitraries.oneOf(flat, dotPath);
    }

    /** Email-shaped literals accepted by {@code ContactFormat.isValidEmail}. */
    public static Arbitrary<String> emailLiteral() {
        Arbitrary<String> local =
                Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(12);
        Arbitrary<String> domain =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(10);
        Arbitrary<String> tld =
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(2).ofMaxLength(5);
        return Combinators.combine(local, domain, tld)
                .as((l, d, t) -> l + "@" + d + "." + t);
    }

    /** Phone-shaped literals accepted by {@code ContactFormat.isValidPhone} (7-15 digits). */
    public static Arbitrary<String> phoneLiteral() {
        return Arbitraries.integers().between(7, 15).flatMap(digitCount ->
                Arbitraries.strings().numeric().ofLength(digitCount)
                        .map(digits -> "+" + digits));
    }

    /**
     * Literals that are neither a valid email nor a valid phone (the junk/explicit-reject space). A
     * runtime filter guarantees the invariant even if a generated value accidentally looks valid.
     */
    public static Arbitrary<String> junkLiteral() {
        return Arbitraries.oneOf(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10), // bare word, no @ / digits
                        Arbitraries.just("@@@"),
                        Arbitraries.just("not an email"),
                        Arbitraries.just("12-34"),         // too few digits for a phone
                        Arbitraries.just("abc@"),          // incomplete email
                        Arbitraries.strings().withChars("!#$%^&*()").ofMinLength(1).ofMaxLength(6))
                .filter(s -> !com.storagemanager.communications.util.ContactFormat.isValidEmail(s)
                        && !com.storagemanager.communications.util.ContactFormat.isValidPhone(s));
    }

    /** Either delivery channel. */
    public static Arbitrary<MessageType> channel() {
        return Arbitraries.of(MessageType.EMAIL, MessageType.SMS);
    }

    /** A correlation id that is present (non-null) about half the time, else {@code null}. */
    public static Arbitrary<UUID> maybeNullCorrelationId() {
        return Arbitraries.oneOf(
                Arbitraries.create(UUID::randomUUID),
                Arbitraries.just(null));
    }

    // ------------------------------------------------------------------------
    // Property 1 (and general happy-path) case generator
    // ------------------------------------------------------------------------

    /**
     * Fully-valid cases that pass every validation branch and therefore produce exactly one send.
     * Covers both recipient paths and both channels, facility- and org-scoped templates, and
     * personalization content mixing literal text with mapped placeholders whose lead values are
     * present, null, or absent.
     *
     * @return an arbitrary of {@link SuccessfulActionCase}
     */
    public static Arbitrary<SuccessfulActionCase> successfulActionCases() {
        Arbitrary<Boolean> leadPathArb = Arbitraries.of(true, false);
        return leadPathArb.flatMap(ActionArbitraries::successfulCaseFor);
    }

    private static Arbitrary<SuccessfulActionCase> successfulCaseFor(boolean leadPath) {
        // A small pool of placeholder names, each mapped to a jsonProperty, with a per-name lead value
        // that is present / null / absent. Built first so content can draw from the pool.
        Arbitrary<List<String>> namePool =
                kebabName().list().uniqueElements().ofMinSize(0).ofMaxSize(4);

        return namePool.flatMap(names -> {
            Arbitrary<Map<String, String>> mappingArb = mappingsFor(names);
            return mappingArb.flatMap(mapping -> {
                Arbitrary<Map<String, Object>> propsArb = leadPropertiesFor(mapping);
                return propsArb.flatMap(leadProps -> {
                    Arbitrary<String> contentArb = contentFrom(names);
                    return contentArb.flatMap(content ->
                            assemble(leadPath, mapping, leadProps, content));
                });
            });
        });
    }

    private static Arbitrary<SuccessfulActionCase> assemble(
            boolean leadPath,
            Map<String, String> mapping,
            Map<String, Object> leadProps,
            String content) {

        Arbitrary<MessageType> channelArb = channel();
        Arbitrary<Boolean> facilityScopeArb = Arbitraries.of(true, false);
        // hasCorrelationId toggles the present-vs-null correlation id so Req 2.2 is exercised; the
        // actual id values are created inside the builder (keeping the combinator arity within bounds).
        Arbitrary<Boolean> hasCorrelationIdArb = Arbitraries.of(true, false);
        Arbitrary<String> templateNameArb = templateName();
        Arbitrary<String> emailArb = emailLiteral();
        Arbitrary<String> phoneArb = phoneLiteral();

        return Combinators.combine(channelArb, facilityScopeArb, hasCorrelationIdArb,
                        templateNameArb, emailArb, phoneArb)
                .as((channel, facilityScope, hasCorrelationId, tmplName, email, phone) ->
                        buildCase(leadPath, mapping, leadProps, content, channel,
                                facilityScope, hasCorrelationId, tmplName, email, phone));
    }

    private static SuccessfulActionCase buildCase(
            boolean leadPath,
            Map<String, String> mapping,
            Map<String, Object> leadProps,
            String content,
            MessageType channel,
            boolean facilityScope,
            boolean hasCorrelationId,
            String tmplName,
            String email,
            String phone) {

        UUID eventId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID facilityId = UUID.randomUUID();
        UUID leadId = UUID.randomUUID();
        UUID corr = hasCorrelationId ? UUID.randomUUID() : null;

        // The template must carry content for the resolved channel. Put the generated content on the
        // resolved channel and leave the other channel null (valid: only the resolved channel is used).
        boolean emailChannel = channel == MessageType.EMAIL;
        String emailContent = emailChannel ? content : null;
        String smsContent = emailChannel ? null : content;

        // Org-scoped templates resolve only when the lead's facility lookup misses; an org template
        // is found whether or not the lead has a facility (the processor falls back to org scope). A
        // facility-scoped template requires the lead to have a (matching) facility.
        UUID leadFacilityId = facilityScope ? facilityId : (leadPath ? null : facilityId);

        // Build the lead view. On the LEAD path the resolved channel's contact must be present; the
        // explicit path uses the literal, not the lead contact, but the lead still supplies org /
        // facility / properties.
        String leadEmail;
        String leadPhone;
        if (leadPath) {
            leadEmail = emailChannel ? email : null;
            leadPhone = emailChannel ? null : phone;
        } else {
            // Explicit path: lead contacts are irrelevant to the resolved recipient; keep them null
            // to prove the explicit literal (not the lead contact) is used.
            leadEmail = null;
            leadPhone = null;
        }

        MessageType preferredMethod = channel; // LEAD path keys off this; harmless on explicit path.
        LeadView leadView = new LeadView(orgId, leadFacilityId, leadEmail, leadPhone,
                preferredMethod, leadProps);

        // Recipient selection + expected contact / audit type.
        RecipientType recipientType;
        String explicitRecipient;
        String expectedContact;
        RecipientType expectedAuditType;
        if (leadPath) {
            recipientType = RecipientType.LEAD;
            explicitRecipient = null;
            expectedContact = emailChannel ? leadEmail : leadPhone;
            expectedAuditType = RecipientType.LEAD;
        } else {
            recipientType = null;
            explicitRecipient = emailChannel ? email : phone;
            expectedContact = explicitRecipient;
            expectedAuditType = null;
        }

        String expectedContent = oracleSubstitute(content, mapping, leadProps);
        UUID expectedCorrelationId = corr != null ? corr : eventId;

        EventEnvelope envelope = ActionEnvelopes.envelopeFor(
                EntityType.LEAD, leadId.toString(), tmplName, recipientType, explicitRecipient,
                eventId, corr);

        return new SuccessfulActionCase(
                envelope, leadId, leadView, tmplName, facilityScope, emailContent, smsContent,
                leadPath, mapping, channel, expectedContact, expectedContent, expectedAuditType,
                expectedCorrelationId);
    }

    // ------------------------------------------------------------------------
    // Content / mapping / property generators
    // ------------------------------------------------------------------------

    /** Maps every pooled name to a generated jsonProperty (so no placeholder is ever unmapped). */
    private static Arbitrary<Map<String, String>> mappingsFor(List<String> names) {
        if (names.isEmpty()) {
            return Arbitraries.just(new LinkedHashMap<>());
        }
        return jsonProperty().list().ofSize(names.size()).map(props -> {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < names.size(); i++) {
                map.put(names.get(i), props.get(i));
            }
            return map;
        });
    }

    /** For each mapped jsonProperty, independently present / null / absent (Req 6.5 coverage). */
    private static Arbitrary<Map<String, Object>> leadPropertiesFor(Map<String, String> mapping) {
        List<String> jsonProps = new ArrayList<>(new LinkedHashSet<>(mapping.values()));
        if (jsonProps.isEmpty()) {
            return Arbitraries.just(new LinkedHashMap<>());
        }
        Arbitrary<List<Integer>> kinds =
                Arbitraries.integers().between(0, 2).list().ofSize(jsonProps.size());
        Arbitrary<List<Object>> values = propertyValue().list().ofSize(jsonProps.size());
        return Combinators.combine(kinds, values).as((kindList, valueList) -> {
            Map<String, Object> props = new LinkedHashMap<>();
            for (int i = 0; i < jsonProps.size(); i++) {
                int kind = kindList.get(i);
                if (kind == 1) {
                    props.put(jsonProps.get(i), null);   // explicit null -> empty string
                } else if (kind == 2) {
                    props.put(jsonProps.get(i), valueList.get(i));
                }
                // kind == 0 -> absent -> empty string
            }
            return props;
        });
    }

    /** Non-null lead property values spanning strings, numbers, booleans. */
    private static Arbitrary<Object> propertyValue() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMinLength(0).ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
    }

    /**
     * Builds content by interleaving literal segments (containing no {@code {{}} tokens) with
     * {@code {{inner-name}}} tokens drawn from the pool. An empty pool yields placeholder-free
     * content. Interior whitespace exercises the grammar's whitespace tolerance.
     *
     * <p>The result is constrained to be <strong>non-blank</strong> template content: a successful
     * action requires the selected template to carry content for the resolved channel, and the
     * processor rejects blank/empty channel content as "template lacks content for the channel"
     * (Req 4.5/4.6). (A template whose only text is placeholders that resolve to empty strings is
     * still valid — the processor checks the raw template content, not the personalized result.)
     */
    private static Arbitrary<String> contentFrom(List<String> names) {
        Arbitrary<String> literal = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!? \n\t")
                .ofMinLength(0)
                .ofMaxLength(12);

        if (names.isEmpty()) {
            return literal.filter(s -> !s.isBlank());
        }

        Arbitrary<String> token = Combinators.combine(
                        Arbitraries.of(names),
                        Arbitraries.of("", " ", "  "),
                        Arbitraries.of("", " ", "  "))
                .as((name, lead, trail) -> "{{" + lead + name + trail + "}}");

        Arbitrary<String> slot = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, literal),
                net.jqwik.api.Tuple.of(3, token));

        return slot.list().ofMinSize(1).ofMaxSize(6)
                .map(parts -> String.join("", parts))
                .filter(s -> !s.isBlank());
    }

    // ------------------------------------------------------------------------
    // Personalization oracle (independent of the production substitution loop)
    // ------------------------------------------------------------------------

    /**
     * Computes the expected personalized content: every {@code {{inner-name}}} replaced by the lead
     * property named by its mapping, or the empty string when that property is absent/null (Req 6.5).
     * Assumes every inner name is mapped (true for successful cases).
     */
    public static String oracleSubstitute(String content, Map<String, String> mapping,
                                          Map<String, Object> leadProperties) {
        Matcher m = PLACEHOLDER.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            String inner = m.group(1);
            String jsonProperty = mapping.get(inner);
            Object value = jsonProperty == null ? null
                    : (leadProperties == null ? null : leadProperties.get(jsonProperty));
            out.append(value == null ? "" : String.valueOf(value));
            last = m.end();
        }
        out.append(content.substring(last));
        return out.toString();
    }

    /** Distinct placeholder inner names appearing in content, in first-seen order. */
    public static Set<String> distinctInnerNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }
}
