package com.pitsch.backend.integration.sheets;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** External row tracking: which sheet row holds which pitch (re-validated by key on every sync). */
@Entity
@Table(name = "pipeline_sheet_rows")
public class PipelineSheetRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false)
    private Long pitchId;

    @Column(nullable = false, length = 200)
    private String spreadsheetId;

    @Column(nullable = false, length = 200)
    private String worksheetTitle;

    @Column(nullable = false)
    private int rowIndex;

    private Instant lastSyncedAt;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getPitchId() { return pitchId; }
    public void setPitchId(Long v) { this.pitchId = v; }
    public String getSpreadsheetId() { return spreadsheetId; }
    public void setSpreadsheetId(String v) { this.spreadsheetId = v; }
    public String getWorksheetTitle() { return worksheetTitle; }
    public void setWorksheetTitle(String v) { this.worksheetTitle = v; }
    public int getRowIndex() { return rowIndex; }
    public void setRowIndex(int v) { this.rowIndex = v; }
    public Instant getLastSyncedAt() { return lastSyncedAt; }
    public void setLastSyncedAt(Instant v) { this.lastSyncedAt = v; }
}
