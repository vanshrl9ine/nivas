package com.nivas.lead;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.lead.domain.Lead;
import com.nivas.lead.domain.LeadStatus;

public interface LeadRepository extends JpaRepository<Lead, UUID> {

    Optional<Lead> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = { "property" })
    Page<Lead> findByPropertyIdOrderByCreatedAtDesc(UUID propertyId, Pageable pageable);

    /** Every lead across all of an owner's listings — the owner's inbox. */
    @EntityGraph(attributePaths = { "property" })
    @Query("SELECT l FROM Lead l WHERE l.property.owner.id = :ownerId ORDER BY l.createdAt DESC")
    Page<Lead> findByOwnerId(@Param("ownerId") UUID ownerId, Pageable pageable);

    long countByPropertyIdAndStatus(UUID propertyId, LeadStatus status);
}
