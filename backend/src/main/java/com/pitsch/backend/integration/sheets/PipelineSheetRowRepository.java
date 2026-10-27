package com.pitsch.backend.integration.sheets;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PipelineSheetRowRepository extends JpaRepository<PipelineSheetRow, Long> {

    Optional<PipelineSheetRow> findByOrganizationIdAndPitchIdAndSpreadsheetIdAndWorksheetTitle(Long organizationId,
                                                                                            Long pitchId, String spreadsheetId,
                                                                                            String worksheetTitle);
}
