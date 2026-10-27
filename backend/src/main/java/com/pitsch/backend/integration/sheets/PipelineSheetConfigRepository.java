package com.pitsch.backend.integration.sheets;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineSheetConfigRepository extends JpaRepository<PipelineSheetConfig, Long> {

    Optional<PipelineSheetConfig> findByOrganizationId(Long organizationId);
}
