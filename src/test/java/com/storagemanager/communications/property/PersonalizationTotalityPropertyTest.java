package com.storagemanager.communications.property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storagemanager.communications.service.TemplatePersonalizer;
import com.storagemanager.communications.service.UnresolvedPlaceholderException;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * jqwik property test for {@link TemplatePersonalizer} placeholder substitution
 * (lead-communication-action spec, Task 4.2).
 *
 * <p><strong>Feature: lead-communication-action, Property 2: Personalization substitutes mapped
 * placeholders totally and consistently.</strong> For any template content, any set of {@code LEAD}
 * {@code Entity_Property_Mapping} rows ({@code placeholder → jsonProperty}), and any lead property
 * map, {@code personalize} SHALL either (a) produce output in which every {@code {{inner-name}}}
 * occurrence whose inner name has a mapping is replaced — by the lead property named by that
 * mapping's {@code jsonProperty}, or by the empty string when that property is absent or null, with
 * every occurrence of the same inner name replaced by the identical value and content containing no
 * placeholders returned unchanged — or (b) throw {@link UnresolvedPlaceholderException} naming the
 * first {@code {{inner-name}}} whose inner name has no {@code LEAD} mapping.
 *
 * <p>The test builds content from a random mix of literal segments and {@code {{inner-name}}}
 * tokens drawn from a pool of kebab-case names, where each name is independently either mapped
 * (with a generated {@code jsonProperty} whose lead-property value may be present, null, or absent)
 * or deliberately left unmapped. An independent oracle computes the expected substitution so the
 * assertion does not reimplement the production regex loop, and the first unmapped token in source
 * order fixes the expected rejection target.
 *
 * <p><strong>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 6.6</strong>
 */
class PersonalizationTotalityPropertyTest {

    /** Mirrors the production placeholder grammar for the oracle only. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-z0-9-]+)\\s*\\}\\}");

    private final TemplatePersonalizer personalizer = new TemplatePersonalizer();

    // ------------------------------------------------------------------------
    // Property 2: totality and consistency of placeholder substitution
    // ------------------------------------------------------------------------

    /**
     * For any generated personalization case, {@code personalize} either reproduces the oracle's
     * total, consistent substitution (every mapped occurrence replaced by the same resolved value,
     * absent/null → empty string, placeholder-free content unchanged) or throws
     * {@link UnresolvedPlaceholderException} naming the first unmapped inner name.
     *
     * <p>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
     */
    @Property(tries = 100)
    void personalize_substitutesMappedPlaceholdersTotallyAndConsistently(
            @ForAll("personalizationCases") PersonalizationCase testCase) {

        String firstUnmapped = firstUnmappedInnerName(testCase.content, testCase.placeholderToJsonProperty);

        if (firstUnmapped != null) {
            // (b) at least one placeholder has no LEAD mapping → reject naming the FIRST such name (Req 6.3)
            assertThatThrownBy(() -> personalizer.personalize(
                            testCase.content,
                            testCase.placeholderToJsonProperty,
                            testCase.leadProperties))
                    .isInstanceOf(UnresolvedPlaceholderException.class)
                    .satisfies(ex -> assertThat(((UnresolvedPlaceholderException) ex).getPlaceholder())
                            .as("rejection names the first unmapped placeholder")
                            .isEqualTo(firstUnmapped));
            return;
        }

        // (a) every placeholder resolves → totally substituted, consistent output.
        String actual = personalizer.personalize(
                testCase.content, testCase.placeholderToJsonProperty, testCase.leadProperties);

        String expected = oracleSubstitute(
                testCase.content, testCase.placeholderToJsonProperty, testCase.leadProperties);
        assertThat(actual)
                .as("fully-mapped content substituted exactly per oracle")
                .isEqualTo(expected);

        // No placeholder delimiters survive for mapped names (totality, Req 6.1).
        assertThat(PLACEHOLDER.matcher(actual).find())
                .as("no {{...}} placeholder tokens remain after full substitution")
                .isFalse();

        // Content with no placeholders is returned unchanged (Req 6.4).
        if (!PLACEHOLDER.matcher(testCase.content).find()) {
            assertThat(actual).as("placeholder-free content unchanged").isEqualTo(testCase.content);
        }

        // Consistency: the same inner name resolves to the identical value everywhere (Req 6.6).
        for (String innerName : distinctInnerNames(testCase.content)) {
            String resolved = resolveValue(
                    testCase.placeholderToJsonProperty.get(innerName), testCase.leadProperties);
            String doubled = personalizer.personalize(
                    "{{" + innerName + "}}|{{" + innerName + "}}",
                    testCase.placeholderToJsonProperty,
                    testCase.leadProperties);
            assertThat(doubled)
                    .as("repeated inner name '%s' resolves identically each occurrence", innerName)
                    .isEqualTo(resolved + "|" + resolved);
        }
    }

