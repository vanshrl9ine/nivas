package com.nivas.placement;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.billing.domain.SalesPackage;
import com.nivas.common.exception.ConflictException;
import com.nivas.placement.domain.Placement;
import com.nivas.placement.domain.PlacementStatus;
import com.nivas.placement.domain.Surface;

/**
 * Books and reads placement inventory.
 *
 * <p>The whole model rests on one idea: an audience maximizer is not a special
 * kind of booking, it is a booking of <em>all</em> the slots on a surface. That
 * means exclusivity needs no code — once the slots are gone there is nothing
 * left to sell, and the exclusion constraint enforces it against concurrent
 * buyers for free.
 */
@Service
public class PlacementService {

    private static final Logger log = LoggerFactory.getLogger(PlacementService.class);

    private final PlacementRepository placementRepository;
    private final JdbcClient jdbcClient;

    public PlacementService(PlacementRepository placementRepository, JdbcClient jdbcClient) {
        this.placementRepository = placementRepository;
        this.jdbcClient = jdbcClient;
    }

    /**
     * Which slots are free on a surface for a period.
     *
     * <p>Advisory only. It exists so a buyer is told "sold out" before paying
     * rather than after, but it is not the guarantee — between this call and the
     * booking, somebody else may take the last slot. The database settles that.
     */
    @Transactional(readOnly = true)
    public Availability availability(Surface surface, String city, LocalDate from, LocalDate to) {
        String cityKey = surface.cityKey(city);
        int capacity = capacityOf(surface);

        Set<Short> taken = Set.copyOf(
                placementRepository.takenSlots(surface.name(), cityKey, from, to));

        List<Short> free = new ArrayList<>();
        for (short slot = 0; slot < capacity; slot++) {
            if (!taken.contains(slot)) {
                free.add(slot);
            }
        }
        return new Availability(surface, cityKey, capacity, free);
    }

    /**
     * Books every slot a package entitles the buyer to.
     *
     * <p>All-or-nothing, and deliberately so: an audience maximizer that got the
     * hero but only four of six featured slots is not what was sold. The whole
     * method runs in one transaction, so a conflict on the last slot rolls back
     * the rest.
     *
     * @throws ConflictException when the inventory was taken, including by a
     *                           concurrent buyer between the availability check
     *                           and the insert
     */
    @Transactional
    public List<Placement> book(SalesPackage salesPackage, UUID propertyId, UUID orderId,
            String city, LocalDate from, LocalDate to) {

        List<Placement> booked = new ArrayList<>();

        for (Map.Entry<Surface, Short> entry : salesPackage.getSlotsPerSurface().entrySet()) {
            Surface surface = entry.getKey();
            int wanted = entry.getValue();
            String cityKey = surface.cityKey(city);

            Availability availability = availability(surface, city, from, to);
            if (availability.freeSlots().size() < wanted) {
                throw new ConflictException(
                        "Only %d of %d slots are free on %s%s between %s and %s."
                                .formatted(availability.freeSlots().size(), wanted, surface,
                                        cityKey.isEmpty() ? "" : " in " + cityKey, from, to));
            }

            for (int i = 0; i < wanted; i++) {
                short slot = availability.freeSlots().get(i);
                booked.add(insert(propertyId, orderId, salesPackage.getId(), surface, cityKey, slot, from, to));
            }
        }

        log.info("Booked {} slot(s) for listing {} on order {}", booked.size(), propertyId, orderId);
        return booked;
    }

