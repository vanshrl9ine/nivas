package com.nivas.security;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.nivas.auth.jwt.JwtAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** Public browsing surface: everything a signed-out visitor may reach. */
    private static final String[] PUBLIC_GET = {
            "/api/v1/properties/**",
            "/api/v1/search/**",
            "/api/v1/localities/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // No cookies are used for authentication, so there is no CSRF
                // vector to protect: a bearer token is not sent automatically by
                // the browser. Disabling it here is a consequence of that design,
                // not a shortcut.
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        // These sit under the otherwise-public /properties tree and
                        // must be matched BEFORE the wildcard below, or the owner's
                        // own dashboard and draft listings would be world-readable.
                        .requestMatchers(HttpMethod.GET, "/api/v1/properties/mine",
                                "/api/v1/properties/*/manage", "/api/v1/properties/*/leads")
                        .authenticated()
                        .requestMatchers(HttpMethod.GET, PUBLIC_GET).permitAll()
                        // Anonymous enquiries are allowed; the endpoint is rate limited instead.
                        .requestMatchers(HttpMethod.POST, "/api/v1/properties/*/leads").permitAll()
                        // Liveness/readiness and the metrics scrape are unauthenticated
                        // because the load balancer and Prometheus cannot hold a token.
                        // In production the management endpoints belong on a separate
                        // port bound to the internal network (management.server.port),
                        // rather than being exposed on the public listener at all.
                        .requestMatchers("/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Unauthorized","status":401,\
                                    "detail":"Authentication is required","code":"unauthenticated"}""");
                        })
                        .accessDeniedHandler((request, response, ex) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                            response.getWriter().write("""
                                    {"type":"about:blank","title":"Forbidden","status":403,\
                                    "detail":"You do not have access to this resource","code":"forbidden"}""");
                        }))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Strength 12 rather than the default 10: roughly 4x the work per hash,
        // which is still well under 250ms on modern hardware and only paid on login.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Local front-end origins. Override per environment before deploying.
        config.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Retry-After", "X-Total-Count"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
