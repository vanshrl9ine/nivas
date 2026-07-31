package com.nivas.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nivas.auth.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Exact match. Callers should almost always use {@link #findByEmailNormalised}.
     *
     * <p>The column is {@code citext}, which is case-insensitive — but only when
     * both sides of the comparison are citext. JPA binds a String parameter as
     * varchar, and Postgres then resolves {@code =} as varchar-to-varchar, which
     * is case-sensitive. Verified directly:
     *
     * <pre>
     * SELECT … WHERE email = 'mixedcase@x.test'            -- 1 row
     * SELECT … WHERE email = 'mixedcase@x.test'::varchar   -- 0 rows
     * </pre>
     *
     * So citext still guarantees the unique index is case-insensitive, but it
     * does <em>not</em> make lookups case-insensitive through an ORM.
     */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * The lookup every caller wants: normalises before matching.
     *
     * <p>Addresses are stored lower-cased by {@link User}, so an exact match on a
     * lower-cased argument is correct regardless of what citext would have done.
     */
    default Optional<User> findByEmailNormalised(String email) {
        return email == null ? Optional.empty() : findByEmail(User.normaliseEmail(email));
    }

    default boolean existsByEmailNormalised(String email) {
        return email != null && existsByEmail(User.normaliseEmail(email));
    }
}
