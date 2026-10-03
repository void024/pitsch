package com.pitsch.backend.activity;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findTop50ByUserIdOrderByCreatedAtDesc(Long userId);
}
