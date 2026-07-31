package com.nivas.indexing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Component;

import com.nivas.search.document.PropertyDocument;

/**
 * Creates the search index, with its mapping, before anything writes to it.
 *
 * <p>This is not belt-and-braces — without it the application is quietly broken.
 * {@code @Document(createIndex = true)} only takes effect when a Spring Data
 * <em>repository</em> bootstraps, and this application talks to
 * {@link ElasticsearchOperations} directly. So nothing created the index, the
 * first projection failed with {@code NoSuchIndexException}, and Elasticsearch
 * then auto-created {@code properties} from the shape of the first document.
 *
 * <p>That auto-created index is the dangerous part, because everything appears
 * to work. Search returns results. But the inferred mapping had
 * {@code location} as two floats rather than a {@code geo_point} (so every geo
 * query fails), {@code suggest} as a plain object rather than a
 * {@code completion} field, {@code price} as a float rather than a
 * {@code scaled_float}, {@code dynamic} back to its permissive default, and no
 * analysis section at all — no synonyms, no edge-ngram autocomplete, no
 * normalisers. Every relevance feature silently absent, with nothing in the logs
 * to say so.
 *
 * <p>Runs on {@code ApplicationReadyEvent} rather than at bean construction so a
 * slow Elasticsearch does not hold up the rest of the context.
 */
@Component
public class SearchIndexInitializer {

    private static final Logger log = LoggerFactory.getLogger(SearchIndexInitializer.class);

    private final ElasticsearchOperations operations;

    public SearchIndexInitializer(ElasticsearchOperations operations) {
        this.operations = operations;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createIndexIfMissing() {
        IndexOperations indexOps = operations.indexOps(PropertyDocument.class);

        try {
            if (indexOps.exists()) {
                log.debug("Search index '{}' already exists", PropertyDocument.INDEX_NAME);
                return;
            }

            indexOps.createWithMapping();
            log.info("Created search index '{}' with its mapping and analyzers",
                    PropertyDocument.INDEX_NAME);

        } catch (RuntimeException ex) {
            // Deliberately not fatal. Elasticsearch being briefly unreachable
            // should not stop the API from serving writes, and the outbox will
            // replay whatever it missed. But this must be loud: if it is left
            // unresolved, the first write auto-creates an index with the wrong
            // mapping and search quality degrades invisibly.
            log.error("Could not create the search index '{}'. Search will not work correctly "
                    + "until this is resolved — fix Elasticsearch, then POST /api/v1/admin/index/rebuild.",
                    PropertyDocument.INDEX_NAME, ex);
        }
    }
}
