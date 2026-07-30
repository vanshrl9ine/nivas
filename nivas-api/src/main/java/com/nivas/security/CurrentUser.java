package com.nivas.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * Injects the authenticated {@link AppUserPrincipal} into a controller method.
 *
 * <p>Resolves to {@code null} for anonymous requests, so endpoints that are
 * public but personalise when signed in can take it as an optional parameter.
 */
@Target({ ElementType.PARAMETER, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentUser {
}
