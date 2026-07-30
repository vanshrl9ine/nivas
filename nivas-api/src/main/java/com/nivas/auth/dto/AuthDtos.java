package com.nivas.auth.dto;

import java.time.Instant;
import java.util.UUID;

import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request and response shapes for the auth endpoints. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Email @Size(max = 254) String email,

            // 12 rather than the customary 8: this is the only credential in the
            // system and length dominates every other password rule.
            @NotBlank @Size(min = 12, max = 128,
                    message = "Password must be between 12 and 128 characters") String password,

            @NotBlank @Size(max = 120) String fullName,

            @Pattern(regexp = "^$|^\\+?[0-9]{7,15}$",
                    message = "Phone must be 7-15 digits, optionally starting with +") String phone,

            /** Optional; anything other than OWNER or AGENT is treated as SEEKER. */
            Role role) {
    }

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokenResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresInSeconds,
            UserResponse user) {

        public static TokenResponse of(String accessToken, String refreshToken, long expiresInSeconds,
                UserResponse user) {
            return new TokenResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user);
        }
    }

    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            Role role,
            boolean emailVerified,
            Instant createdAt) {

        public static UserResponse from(User user) {
            return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                    user.getRole(), user.isEmailVerified(), user.getCreatedAt());
        }
    }
}
