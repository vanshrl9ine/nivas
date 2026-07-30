package com.nivas.savedsearch;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.nivas.savedsearch.dto.SavedSearchDtos.SavedSearchResponse;
import com.nivas.savedsearch.dto.SavedSearchDtos.UpsertSavedSearchRequest;
import com.nivas.search.dto.SearchDtos.SearchResponse;
import com.nivas.security.AppUserPrincipal;
import com.nivas.security.CurrentUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/saved-searches")
@Tag(name = "Saved searches", description = "Stored filter sets, optionally alerting")
public class SavedSearchController {

    private final SavedSearchService savedSearchService;

    public SavedSearchController(SavedSearchService savedSearchService) {
        this.savedSearchService = savedSearchService;
    }

    @GetMapping
    @Operation(summary = "The caller's saved searches, each with a count of new matches")
    public List<SavedSearchResponse> list(@CurrentUser AppUserPrincipal principal) {
        return savedSearchService.list(principal.getId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save the current search criteria")
    public SavedSearchResponse create(@Valid @RequestBody UpsertSavedSearchRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return savedSearchService.create(principal.getId(), request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Rename a saved search or change its alert frequency")
    public SavedSearchResponse update(@PathVariable UUID id,
            @Valid @RequestBody UpsertSavedSearchRequest request,
            @CurrentUser AppUserPrincipal principal) {
        return savedSearchService.update(id, principal.getId(), request);
    }

    @GetMapping("/{id}/results")
    @Operation(summary = "Run a saved search now")
    public SearchResponse run(@PathVariable UUID id, @CurrentUser AppUserPrincipal principal) {
        return savedSearchService.run(id, principal.getId());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a saved search")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser AppUserPrincipal principal) {
        savedSearchService.delete(id, principal.getId());
        return ResponseEntity.noContent().build();
    }
}
