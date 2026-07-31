package com.nivas.search;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.common.ratelimit.RateLimitSubject;
import com.nivas.common.ratelimit.RateLimiter;
import com.nivas.config.NivasProperties;
import com.nivas.search.dto.SearchDtos.PropertySummary;
import com.nivas.search.dto.SearchDtos.SuggestionResponse;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Discovery", description = "Type-ahead, trending and similar listings")
public class DiscoveryController {

    private static final String SUGGEST_BUCKET = "suggest";
    private static final int MAX_TRENDING = 12;

    private final PropertyDiscoveryService discoveryService;
    private final RateLimiter rateLimiter;
    private final NivasProperties properties;

    public DiscoveryController(PropertyDiscoveryService discoveryService, RateLimiter rateLimiter,
            NivasProperties properties) {
        this.discoveryService = discoveryService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Type-ahead suggestions.
     *
     * <p>Fires on every keystroke, so it gets its own rate-limit bucket rather
     * than sharing the search one — a user typing "koramangala" would otherwise
     * burn eleven of their sixty search permits before pressing enter. The
     * allowance is generous for the same reason.
     */
    @GetMapping("/search/suggest")
    @Operation(summary = "Completion suggestions for a partial query")
    public SuggestionResponse suggest(
            @RequestParam @Size(min = 1, max = 80) String q,
            @RequestParam(required = false) String city,
            @CurrentUser AppUserPrincipal principal,
            HttpServletRequest httpRequest) {

        NivasProperties.RateLimit.Rule rule = properties.rateLimit().suggest();
        rateLimiter.consume(SUGGEST_BUCKET, RateLimitSubject.of(principal, httpRequest),
                rule.permits(), rule.window());

        return discoveryService.suggest(q, city);
    }

    /**
     * The most-viewed listings today.
     *
     * <p>Ranked by the live Redis counter, not the {@code viewCount} in the
     * index — that one is only as fresh as the last reindex.
     */
    @GetMapping("/search/trending")
    @Operation(summary = "Most-viewed listings today")
    public List<PropertySummary> trending(
            @RequestParam(defaultValue = "6") @Min(1) @Max(MAX_TRENDING) int limit) {
        return discoveryService.trending(limit);
    }

    @GetMapping("/properties/{propertyId}/similar")
    @Operation(summary = "Listings comparable to this one, by text, distance and price")
    public List<PropertySummary> similar(@PathVariable UUID propertyId) {
        return discoveryService.similarTo(propertyId);
    }
}
