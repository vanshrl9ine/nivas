package com.nivas.indexing;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.catalog.PropertyRepository;
import com.nivas.catalog.domain.Property;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.search.document.PropertyDocument;

/**
 * Owns every write to the search index.
 *
 * <p>Nothing else in the application talks to Elasticsearch for writes, which
 * means the "only ACTIVE listings are indexed" rule is enforced in exactly one
 * place rather than assumed at each call site.
 */
@Service
public class PropertyIndexService {

    private static final Logger log = LoggerFactory.getLogger(PropertyIndexService.class);
    private static final int REINDEX_PAGE_SIZE = 500;

    private final ElasticsearchOperations operations;
    private final PropertyRepository propertyRepository;
    private final PropertyDocumentMapper mapper;
    private final BatchIndexer batchIndexer;

    public PropertyIndexService(ElasticsearchOperations operations, PropertyRepository propertyRepository,
            PropertyDocumentMapper mapper, BatchIndexer batchIndexer) {
        this.operations = operations;
        this.propertyRepository = propertyRepository;
        this.mapper = mapper;
        this.batchIndexer = batchIndexer;
    }

    /**
     * Re-reads the listing and brings the index in line with it.
     *
     * <p>Reading current state here rather than trusting the event payload is what
     * makes redelivery harmless: whatever the event said, the index ends up
     * matching the database as of now.
     */
    @Transactional(readOnly = true)
    public void syncFromDatabase(UUID propertyId) {
        Property property = propertyRepository.findByIdWithDetail(propertyId).orElse(null);

        if (property == null) {
            // Deleted between the event being written and being consumed.
            deleteFromIndex(propertyId);
            return;
        }
        if (!property.getStatus().isSearchable()) {
            deleteFromIndex(propertyId);
            return;
        }
        index(property);
    }

    public void index(Property property) {
        PropertyDocument document = mapper.toDocument(property);
        try {
            operations.save(document);
            log.debug("Indexed listing {} at version {}", document.getId(), document.getVersion());
        } catch (RuntimeException ex) {
            // A version conflict means the index already holds a newer revision of
            // this listing. That is the external-versioning guard working as
            // designed, not a failure — swallowing it prevents a pointless retry
            // loop that would never converge.
            if (isVersionConflict(ex)) {
                log.debug("Skipped listing {}: index already holds a newer version", document.getId());
                return;
            }
            throw ex;
        }
    }

    public void deleteFromIndex(UUID propertyId) {
        try {
            operations.delete(propertyId.toString(), PropertyDocument.class);
            log.debug("Removed listing {} from the index", propertyId);
        } catch (RuntimeException ex) {
            // Deleting something already gone is the desired end state.
            if (isNotFound(ex)) {
                return;
            }
            throw ex;
        }
    }

    /**
     * Rebuilds the index from Postgres.
     *
     * <p>The safety net behind the whole pipeline: if the outbox is ever lost, a
     * mapping changes, or the index is dropped, this restores it from the system
     * of record. Postgres is the source of truth; Elasticsearch is derived and
     * always rebuildable.
     *
     * @return how many listings were indexed
     */
    public long reindexAll() {
        IndexOperations indexOps = operations.indexOps(PropertyDocument.class);
        if (!indexOps.exists()) {
            indexOps.createWithMapping();
        }

        long total = 0;
        int pageNumber = 0;
        Page<UUID> page;

        do {
            page = propertyRepository.findIdsByStatus(PropertyStatus.ACTIVE,
                    PageRequest.of(pageNumber, REINDEX_PAGE_SIZE));

            List<UUID> ids = page.getContent();
            if (!ids.isEmpty()) {
                total += batchIndexer.indexBatch(ids);
            }
            pageNumber++;
        } while (page.hasNext());

        indexOps.refresh();
        log.info("Reindex complete: {} listings", total);
        return total;
    }

    /**
     * A separate bean so {@code @Transactional} is honoured. Called from
     * {@link #reindexAll()} on {@code this}, the proxy would be bypassed and each
     * batch would load outside a transaction.
     */
    @Service
    static class BatchIndexer {

        private final ElasticsearchOperations operations;
        private final PropertyRepository propertyRepository;
        private final PropertyDocumentMapper mapper;

        BatchIndexer(ElasticsearchOperations operations, PropertyRepository propertyRepository,
                PropertyDocumentMapper mapper) {
            this.operations = operations;
            this.propertyRepository = propertyRepository;
            this.mapper = mapper;
        }

        @Transactional(readOnly = true)
        int indexBatch(List<UUID> ids) {
            List<Property> properties = propertyRepository.findByIdIn(ids);

            List<IndexQuery> queries = properties.stream()
                    .map(mapper::toDocument)
                    .map(document -> (IndexQuery) new IndexQueryBuilder()
                            .withId(document.getId())
                            .withObject(document)
                            .withVersion(document.getVersion())
                            .build())
                    .toList();

            if (queries.isEmpty()) {
                return 0;
            }
            // Bulk, not one call per listing: a full reindex of a real catalogue
            // is the difference between minutes and hours.
            operations.bulkIndex(queries, PropertyDocument.class);
            return queries.size();
        }
    }

    private static boolean isVersionConflict(RuntimeException ex) {
        return containsAny(ex, "version_conflict", "version conflict", "409");
    }

    private static boolean isNotFound(RuntimeException ex) {
        return containsAny(ex, "index_not_found", "404", "not_found");
    }

    private static boolean containsAny(Throwable throwable, String... needles) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String lower = message.toLowerCase();
            for (String needle : needles) {
                if (lower.contains(needle)) {
                    return true;
                }
            }
        }
        return false;
    }
}
