package com.nivas.savedsearch.dto;

import java.time.Instant;
import java.util.UUID;

import com.nivas.savedsearch.domain.AlertFrequency;
import com.nivas.search.dto.SearchDtos.SearchRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SavedSearchDtos {

    private SavedSearchDtos() {
    }

    public record UpsertSavedSearchRequest(
            @NotBlank @Size(max = 120) String name,
            @Valid @NotNull SearchRequest criteria,
            AlertFrequency alertFrequency) {
    }

    public record SavedSearchResponse(
            UUID id,
            String name,
            SearchRequest criteria,
            AlertFrequency alertFrequency,
            Instant lastAlertedAt,
            Instant createdAt,
            /** Listings matching this search that the user has not been alerted about yet. */
            long newMatchCount) {
    }
}
