package com.nivas.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.catalog.domain.Property;
import com.nivas.catalog.domain.PropertyStatus;

public interface PropertyRepository extends JpaRepository<Property, UUID> {

    /**
     * Loads a listing with everything the detail page and the indexer need.
     *
     * <p>Only the to-one associations are in the graph. Adding {@code images} and
     * {@code amenities} as well seems like an obvious win — one round trip
     * instead of three — and silently returns a Cartesian product: joining two
     * collections in one query yields |images| × |amenities| rows, and Hibernate
     * hands back that many duplicated elements. A listing with 4 photos and 4
     * amenities returned 16 images, each repeated four times.
     *
     * <p>That was not merely cosmetic. It multiplied every detail payload, and
     * the indexer derives {@code imageCount} from the same list, so the "has 3+
     * photos" relevance boost was scoring against a fabricated number.
     *
     * <p>The collections are instead {@code @BatchSize}-annotated on the entity,
     * so they load in a couple of extra queries rather than N+1 — and correctly.
     */
    @EntityGraph(attributePaths = { "owner", "locality" })
    @Query("SELECT p FROM Property p WHERE p.id = :id")
    Optional<Property> findByIdWithDetail(@Param("id") UUID id);

    @EntityGraph(attributePaths = { "locality", "images" })
    Page<Property> findByOwnerId(UUID ownerId, Pageable pageable);

    @EntityGraph(attributePaths = { "locality", "images" })
    Page<Property> findByOwnerIdAndStatus(UUID ownerId, PropertyStatus status, Pageable pageable);

    Optional<Property> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * Relative increment, so concurrent flushes compose instead of clobbering.
     *
     * <p>Deliberately does not touch {@code updated_at} or {@code version}: a view
     * is not an edit, and bumping the version would both invalidate other writers'
     * optimistic locks and trigger a pointless reindex.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE properties SET view_count = view_count + :delta WHERE id = :id", nativeQuery = true)
    int incrementViewCount(@Param("id") UUID id, @Param("delta") long delta);

    /** Drives the full-reindex job; ids only, so a large catalogue stays streamable. */
    @Query("SELECT p.id FROM Property p WHERE p.status = :status ORDER BY p.publishedAt DESC")
    Page<UUID> findIdsByStatus(@Param("status") PropertyStatus status, Pageable pageable);

    long countByStatus(PropertyStatus status);

    long countByOwnerId(UUID ownerId);

    /** Same reasoning as {@link #findByIdWithDetail}: to-one only, collections batched. */
    @EntityGraph(attributePaths = { "owner", "locality" })
    List<Property> findByIdIn(List<UUID> ids);
}
