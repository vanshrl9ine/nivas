package com.nivas.catalog;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.analytics.ViewCounterService;
import com.nivas.catalog.domain.PropertyStatus;
import com.nivas.catalog.dto.PropertyDtos.OwnerListingResponse;
import com.nivas.catalog.dto.PropertyDtos.PropertyDetailResponse;
import com.nivas.catalog.dto.PropertyDtos.StatusChangeRequest;
import com.nivas.catalog.dto.PropertyDtos.UpsertPropertyRequest;
import com.nivas.common.PageResponse;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/properties")
@Tag(name = "Properties", description = "Listing lifecycle and detail")
public class PropertyController {

    private final PropertyService propertyService;
    private final ViewCounterService viewCounter;

    public PropertyController(PropertyService propertyService, ViewCounterService viewCounter) {
        this.propertyService = propertyService;
        this.viewCounter = viewCounter;
    }

    @GetMapping("/{propertyId}")
    @Operation(summary = "Public listing detail")
    public PropertyDetailResponse get(@PathVariable UUID propertyId,
            @CurrentUser AppUserPrincipal principal,
            HttpServletRequest request) {

        PropertyDetailResponse response = propertyService.getPublicDetail(propertyId);

        // Recorded after the lookup succeeds, so a 404 does not inflate the count.
        viewCounter.recordView(propertyId, viewerKey(principal, request));
        return response;
    }

    @GetMapping("/mine")
    @Operation(summary = "The caller's own listings, including drafts")
    public PageResponse<OwnerListingResponse> listMine(
            @CurrentUser AppUserPrincipal principal,
            @RequestParam(required = false) PropertyStatus status,
            @PageableDefault(size = 20) Pageable pageable) {
        return propertyService.listMine(principal.getId(), status, pageable);
    }

    @GetMapping("/{propertyId}/manage")
    @Operation(summary = "Owner's view of a listing, including drafts and private fields")
    public PropertyDetailResponse getForOwner(@PathVariable UUID propertyId,
            @CurrentUser AppUserPrincipal principal) {
        return propertyService.getForOwner(propertyId, principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT', 'ADMIN')")
    @Operation(summary = "Create a listing (starts as a draft)")
    public PropertyDetailResponse create(@Valid @RequestBody UpsertPropertyRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return propertyService.create(request, principal);
    }

    @PutMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT', 'ADMIN')")
    @Operation(summary = "Replace a listing's details")
    public PropertyDetailResponse update(@PathVariable UUID propertyId,
            @Valid @RequestBody UpsertPropertyRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return propertyService.update(propertyId, request, principal);
    }

    @PatchMapping("/{propertyId}/status")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT', 'ADMIN')")
    @Operation(summary = "Publish, mark sold/rented, or return to draft")
    public PropertyDetailResponse changeStatus(@PathVariable UUID propertyId,
            @Valid @RequestBody StatusChangeRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return propertyService.changeStatus(propertyId, request.status(), principal);
    }

    @DeleteMapping("/{propertyId}")
    @PreAuthorize("hasAnyRole('OWNER', 'AGENT', 'ADMIN')")
    @Operation(summary = "Archive a listing (soft delete)")
    public ResponseEntity<Void> archive(@PathVariable UUID propertyId,
            @CurrentUser AppUserPrincipal principal) {
        propertyService.archive(propertyId, principal);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{propertyId}/verify")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mark a listing as verified")
    public PropertyDetailResponse verify(@PathVariable UUID propertyId) {
        return propertyService.verify(propertyId);
    }

    /**
     * A stable, non-identifying token for view de-duplication.
     *
     * <p>For signed-in users the id is exact. For anonymous traffic the IP and
     * user agent are hashed rather than stored: the counter only needs to know
     * "same visitor as a moment ago", which does not require keeping anything
     * that identifies them.
     */
    private static String viewerKey(AppUserPrincipal principal, HttpServletRequest request) {
        if (principal != null) {
            return "u:" + principal.getId();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = forwarded == null || forwarded.isBlank()
                ? request.getRemoteAddr()
                : forwarded.split(",")[0].trim();
        String agent = request.getHeader("User-Agent");

        String raw = ip + "|" + (agent == null ? "" : agent);
        return "a:" + shortHash(raw);
    }

    private static String shortHash(String value) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            // 8 bytes is ample for collision avoidance within a 30-minute window
            // and keeps the Redis key small.
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
