package com.nivas.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nivas.search.document.PropertyDocument;

/**
 * Guards the hand-written index mapping against the document class drifting away
 * from it.
 *
 * <p>The mapping is {@code dynamic: strict}, which means an unmapped field does
 * not get quietly indexed — it fails the write. Without this test that mismatch
 * would first appear as a runtime failure in the indexer, well away from the
 * change that caused it.
 */
class PropertyMappingResourceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode load(String resource) {
        try (InputStream stream = PropertyMappingResourceTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(stream).as("%s must exist on the classpath", resource).isNotNull();
            return JSON.readTree(stream);
        } catch (Exception ex) {
            throw new AssertionError(resource + " is not valid JSON", ex);
        }
    }

    @Test
    @DisplayName("the mapping is strict, so unknown fields fail loudly")
    void mappingIsStrict() {
        assertThat(load("elasticsearch/property-mapping.json").path("dynamic").asText())
                .isEqualTo("strict");
    }

    /**
     * Both of these are what make {@code dynamic: strict} survivable. Spring Data
     * otherwise injects two fields of its own into {@code _source} — a
     * {@code _class} type hint and the {@code version} — and the mapping knows
     * about neither, so every index write fails.
     *
     * <p>Not hypothetical: leaving {@code storeVersionInSource} at its default
     * broke the entire indexing pipeline, and the failure surfaced only as
     * listings never appearing in search.
     */
    @Test
    @DisplayName("the document does not let Spring Data inject fields into _source")
    void documentSuppressesFrameworkFields() {
        org.springframework.data.elasticsearch.annotations.Document annotation =
                PropertyDocument.class.getAnnotation(
                        org.springframework.data.elasticsearch.annotations.Document.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.storeVersionInSource())
                .as("true would add a 'version' field the strict mapping rejects")
                .isFalse();
        assertThat(annotation.writeTypeHint())
                .as("a _class type hint would be rejected the same way")
                .isEqualTo(org.springframework.data.elasticsearch.annotations.WriteTypeHint.FALSE);
    }

    @Test
    @DisplayName("every persisted document field is present in the mapping")
    void documentFieldsAreAllMapped() {
        JsonNode mappedProperties = load("elasticsearch/property-mapping.json").path("properties");

        List<String> missing = new ArrayList<>();
        for (Field field : PropertyDocument.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            // Unmapped on purpose — but only legitimate because the document sets
            // storeVersionInSource = false. That is asserted separately below;
            // without it Spring Data writes "version" into _source and the strict
            // mapping rejects every single index operation.
            if (field.getName().equals("version")) {
                continue;
            }
            if (!mappedProperties.has(field.getName())) {
                missing.add(field.getName());
            }
        }

        assertThat(missing)
                .as("dynamic:strict means these fields would be rejected at index time")
                .isEmpty();
    }

    @Test
    @DisplayName("the mapping declares no field the document cannot supply")
    void mappingHasNoOrphanFields() {
        JsonNode mappedProperties = load("elasticsearch/property-mapping.json").path("properties");

        List<String> documentFields = new ArrayList<>();
        for (Field field : PropertyDocument.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                documentFields.add(field.getName());
            }
        }

        List<String> orphans = new ArrayList<>();
        for (Iterator<String> names = mappedProperties.fieldNames(); names.hasNext();) {
            String name = names.next();
            if (!documentFields.contains(name)) {
                orphans.add(name);
            }
        }

        assertThat(orphans).as("mapped fields nothing ever writes are dead weight").isEmpty();
    }

    @Test
    @DisplayName("every analyzer the mapping references is defined in the settings")
    void analyzersAreDefined() {
        JsonNode settings = load("elasticsearch/property-settings.json");
        JsonNode mapping = load("elasticsearch/property-mapping.json");

        JsonNode analysis = settings.path("analysis");
        List<String> declared = new ArrayList<>();
        analysis.path("analyzer").fieldNames().forEachRemaining(declared::add);
        analysis.path("normalizer").fieldNames().forEachRemaining(declared::add);
        // Elasticsearch ships these; they need no declaration.
        declared.addAll(List.of("standard", "simple", "keyword", "whitespace", "stop", "english"));

        List<String> referenced = new ArrayList<>();
        mapping.findValues("analyzer").forEach(node -> referenced.add(node.asText()));
        mapping.findValues("search_analyzer").forEach(node -> referenced.add(node.asText()));
        mapping.findValues("normalizer").forEach(node -> referenced.add(node.asText()));

        assertThat(referenced).isNotEmpty();
        assertThat(declared).containsAll(referenced);
    }

    @Test
    @DisplayName("the autocomplete field is not analysed with edge-ngrams at search time")
    void autocompleteUsesASearchAnalyzer() {
        JsonNode autocomplete = load("elasticsearch/property-mapping.json")
                .path("properties").path("title").path("fields").path("autocomplete");

        assertThat(autocomplete.path("analyzer").asText()).isEqualTo("nivas_autocomplete_index");
        // Without a distinct search analyzer the query itself is split into
        // ngrams too, which matches almost everything and destroys precision.
        assertThat(autocomplete.path("search_analyzer").asText()).isEqualTo("nivas_autocomplete_search");
    }

    @Test
    @DisplayName("synonyms are applied at search time only")
    void synonymsAreSearchTimeOnly() {
        JsonNode analyzers = load("elasticsearch/property-settings.json").path("analysis").path("analyzer");

        assertThat(analyzers.path("nivas_text").path("filter").toString())
                .as("index-time synonyms would require a reindex to change")
                .doesNotContain("nivas_synonyms");
        assertThat(analyzers.path("nivas_text_search").path("filter").toString())
                .contains("nivas_synonyms");
    }
}
