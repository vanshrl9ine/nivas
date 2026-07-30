package com.nivas.savedsearch;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.savedsearch.domain.AlertFrequency;
import com.nivas.savedsearch.domain.SavedSearch;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, UUID> {

    List<SavedSearch> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<SavedSearch> findByIdAndUserId(UUID id, UUID userId);

    boolean existsByUserIdAndName(UUID userId, String name);

    long countByUserId(UUID userId);

    /**
     * Alerting searches, oldest-alerted first, so a backlog drains fairly rather
     * than repeatedly serving whichever rows the planner happens to return.
     *
     * <p>Whether each one is actually due is decided in the domain
     * ({@link SavedSearch#isDueAt}) rather than in SQL — the interval belongs to
     * {@link AlertFrequency}, and duplicating it in a query is how the two drift
     * apart. This narrows to candidates; the entity makes the call.
     *
     * <p>Backed by {@code ix_saved_searches_due}.
     */
    @Query("""
            SELECT s FROM SavedSearch s
            JOIN FETCH s.user
            WHERE s.alertFrequency <> com.nivas.savedsearch.domain.AlertFrequency.NONE
            ORDER BY s.lastAlertedAt ASC NULLS FIRST
            """)
    List<SavedSearch> findAlertCandidates(Pageable pageable);

    long countByAlertFrequencyNot(AlertFrequency frequency);
}
