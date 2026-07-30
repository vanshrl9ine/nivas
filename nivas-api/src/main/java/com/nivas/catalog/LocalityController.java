package com.nivas.catalog;

import java.util.List;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.catalog.domain.Locality;
import com.nivas.catalog.dto.PropertyDtos.LocalityResponse;
import com.nivas.config.CacheNames;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/localities")
@Tag(name = "Localities", description = "Reference data for location filters")
public class LocalityController {

    private static final int MAX_SUGGESTIONS = 10;

    private final LocalityRepository localityRepository;

    public LocalityController(LocalityRepository localityRepository) {
        this.localityRepository = localityRepository;
    }

    @GetMapping("/cities")
    @Cacheable(cacheNames = CacheNames.LOCALITIES, key = "'cities'")
    @Operation(summary = "Every city with at least one locality")
    public List<String> cities() {
        return localityRepository.findDistinctCities();
    }

    @GetMapping
    @Cacheable(cacheNames = CacheNames.LOCALITIES, key = "'city:' + #city")
    @Operation(summary = "All localities in a city")
    public List<LocalityResponse> byCity(@RequestParam String city) {
        return localityRepository.findByCityIgnoreCaseOrderByNameAsc(city).stream()
                .map(LocalityController::toResponse)
                .toList();
    }

    /**
     * Type-ahead for the location box. Not cached: the key space is every prefix
     * a user might type, so the hit rate would be poor and the memory cost high.
     * The trigram index makes the query cheap enough not to need it.
     */
    @GetMapping("/suggest")
    @Operation(summary = "Fuzzy locality suggestions for a partial name")
    public List<LocalityResponse> suggest(
            @RequestParam @Size(min = 2, max = 60) String q,
            @RequestParam(required = false) String city) {
        return localityRepository.searchByName(q.trim(), city, PageRequest.of(0, MAX_SUGGESTIONS)).stream()
                .map(LocalityController::toResponse)
                .toList();
    }

    private static LocalityResponse toResponse(Locality locality) {
        return new LocalityResponse(locality.getId(), locality.getSlug(), locality.getName(),
                locality.getCity(), locality.getState());
    }
}
