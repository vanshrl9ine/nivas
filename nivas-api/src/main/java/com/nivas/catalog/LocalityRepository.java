package com.nivas.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.catalog.domain.Locality;

public interface LocalityRepository extends JpaRepository<Locality, UUID> {

    Optional<Locality> findBySlug(String slug);

    List<Locality> findByCityIgnoreCaseOrderByNameAsc(String city);

    /**
     * Type-ahead over locality names, backed by the trigram index.
     *
     * <p>{@code similarity()} rather than {@code LIKE '%x%'} so "koramangla"
     * still finds "Koramangala" — misspelt locality names are the norm, not the
     * exception. Ordering by similarity puts the best match first.
     */
    @Query(value = """
            SELECT * FROM localities
            WHERE (:city IS NULL OR lower(city) = lower(:city))
              AND (name %> :term OR lower(name) LIKE lower(:term) || '%%')
            ORDER BY similarity(name, :term) DESC, name ASC
            """, nativeQuery = true)
    List<Locality> searchByName(@Param("term") String term, @Param("city") String city, Pageable pageable);

    @Query("SELECT DISTINCT l.city FROM Locality l ORDER BY l.city")
    List<String> findDistinctCities();
}
