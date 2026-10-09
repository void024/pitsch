package com.pitsch.backend.idempotency;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ExternalOperationRepository extends JpaRepository<ExternalOperation, Long> {

    Optional<ExternalOperation> findByIdempotencyKey(String idempotencyKey);
}
