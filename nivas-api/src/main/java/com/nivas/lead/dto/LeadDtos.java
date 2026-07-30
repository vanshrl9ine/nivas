package com.nivas.lead.dto;

import java.time.Instant;
import java.util.UUID;

import com.nivas.lead.domain.Lead;
import com.nivas.lead.domain.LeadStatus;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class LeadDtos {

    private LeadDtos() {
    }

    public record CreateLeadRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{7,15}$",
                    message = "Phone must be 7-15 digits, optionally starting with +") String phone,
            @Size(max = 1000) String message,
            @Size(max = 32) String source) {
    }

    public record LeadStatusUpdate(@NotNull LeadStatus status) {
    }

    public record LeadResponse(
            UUID id,
            UUID propertyId,
            String propertyTitle,
            String name,
            String email,
            String phone,
            String message,
            LeadStatus status,
            String source,
            Instant createdAt) {

        public static LeadResponse from(Lead lead) {
            return new LeadResponse(
                    lead.getId(),
                    lead.getProperty().getId(),
                    lead.getProperty().getTitle(),
                    lead.getName(),
                    lead.getEmail(),
                    lead.getPhone(),
                    lead.getMessage(),
                    lead.getStatus(),
                    lead.getSource(),
                    lead.getCreatedAt());
        }
    }

    /** What the enquirer gets back — deliberately no owner contact details. */
    public record LeadAcceptedResponse(UUID id, LeadStatus status, Instant createdAt) {

        public static LeadAcceptedResponse from(Lead lead) {
            return new LeadAcceptedResponse(lead.getId(), lead.getStatus(), lead.getCreatedAt());
        }
    }
}
