package com.nivas.auth;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.domain.Role;
import com.nivas.auth.domain.User;
import com.nivas.auth.dto.AuthDtos.LoginRequest;
import com.nivas.auth.dto.AuthDtos.RegisterRequest;
import com.nivas.auth.dto.AuthDtos.TokenResponse;
import com.nivas.auth.dto.AuthDtos.UserResponse;
import com.nivas.auth.jwt.JwtService;
import com.nivas.common.exception.ConflictException;
import com.nivas.security.AppUserPrincipal;

import io.jsonwebtoken.JwtException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService,
            RefreshTokenStore refreshTokenStore) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
    }

    @Transactional
    public TokenResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            // The unique index is still the real guard against a concurrent
            // duplicate; this check exists to return a decent message.
            throw new ConflictException("An account with that email already exists");
        }

        // Self-registration cannot mint an ADMIN. Anything unexpected falls back
        // to the least privileged role rather than being trusted.
        Role role = switch (request.role() == null ? Role.SEEKER : request.role()) {
            case OWNER -> Role.OWNER;
            case AGENT -> Role.AGENT;
            default -> Role.SEEKER;
        };

        User user = new User(
                request.email().trim(),
                passwordEncoder.encode(request.password()),
                request.fullName().trim(),
                request.phone() == null || request.phone().isBlank() ? null : request.phone().trim(),
                role);

        User saved = userRepository.save(user);
        log.info("Registered user {} with role {}", saved.getId(), role);
        return issueTokens(AppUserPrincipal.from(saved), UserResponse.from(saved));
    }

    /**
     * Verifies credentials directly rather than going through
     * {@code AuthenticationManager}: the work is a repository lookup and one
     * password comparison, and doing it here keeps the failure mode uniform.
     */
    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim())
                .orElse(null);

        // Always run the encoder, even when the user does not exist, so the
        // response time does not reveal which emails are registered.
        String storedHash = user != null ? user.getPasswordHash() : PLACEHOLDER_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), storedHash);

        if (user == null || !passwordMatches) {
            throw new BadCredentialsException("Invalid email or password");
        }
        if (!user.isEnabled()) {
            throw new BadCredentialsException("This account has been disabled");
        }

        return issueTokens(AppUserPrincipal.from(user), UserResponse.from(user));
    }

    /**
     * Exchanges a refresh token for a new pair, rotating the refresh token.
     *
     * <p>Rotation means a leaked refresh token is only useful until the legitimate
     * client next refreshes, at which point the stolen one is already revoked.
     */
    @Transactional(readOnly = true)
    public TokenResponse refresh(String refreshToken) {
        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parseRefreshToken(refreshToken);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BadCredentialsException("Invalid refresh token");
        }

        if (parsed.tokenId() == null || !refreshTokenStore.isActive(parsed.userId(), parsed.tokenId())) {
            throw new BadCredentialsException("This refresh token is no longer valid");
        }

        User user = userRepository.findById(parsed.userId())
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token"));
        if (!user.isEnabled()) {
            refreshTokenStore.revokeAll(user.getId());
            throw new BadCredentialsException("This account has been disabled");
        }

        refreshTokenStore.revoke(parsed.userId(), parsed.tokenId());
        return issueTokens(AppUserPrincipal.from(user), UserResponse.from(user));
    }

    public void logout(String refreshToken) {
        try {
            JwtService.ParsedToken parsed = jwtService.parseRefreshToken(refreshToken);
            if (parsed.tokenId() != null) {
                refreshTokenStore.revoke(parsed.userId(), parsed.tokenId());
            }
        } catch (JwtException | IllegalArgumentException ex) {
            // Logging out with a token that is already invalid is a success from
            // the caller's point of view; there is nothing left to revoke.
            log.debug("Logout called with an unparseable refresh token: {}", ex.getMessage());
        }
    }

    public long logoutEverywhere(UUID userId) {
        return refreshTokenStore.revokeAll(userId);
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                // The token verified, so the user existed when it was issued.
                // Reaching here means the account was deleted mid-session.
                .orElseThrow(() -> new BadCredentialsException("This account no longer exists"));
    }

    private TokenResponse issueTokens(AppUserPrincipal principal, UserResponse user) {
        UUID tokenId = UUID.randomUUID();
        String accessToken = jwtService.issueAccessToken(principal);
        String refreshToken = jwtService.issueRefreshToken(principal.getId(), tokenId);
        refreshTokenStore.store(principal.getId(), tokenId, jwtService.refreshTokenTtl());

        return TokenResponse.of(accessToken, refreshToken, jwtService.accessTokenTtl().toSeconds(), user);
    }

    /**
     * A real BCrypt hash of a value nobody knows, used to keep the timing of a
     * failed login roughly equal whether or not the account exists.
     */
    private static final String PLACEHOLDER_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";
}
