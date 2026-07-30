package com.nivas.auth;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.nivas.auth.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Case-insensitivity comes from the citext column, not from a lower() call here. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
