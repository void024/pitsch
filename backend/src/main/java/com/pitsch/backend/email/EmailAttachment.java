package com.pitsch.backend.email;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "email_attachments")
public class EmailAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long organizationId;

    private Long emailId;
    private String filename;
    private String mimeType;
    private long sizeBytes;

    /** Object-storage reference (all new uploads). */
    private Long storedFileId;

    @Column(length = 64)
    private String contentSha256;

    /** Legacy rows only (pre-upgrade uploads kept base64 in the database). Never returned by APIs. */
    @Column(columnDefinition = "TEXT")
    private String contentBase64;

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public void setOrganizationId(Long v) { this.organizationId = v; }
    public Long getEmailId() { return emailId; }
    public void setEmailId(Long emailId) { this.emailId = emailId; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public Long getStoredFileId() { return storedFileId; }
    public void setStoredFileId(Long v) { this.storedFileId = v; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String v) { this.contentSha256 = v; }
    public String getContentBase64() { return contentBase64; }
    public void setContentBase64(String contentBase64) { this.contentBase64 = contentBase64; }
}
