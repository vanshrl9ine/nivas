package com.nivas.common.ratelimit;

import com.nivas.security.AppUserPrincipal;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides who a rate limit applies to.
 *
 * <p>Authenticated callers are keyed by identity so the limit follows them across
 * devices and networks. Anonymous callers fall back to IP, which is imperfect
 * behind shared NAT — but the alternative, a single global bucket, would let one
 * abusive client throttle everybody.
 */
public final class RateLimitSubject {

    private RateLimitSubject() {
    }

    public static String of(AppUserPrincipal principal, HttpServletRequest request) {
        if (principal != null) {
            return "u:" + principal.getId();
        }
        return "ip:" + clientIp(request);
    }

    /**
     * Trusts {@code X-Forwarded-For} because the API is expected to sit behind a
     * reverse proxy that sets it. If it is ever exposed directly, this header
     * becomes client-controlled and the limit trivially bypassable — the proxy
     * must always overwrite rather than append it.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return request.getRemoteAddr();
        }
        return forwarded.split(",")[0].trim();
    }
}
