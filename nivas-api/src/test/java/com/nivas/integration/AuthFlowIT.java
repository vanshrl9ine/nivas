package com.nivas.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;

import com.nivas.auth.AuthService;
import com.nivas.auth.domain.Role;
import com.nivas.auth.dto.AuthDtos.LoginRequest;
import com.nivas.auth.dto.AuthDtos.RegisterRequest;
import com.nivas.auth.dto.AuthDtos.TokenResponse;
import com.nivas.common.exception.ConflictException;
import com.nivas.support.IntegrationTest;

@IntegrationTest
class AuthFlowIT {

    @Autowired
    private AuthService authService;

    private static RegisterRequest registration(String email, Role role) {
        return new RegisterRequest(email, "a-sufficiently-long-password", "Test Person", "+919876543210", role);
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@nivas.test";
    }

    @Test
    @DisplayName("register then log in with the same credentials")
    void registerAndLogin() {
        String email = uniqueEmail();
        TokenResponse registered = authService.register(registration(email, Role.OWNER));

        assertThat(registered.accessToken()).isNotBlank();
        assertThat(registered.refreshToken()).isNotBlank();
        assertThat(registered.user().role()).isEqualTo(Role.OWNER);

        TokenResponse loggedIn = authService.login(
                new LoginRequest(email, "a-sufficiently-long-password"));
        assertThat(loggedIn.user().id()).isEqualTo(registered.user().id());
    }

    @Test
    @DisplayName("self-registration cannot mint an admin")
    void cannotSelfRegisterAsAdmin() {
        TokenResponse response = authService.register(registration(uniqueEmail(), Role.ADMIN));

        assertThat(response.user().role())
                .as("an ADMIN request must fall back to the least privileged role")
                .isEqualTo(Role.SEEKER);
    }

    @Test
    @DisplayName("registering the same email twice conflicts")
    void duplicateEmailRejected() {
        String email = uniqueEmail();
        authService.register(registration(email, Role.SEEKER));

        assertThatThrownBy(() -> authService.register(registration(email, Role.SEEKER)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("email matching is case-insensitive, thanks to the citext column")
    void emailIsCaseInsensitive() {
        String email = "MixedCase-" + UUID.randomUUID() + "@Nivas.Test";
        authService.register(registration(email, Role.SEEKER));

        assertThat(authService.login(new LoginRequest(email.toLowerCase(), "a-sufficiently-long-password")))
                .isNotNull();
    }

    @Test
    @DisplayName("a wrong password is rejected")
    void wrongPasswordRejected() {
        String email = uniqueEmail();
        authService.register(registration(email, Role.SEEKER));

        assertThatThrownBy(() -> authService.login(new LoginRequest(email, "not-the-right-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("an unknown email fails the same way a wrong password does")
    void unknownEmailIsIndistinguishable() {
        assertThatThrownBy(() -> authService.login(new LoginRequest(uniqueEmail(), "whatever-password")))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessageContaining("Invalid email or password");
    }

    @Test
    @DisplayName("refreshing rotates the token and revokes the old one")
    void refreshRotatesToken() {
        TokenResponse initial = authService.register(registration(uniqueEmail(), Role.SEEKER));

        TokenResponse refreshed = authService.refresh(initial.refreshToken());
        assertThat(refreshed.refreshToken()).isNotEqualTo(initial.refreshToken());

        // The original must be dead the moment it has been exchanged, otherwise
        // a stolen refresh token stays usable alongside the legitimate one.
        assertThatThrownBy(() -> authService.refresh(initial.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("logout revokes the refresh token")
    void logoutRevokes() {
        TokenResponse tokens = authService.register(registration(uniqueEmail(), Role.SEEKER));

        authService.logout(tokens.refreshToken());

        assertThatThrownBy(() -> authService.refresh(tokens.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("logout-all invalidates every outstanding session")
    void logoutEverywhere() {
        String email = uniqueEmail();
        TokenResponse first = authService.register(registration(email, Role.SEEKER));
        TokenResponse second = authService.login(new LoginRequest(email, "a-sufficiently-long-password"));

        authService.logoutEverywhere(first.user().id());

        assertThatThrownBy(() -> authService.refresh(first.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> authService.refresh(second.refreshToken()))
                .isInstanceOf(BadCredentialsException.class);
    }
}
