package com.nivas.placement;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.nivas.placement.domain.Placement;
import com.nivas.placement.domain.PlacementStatus;
import com.nivas.placement.domain.Surface;

public interface PlacementRepository extends JpaRepository<Placement, UUID> {

    /**
     * Slot indexes already taken on a surface for an overlapping period.
     *
     * <p>Only an advisory read. The exclusion constraint is what actually
     * prevents a double booking — this exists so the API can say "sold out"
     * before taking someone's money, rather than after.
     */
    @Query(value = """
            SELECT slot_index FROM placements
            WHERE surface = :surface
              AND city = :city
              AND status = 'ACTIVE'
              AND period && daterange(:startsOn, :endsOn, '[)')
            """, nativeQuery = true)
    List<Short> takenSlots(@Param("surface") String surface,
            @Param("city") String city,
            @Param("startsOn") LocalDate startsOn,
            @Param("endsOn") LocalDate endsOn);

    /**
     * What is live on a surface right now, best slot first.
     *
     * <p>Drives the home pages. Ordering by slot index means slot 0 is the top
     * position, which is what the hero and the first featured card use.
     */
    @Query(value = """
            SELECT * FROM placements
            WHERE surface = :surface
              AND city = :city
              AND status = 'ACTIVE'
              AND period @> CAST(:onDay AS date)
            ORDER BY slot_index
            """, nativeQuery = true)
    List<Placement> liveOn(@Param("surface") String surface,
            @Param("city") String city,
            @Param("onDay") LocalDate onDay);

    List<Placement> findByPropertyIdAndStatus(UUID propertyId, PlacementStatus status);

    List<Placement> findByOrderId(UUID orderId);

    long countBySurfaceAndCityAndStatus(Surface surface, String city, PlacementStatus status);

    /**
     * Retires bookings whose period has passed.
     *
     * <p>Cosmetic for correctness — the {@code period @> today} filter already
     * excludes them from every read — but it keeps the partial index small and
     * makes "why is this still ACTIVE" a question nobody has to ask.
     */
    @Modifying
    @Query(value = """
            UPDATE placements SET status = 'EXPIRED', updated_at = now()
            WHERE status = 'ACTIVE' AND upper(period) <= CAST(:today AS date)
            """, nativeQuery = true)
    int expireFinished(@Param("today") LocalDate today);
}
