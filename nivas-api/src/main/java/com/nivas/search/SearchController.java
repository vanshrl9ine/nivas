package com.nivas.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.common.ratelimit.RateLimitSubject;
import com.nivas.common.ratelimit.RateLimiter;
import com.nivas.config.NivasProperties;
import com.nivas.search.dto.SearchDtos.SearchRequest;
import com.nivas.search.dto.SearchDtos.SearchResponse;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "Search", description = "Faceted, geo-aware listing search")
public class SearchController {

    private static final String RATE_LIMIT_BUCKET = "search";

    private final PropertySearchService searchService;
    private final RateLimiter rateLimiter;
    private final NivasProperties properties;

    public SearchController(PropertySearchService searchService, RateLimiter rateLimiter,
            NivasProperties properties) {
        this.searchService = searchService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Search is public and unauthenticated, which makes it the cheapest endpoint
     * to abuse — and the most expensive to serve. It is rate limited per caller
     * before any Elasticsearch work happens.
     */
    @GetMapping
    @Operation(summary = "Search listings with filters, facets and geo constraints")
    public SearchResponse search(@Valid SearchRequest request,
            @CurrentUser AppUserPrincipal principal,
            HttpServletRequest httpRequest) {

        NivasProperties.RateLimit.Rule rule = properties.rateLimit().search();
        rateLimiter.consume(RATE_LIMIT_BUCKET, RateLimitSubject.of(principal, httpRequest),
                rule.permits(), rule.window());

        return searchService.search(request);
    }
}
