package com.pitsch.backend.files;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** Metadata of an object in private storage. The bytes live in StorageProvider under {@code storageKey}. */
@Entity
@Table(name = "stored_files")
public class StoredFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    @Column(nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(nullable = false)
    private String filename;

    @Column(nullable = false, length = 120)
    private String contentType;

    @Column(nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    /** CLEAN | SKIPPED (no scanner configured) — INFECTED files are rejected and never stored. */
    @Column(nullable = false, length = 20)
    private String scanStatus;

    private Integer pageCount;

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String v) { this.storageKey = v; }
    public String getFilename() { return filename; }
    public void setFilename(String v) { this.filename = v; }
    public String getContentType() { return contentType; }
    public void setContentType(String v) { this.contentType = v; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public String getSha256() { return sha256; }
    public void setSha256(String v) { this.sha256 = v; }
    public String getScanStatus() { return scanStatus; }
    public void setScanStatus(String v) { this.scanStatus = v; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer v) { this.pageCount = v; }
    public Instant getCreatedAt() { return createdAt; }
}
