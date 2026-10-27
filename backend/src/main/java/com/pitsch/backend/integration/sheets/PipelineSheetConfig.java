package com.pitsch.backend.integration.sheets;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Workspace-level pipeline sheet: which spreadsheet/worksheet, and which Pitsch field goes to which column. */
@Entity
@Table(name = "pipeline_sheet_configs")
public class PipelineSheetConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private Long organizationId;

    @Column(nullable = false)
    private Long connectionId;

    @Column(nullable = false, length = 200)
    private String spreadsheetId;

    @Column(nullable = false, length = 200)
    private String worksheetTitle;

    @Column(nullable = false)
    private int headerRow = 1;

    /** The field identifying a row (always pitchId). */
    @Column(nullable = false, length = 40)
    private String keyField = "pitchId";

    /** JSON array of {"field": PipelineField, "column": "Header text"}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String fieldMappingJson;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getConnectionId() { return connectionId; }
    public void setConnectionId(Long v) { this.connectionId = v; }
    public String getSpreadsheetId() { return spreadsheetId; }
    public void setSpreadsheetId(String v) { this.spreadsheetId = v; }
    public String getWorksheetTitle() { return worksheetTitle; }
    public void setWorksheetTitle(String v) { this.worksheetTitle = v; }
    public int getHeaderRow() { return headerRow; }
    public void setHeaderRow(int v) { this.headerRow = v; }
    public String getKeyField() { return keyField; }
    public void setKeyField(String v) { this.keyField = v; }
    public String getFieldMappingJson() { return fieldMappingJson; }
    public void setFieldMappingJson(String v) { this.fieldMappingJson = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
