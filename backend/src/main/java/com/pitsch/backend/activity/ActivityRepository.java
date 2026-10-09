package com.pitsch.backend.activity;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findTop50ByOrganizationIdOrderByCreatedAtDesc(Long organizationId);

    Page<Activity> findByOrganizationId(Long organizationId, Pageable pageable);
}
