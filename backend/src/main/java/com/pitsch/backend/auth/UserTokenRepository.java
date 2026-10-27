package com.pitsch.backend.auth;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHash(String tokenHash);
}
