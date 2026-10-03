package com.pitsch.backend.email;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailRepository extends JpaRepository<Email, Long> {

    List<Email> findByUserIdOrderByReceivedAtDesc(Long userId);

    Optional<Email> findByIdAndUserId(Long id, Long userId);
}
