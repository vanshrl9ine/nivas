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
     * <p>The entity graph is what keeps this to a single round trip. Without it,
     * rendering one listing triggers a select for the owner, another for the
     * locality, another for the images and another for the amenities.
     */
    @EntityGraph(attributePaths = { "owner", "locality", "images", "amenities" })
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

    @EntityGraph(attributePaths = { "owner", "locality", "images", "amenities" })
    List<Property> findByIdIn(List<UUID> ids);
}
