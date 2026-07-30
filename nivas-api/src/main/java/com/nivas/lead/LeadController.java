package com.nivas.lead;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.common.PageResponse;
import com.nivas.common.ratelimit.RateLimitSubject;
import com.nivas.common.ratelimit.RateLimiter;
import com.nivas.config.NivasProperties;
import com.nivas.lead.dto.LeadDtos.CreateLeadRequest;
import com.nivas.lead.dto.LeadDtos.LeadAcceptedResponse;
import com.nivas.lead.dto.LeadDtos.LeadResponse;
import com.nivas.lead.dto.LeadDtos.LeadStatusUpdate;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Leads", description = "Enquiries against listings")
public class LeadController {

    private static final String RATE_LIMIT_BUCKET = "lead";

    private final LeadService leadService;
    private final RateLimiter rateLimiter;
    private final NivasProperties properties;

    public LeadController(LeadService leadService, RateLimiter rateLimiter, NivasProperties properties) {
        this.leadService = leadService;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * Open to anonymous callers — requiring an account to ask about a house would
     * cost far more enquiries than the spam it prevents. The defences are instead
     * a tight rate limit and an idempotency key.
     */
    @PostMapping("/properties/{propertyId}/leads")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit an enquiry about a listing")
    public LeadAcceptedResponse create(@PathVariable UUID propertyId,
            @Valid @RequestBody CreateLeadRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @CurrentUser AppUserPrincipal principal,
            HttpServletRequest httpRequest) {

        NivasProperties.RateLimit.Rule rule = properties.rateLimit().lead();
        rateLimiter.consume(RATE_LIMIT_BUCKET, RateLimitSubject.of(principal, httpRequest),
                rule.permits(), rule.window());

        return LeadAcceptedResponse.from(
                leadService.create(propertyId, request, principal, normalise(idempotencyKey)));
    }

    @GetMapping("/properties/{propertyId}/leads")
    @Operation(summary = "Enquiries for one listing (owner or admin only)")
    public PageResponse<LeadResponse> listForProperty(@PathVariable UUID propertyId,
            @CurrentUser AppUserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return leadService.listForProperty(propertyId, principal, pageable);
    }

    @GetMapping("/leads")
    @Operation(summary = "Every enquiry across the caller's listings")
    public PageResponse<LeadResponse> inbox(@CurrentUser AppUserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return leadService.listForOwner(principal.getId(), pageable);
    }

    @PatchMapping("/leads/{leadId}")
    @Operation(summary = "Move an enquiry through the funnel")
    public LeadResponse updateStatus(@PathVariable UUID leadId,
            @Valid @RequestBody LeadStatusUpdate request,
            @CurrentUser AppUserPrincipal principal) {
        return leadService.updateStatus(leadId, request.status(), principal);
    }

    /** The column is 80 characters; a longer header is truncated rather than rejected. */
    private static String normalise(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        String trimmed = idempotencyKey.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }
}