    /**
     * Inserted through JdbcClient rather than the repository.
     *
     * <p>{@code period} is a generated column, so Hibernate must not try to
     * write it — and the returned id is needed immediately. A short explicit
     * insert is clearer here than fighting the mapping.
     */
    private Placement insert(UUID propertyId, UUID orderId, UUID packageId, Surface surface,
            String cityKey, short slot, LocalDate from, LocalDate to) {
        try {
            UUID id = jdbcClient.sql("""
                    INSERT INTO placements
                        (property_id, order_id, package_id, surface, city, slot_index, starts_on, ends_on)
                    VALUES (:propertyId, :orderId, :packageId, :surface, :city, :slot, :startsOn, :endsOn)
                    RETURNING id
                    """)
                    .param("propertyId", propertyId)
                    .param("orderId", orderId)
                    .param("packageId", packageId)
                    .param("surface", surface.name())
                    .param("city", cityKey)
                    .param("slot", slot)
                    .param("startsOn", from)
                    .param("endsOn", to)
                    .query(UUID.class)
                    .single();

            return placementRepository.findById(id).orElseThrow();

        } catch (DataIntegrityViolationException ex) {
            // The exclusion constraint fired, which means somebody bought this
            // slot between the availability check and now. That is the race the
            // constraint exists for, and it is the correct outcome.
            throw new ConflictException(
                    "That slot was taken while you were checking out. Please pick another period.");
        }
    }

    /** Everything live on a surface today, best position first. */
    @Transactional(readOnly = true)
    public List<Placement> live(Surface surface, String city, LocalDate onDay) {
        return placementRepository.liveOn(surface.name(), surface.cityKey(city), onDay);
    }

    /** Listing ids currently holding a slot on a surface, in slot order. */
    @Transactional(readOnly = true)
    public List<UUID> livePropertyIds(Surface surface, String city, LocalDate onDay) {
        return live(surface, city, onDay).stream()
                .map(Placement::getPropertyId)
                .distinct()
                .toList();
    }

    /**
     * Whether a city's home page is under an exclusive takeover.
     *
     * <p>True when one listing holds every slot on the hero. Nothing sets a
     * "takeover" flag — this is derived from the inventory, which is the point
     * of modelling it as slots.
     */
    @Transactional(readOnly = true)
    public boolean isTakenOver(String city, LocalDate onDay) {
        List<Placement> hero = live(Surface.CITY_HOME_HERO, city, onDay);
        return hero.size() >= capacityOf(Surface.CITY_HOME_HERO)
                && hero.stream().map(Placement::getPropertyId).distinct().count() == 1;
    }

    @Transactional
    public void cancelForOrder(UUID orderId) {
        List<Placement> placements = placementRepository.findByOrderId(orderId);
        placements.forEach(Placement::cancel);
        placementRepository.saveAll(placements);
        if (!placements.isEmpty()) {
            log.info("Released {} slot(s) held by order {}", placements.size(), orderId);
        }
    }

    @Transactional
    public int expireFinished() {
        return placementRepository.expireFinished(LocalDate.now());
    }

    @Transactional(readOnly = true)
    public List<Placement> activeFor(UUID propertyId) {
        return placementRepository.findByPropertyIdAndStatus(propertyId, PlacementStatus.ACTIVE);
    }

    /**
     * Capacity comes from the database, not from the enum.
     *
     * <p>Widening the featured rail from six slots to eight is a commercial
     * decision; it should not need a deploy.
     */
    @Transactional(readOnly = true)
    public int capacityOf(Surface surface) {
        return jdbcClient.sql("SELECT capacity FROM placement_surfaces WHERE code = :code")
                .param("code", surface.name())
                .query(Integer.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "Surface " + surface + " has no row in placement_surfaces"));
    }

    /** Capacity for every surface, for the pricing page. */
    @Transactional(readOnly = true)
    public Map<Surface, Integer> capacities() {
        Map<Surface, Integer> result = new LinkedHashMap<>();
        for (Surface surface : Surface.values()) {
            result.put(surface, capacityOf(surface));
        }
        return result;
    }

    public record Availability(Surface surface, String city, int capacity, List<Short> freeSlots) {

        public boolean soldOut() {
            return freeSlots.isEmpty();
        }

        public int freeCount() {
            return freeSlots.size();
        }
    }

    /** Convenience for the pricing page: what a package would cost in inventory terms. */
    public Map<Surface, Availability> availabilityFor(SalesPackage salesPackage, String city,
            LocalDate from, LocalDate to) {
        return salesPackage.getSlotsPerSurface().keySet().stream()
                .collect(Collectors.toMap(
                        surface -> surface,
                        surface -> availability(surface, city, from, to),
                        (a, b) -> a,
                        LinkedHashMap::new));
    }
}