    // ------------------------------------------------------------------------
    // Oracle (independent of the production substitution loop)
    // ------------------------------------------------------------------------

    /** Returns the first unmapped inner name in source order, or null when all are mapped. */
    private static String firstUnmappedInnerName(String content, Map<String, String> mappings) {
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            String inner = m.group(1);
            if (mappings == null || !mappings.containsKey(inner)) {
                return inner;
            }
        }
        return null;
    }

    /** Computes the fully-substituted expected output; assumes every inner name is mapped. */
    private static String oracleSubstitute(
            String content, Map<String, String> mappings, Map<String, Object> leadProperties) {
        Matcher m = PLACEHOLDER.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(content, last, m.start());
            String inner = m.group(1);
            out.append(resolveValue(mappings.get(inner), leadProperties));
            last = m.end();
        }
        out.append(content.substring(last));
        return out.toString();
    }

    /** Absent/null lead property → empty string; otherwise its String.valueOf form (Req 6.5). */
    private static String resolveValue(String jsonProperty, Map<String, Object> leadProperties) {
        if (jsonProperty == null || leadProperties == null) {
            return "";
        }
        Object value = leadProperties.get(jsonProperty);
        return value == null ? "" : String.valueOf(value);
    }

    private static Set<String> distinctInnerNames(String content) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(content);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ------------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------------

    /**
     * A full personalization case: generated content assembled from literal segments and
     * {@code {{inner-name}}} tokens, the {@code LEAD} placeholder→jsonProperty mapping set that
     * governs which tokens resolve, and the lead property map that supplies (or omits) values.
     */
    static final class PersonalizationCase {
        final String content;
        final Map<String, String> placeholderToJsonProperty;
        final Map<String, Object> leadProperties;

        PersonalizationCase(String content,
                            Map<String, String> placeholderToJsonProperty,
                            Map<String, Object> leadProperties) {
            this.content = content;
            this.placeholderToJsonProperty = placeholderToJsonProperty;
            this.leadProperties = leadProperties;
        }
    }

    /**
     * Builds cases covering the full input space: placeholder-free content, content with mapped
     * placeholders (values present, null, or absent in the lead data), repeated inner names, and
     * content with one or more unmapped placeholders.
     */
    @Provide
    Arbitrary<PersonalizationCase> personalizationCases() {
        // A small pool of candidate kebab-case inner names so repeats are likely across a document.
        Arbitrary<List<String>> namePool = kebabName().list().ofMinSize(1).ofMaxSize(5).map(ArrayList::new);

        return namePool.flatMap(names -> {
            Arbitrary<Map<String, String>> mappings = buildMappings(names);
            return mappings.flatMap(mapping -> {
                Arbitrary<Map<String, Object>> props = buildLeadProperties(mapping);
                Arbitrary<String> content = buildContent(names);
                return Combinators.combine(content, Arbitraries.just(mapping), props)
                        .as(PersonalizationCase::new);
            });
        });
    }

    /**
     * For each candidate name, independently decide whether it is mapped. Mapped names get a
     * generated {@code jsonProperty}; unmapped names are omitted from the map so they trigger the
     * rejection branch when they appear in content.
     */
    private Arbitrary<Map<String, String>> buildMappings(List<String> names) {
        Arbitrary<List<Boolean>> mappedFlags =
                Arbitraries.of(true, false).list().ofSize(names.size());
        Arbitrary<List<String>> jsonProps =
                jsonProperty().list().ofSize(names.size());
        return Combinators.combine(mappedFlags, jsonProps).as((flags, props) -> {
            Map<String, String> map = new HashMap<>();
            for (int i = 0; i < names.size(); i++) {
                if (flags.get(i)) {
                    map.put(names.get(i), props.get(i));
                }
            }
            return map;
        });
    }

    /**
     * For each mapped {@code jsonProperty}, independently decide whether the lead property is
     * present (a value), explicitly null, or absent from the map (so absent/null → empty string is
     * exercised per Req 6.5).
     */
    private Arbitrary<Map<String, Object>> buildLeadProperties(Map<String, String> mapping) {
        List<String> jsonProps = new ArrayList<>(new LinkedHashSet<>(mapping.values()));
        if (jsonProps.isEmpty()) {
            return Arbitraries.just(new HashMap<>());
        }
        // 0 = absent, 1 = null, 2 = present-value
        Arbitrary<List<Integer>> kinds = Arbitraries.integers().between(0, 2).list().ofSize(jsonProps.size());
        Arbitrary<List<Object>> values = propertyValue().list().ofSize(jsonProps.size());
        return Combinators.combine(kinds, values).as((kindList, valueList) -> {
            Map<String, Object> props = new HashMap<>();
            for (int i = 0; i < jsonProps.size(); i++) {
                int kind = kindList.get(i);
                if (kind == 1) {
                    props.put(jsonProps.get(i), null);
                } else if (kind == 2) {
                    props.put(jsonProps.get(i), valueList.get(i));
                }
                // kind == 0 → leave absent
            }
            return props;
        });
    }

    /**
     * Assembles document content by interleaving literal segments with {@code {{inner-name}}}
     * tokens drawn from the candidate pool, with optional interior whitespace inside the braces so
     * the grammar's whitespace tolerance is exercised. An empty token list yields placeholder-free
     * content.
     */
    private Arbitrary<String> buildContent(List<String> names) {
        Arbitrary<String> literal = Arbitraries.strings()
                .withChars("abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789.,!?\n\t")
                .ofMinLength(0)
                .ofMaxLength(12);

        Arbitrary<String> token = Combinators.combine(
                        Arbitraries.of(names),
                        Arbitraries.of("", " ", "  "),
                        Arbitraries.of("", " ", "  "))
                .as((name, lead, trail) -> "{{" + lead + name + trail + "}}");

        // Each slot is either a literal or a placeholder token; join them in order.
        Arbitrary<String> slot = Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(2, literal),
                net.jqwik.api.Tuple.of(3, token));

        return slot.list().ofMinSize(0).ofMaxSize(8).map(parts -> String.join("", parts));
    }

    /** Candidate inner names: valid kebab-case per the placeholder grammar. */
    private Arbitrary<String> kebabName() {
        Arbitrary<String> segment =
                Arbitraries.strings().withCharRange('a', 'z').numeric().ofMinLength(1).ofMaxLength(6);
        return segment.list().ofMinSize(1).ofMaxSize(3).map(segs -> String.join("-", segs));
    }

    /** jsonProperty values: flat names and dot-paths, stored and looked up verbatim. */
    private Arbitrary<String> jsonProperty() {
        Arbitrary<String> flat = Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(16);
        Arbitrary<String> dotPath = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(8)
                .list().ofMinSize(2).ofMaxSize(3).map(segs -> String.join(".", segs));
        return Arbitraries.oneOf(flat, dotPath);
    }

    /** Non-null lead property values spanning strings, numbers, and booleans. */
    private Arbitrary<Object> propertyValue() {
        return Arbitraries.oneOf(
                Arbitraries.strings().ofMinLength(0).ofMaxLength(20).map(s -> (Object) s),
                Arbitraries.integers().map(i -> (Object) i),
                Arbitraries.of(Boolean.TRUE, Boolean.FALSE).map(b -> (Object) b));
    }
}
