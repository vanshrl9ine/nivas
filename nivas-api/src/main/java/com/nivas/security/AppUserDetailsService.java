package com.nivas.security;

import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.nivas.auth.UserRepository;

/**
 * Used only on the login path. Authenticated requests are resolved from JWT
 * claims by {@link com.nivas.auth.jwt.JwtAuthenticationFilter}, so this does not
 * run per request.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public AppUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public AppUserPrincipal loadUserByUsername(String email) throws UsernameNotFoundException {
        return userRepository.findByEmail(email)
                .map(AppUserPrincipal::from)
                // Same message whether the account is missing or the password is
                // wrong: distinguishing them turns login into a user enumeration oracle.
                .orElseThrow(() -> new UsernameNotFoundException("Invalid email or password"));
    }
}
