package com.pitsch.backend.audit;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/** Append-only audit record. No setters after construction; UPDATE is also blocked by a database trigger. */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    public enum ActorType { USER, SYSTEM, AI }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long organizationId;
    private Long actorUserId;

    @Column(nullable = false, length = 10)
    private String actorType;

    @Column(nullable = false, length = 60)
    private String action;

    @Column(length = 40)
    private String resourceType;

    @Column(length = 64)
    private String resourceId;

    @Column(length = 64)
    private String ipAddress;

    @Column(length = 400)
    private String userAgent;

    @Column(length = 64)
    private String requestId;

    @Column(columnDefinition = "TEXT")
    private String metadataJson;

    @Column(nullable = false)
    private Instant createdAt;

    protected AuditEvent() { }

    public AuditEvent(Long organizationId, Long actorUserId, ActorType actorType, AuditAction action,
                      String resourceType, String resourceId, String ipAddress, String userAgent, String requestId,
                      String metadataJson, Instant createdAt) {
        this.organizationId = organizationId;
        this.actorUserId = actorUserId;
        this.actorType = actorType.name();
        this.action = action.name();
        this.resourceType = resourceType;
        this.resourceId = resourceId;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.requestId = requestId;
        this.metadataJson = metadataJson;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getOrganizationId() { return organizationId; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorType() { return actorType; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getIpAddress() { return ipAddress; }
    public String getUserAgent() { return userAgent; }
    public String getRequestId() { return requestId; }
    public String getMetadataJson() { return metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
}
