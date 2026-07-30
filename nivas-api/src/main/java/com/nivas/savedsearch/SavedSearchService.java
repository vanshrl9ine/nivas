package com.nivas.savedsearch;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;
import com.nivas.auth.domain.User;
import com.nivas.common.exception.BadRequestException;
import com.nivas.common.exception.ConflictException;
import com.nivas.common.exception.NotFoundException;
import com.nivas.savedsearch.domain.SavedSearch;
import com.nivas.savedsearch.dto.SavedSearchDtos.SavedSearchResponse;
import com.nivas.savedsearch.dto.SavedSearchDtos.UpsertSavedSearchRequest;
import com.nivas.search.PropertySearchService;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SearchResponse;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class SavedSearchService {

    private static final Logger log = LoggerFactory.getLogger(SavedSearchService.class);

    /**
     * A cap, not a business rule. Saved searches are evaluated on a schedule, so
     * an unbounded number per user is a self-inflicted denial of service.
     */
    private static final int MAX_PER_USER = 25;

    private final SavedSearchRepository repository;
    private final UserRepository userRepository;
    private final PropertySearchService searchService;
    private final ObjectMapper objectMapper;

    public SavedSearchService(SavedSearchRepository repository, UserRepository userRepository,
            PropertySearchService searchService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.searchService = searchService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public SavedSearchResponse create(UUID userId, UpsertSavedSearchRequest request) {
        if (repository.countByUserId(userId) >= MAX_PER_USER) {
            throw new BadRequestException(
                    "You can keep at most " + MAX_PER_USER + " saved searches. Delete one to add another.");
        }
        if (repository.existsByUserIdAndName(userId, request.name().trim())) {
            throw new ConflictException("You already have a saved search called \"" + request.name().trim() + "\"");
        }

        User user = userRepository.getReferenceById(userId);
        SavedSearch saved = repository.save(new SavedSearch(
                user, request.name().trim(), serialise(request.criteria()), request.alertFrequency()));

        log.info("Saved search {} created for user {}", saved.getId(), userId);
        return toResponse(saved, true);
    }

    @Transactional
    public SavedSearchResponse update(UUID id, UUID userId, UpsertSavedSearchRequest request) {
        SavedSearch saved = requireOwned(id, userId);
        saved.update(request.name().trim(), serialise(request.criteria()), request.alertFrequency());
        return toResponse(repository.save(saved), true);
    }

    @Transactional(readOnly = true)
    public List<SavedSearchResponse> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                // Each response costs one Elasticsearch count, so this is N queries
                // for N saved searches. Bounded by MAX_PER_USER and only paid on a
                // page the user opens deliberately.
                .map(saved -> toResponse(saved, true))
                .toList();
    }

    /** Runs a saved search now and returns the results, as if the user re-ran it. */
    @Transactional(readOnly = true)
    public SearchResponse run(UUID id, UUID userId) {
        return searchService.search(deserialise(requireOwned(id, userId).getCriteria()));
    }

    @Transactional
    public void delete(UUID id, UUID userId) {
        repository.delete(requireOwned(id, userId));
    }

    private SavedSearch requireOwned(UUID id, UUID userId) {
        // Scoping the lookup by user is deliberate: a plain findById plus an
        // ownership check leaks existence through the difference between 403 and 404.
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> NotFoundException.of("Saved search", id));
    }

    SavedSearchResponse toResponse(SavedSearch saved, boolean includeNewMatches) {
        SearchRequest criteria = deserialise(saved.getCriteria());
        long newMatches = includeNewMatches ? countNewMatches(saved, criteria) : 0;

        return new SavedSearchResponse(
                saved.getId(),
                saved.getName(),
                criteria,
                saved.getAlertFrequency(),
                saved.getLastAlertedAt(),
                saved.getCreatedAt(),
                newMatches);
    }

    /** How many matches have appeared since the user was last told about this search. */
    long countNewMatches(SavedSearch saved, SearchRequest criteria) {
        try {
            return searchService.search(withWatermark(criteria, saved)).results().totalElements();
        } catch (RuntimeException ex) {
            // A badge on a list page is never worth failing the page for.
            log.warn("Could not count new matches for saved search {}", saved.getId(), ex);
            return 0;
        }
    }

    /**
     * Rewrites the stored criteria to ask only for what is new.
     *
     * <p>Page size is forced to zero because only the total is wanted — fetching
     * documents to then discard them is pure waste when there are hundreds of
     * saved searches to evaluate.
     */
    static SearchRequest withWatermark(SearchRequest criteria, SavedSearch saved) {
        return new SearchRequest(
                criteria.q(), criteria.city(), criteria.localitySlugs(), criteria.listingType(),
                criteria.propertyTypes(), criteria.minPrice(), criteria.maxPrice(), criteria.bedrooms(),
                criteria.minAreaSqft(), criteria.maxAreaSqft(), criteria.furnishing(), criteria.amenities(),
                criteria.verifiedOnly(),
                saved.alertWatermark(),
                criteria.lat(), criteria.lon(), criteria.radiusKm(),
                criteria.topLeftLat(), criteria.topLeftLon(), criteria.bottomRightLat(),
                criteria.bottomRightLon(),
                criteria.sort(), 0, 1);
    }

    private String serialise(SearchRequest criteria) {
        try {
            return objectMapper.writeValueAsString(criteria);
        } catch (JacksonException ex) {
            throw new BadRequestException("Those search criteria could not be saved");
        }
    }

    SearchRequest deserialise(String criteria) {
        try {
            return objectMapper.readValue(criteria, SearchRequest.class);
        } catch (JacksonException ex) {
            // Stored by an older version of the app whose shape no longer parses.
            throw new IllegalStateException("Stored search criteria are no longer readable", ex);
        }
    }
}
