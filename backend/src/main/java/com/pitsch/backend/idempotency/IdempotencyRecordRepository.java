package com.pitsch.backend.idempotency;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByUserIdAndKey(Long userId, String key);

    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
