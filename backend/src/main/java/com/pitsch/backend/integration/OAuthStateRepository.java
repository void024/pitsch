package com.pitsch.backend.integration;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {

    @Modifying
    @Query("delete from OAuthState s where s.expiresAt < :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("delete from OAuthState s where s.organizationId = :orgId")
    int deleteByOrganization(@Param("orgId") Long orgId);
}
