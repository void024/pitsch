package com.pitsch.backend.idempotency;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Stored outcome of a POST sent with an Idempotency-Key header (replayed for retries with the same key). */
@Entity
@Table(name = "idempotency_records")
public class IdempotencyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(name = "idem_key", nullable = false, length = 100)
    private String key;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(nullable = false, length = 300)
    private String path;

    @Column(nullable = false, length = 64)
    private String requestHash;

    private Integer responseStatus;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getKey() { return key; }
    public void setKey(String v) { this.key = v; }
    public String getMethod() { return method; }
    public void setMethod(String v) { this.method = v; }
    public String getPath() { return path; }
    public void setPath(String v) { this.path = v; }
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String v) { this.requestHash = v; }
    public Integer getResponseStatus() { return responseStatus; }
    public void setResponseStatus(Integer v) { this.responseStatus = v; }
    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String v) { this.responseBody = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
}
