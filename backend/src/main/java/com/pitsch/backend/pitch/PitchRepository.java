package com.pitsch.backend.pitch;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchRepository extends JpaRepository<Pitch, Long> {

    List<Pitch> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<Pitch> findByIdAndUserId(Long id, Long userId);
}
