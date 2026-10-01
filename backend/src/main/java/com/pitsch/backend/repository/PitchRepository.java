package com.pitsch.backend.repository;

import com.pitsch.backend.entity.pitch;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PitchRepository extends JpaRepository<pitch, Long> {
}