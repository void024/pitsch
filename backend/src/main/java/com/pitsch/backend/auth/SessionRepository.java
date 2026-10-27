package com.pitsch.backend.auth;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends JpaRepository<Session, String> {

    List<Session> findByUserIdAndRevokedAtIsNullAndExpiresAtAfterOrderByLastSeenAtDesc(Long userId, Instant now);

    @Modifying
    @Query("update Session s set s.revokedAt = :now, s.revokeReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null and s.id <> :keepId")
    int revokeAllExcept(@Param("userId") Long userId, @Param("keepId") String keepId, @Param("now") Instant now,
                        @Param("reason") String reason);

    @Modifying
    @Query("delete from Session s where s.expiresAt < :cutoff")
    int deleteExpiredBefore(@Param("cutoff") Instant cutoff);
}
