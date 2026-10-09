package com.pitsch.backend.audit;

import java.time.Clock;
import java.util.Map;

import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes immutable audit events, enriched with the request ID, client IP and user agent of the current request.
 * Metadata must never contain secrets, tokens, passwords or email/document content — callers pass IDs and counts.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repo;
    private final Json json;
    private final Clock clock;

    public AuditService(AuditEventRepository repo, Json json, Clock clock) {
        this.repo = repo;
        this.json = json;
        this.clock = clock;
    }

    /** Recorded in the caller's transaction (rolled back together with the business change). */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Long organizationId, Long actorUserId, AuditAction action, String resourceType,
                       Object resourceId, Map<String, ?> metadata) {
        save(organizationId, actorUserId, actorUserId == null ? AuditEvent.ActorType.SYSTEM : AuditEvent.ActorType.USER,
                action, resourceType, resourceId, metadata);
    }

    /** Recorded even if the surrounding transaction rolls back (security events such as failed logins). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(Long organizationId, Long actorUserId, AuditAction action, String resourceType,
                                  Object resourceId, Map<String, ?> metadata) {
        save(organizationId, actorUserId, actorUserId == null ? AuditEvent.ActorType.SYSTEM : AuditEvent.ActorType.USER,
                action, resourceType, resourceId, metadata);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAi(Long organizationId, AuditAction action, String resourceType, Object resourceId,
                         Map<String, ?> metadata) {
        save(organizationId, null, AuditEvent.ActorType.AI, action, resourceType, resourceId, metadata);
    }

    private void save(Long organizationId, Long actorUserId, AuditEvent.ActorType actorType, AuditAction action,
                      String resourceType, Object resourceId, Map<String, ?> metadata) {
        String meta = metadata == null || metadata.isEmpty() ? null : Json.truncate(json.write(metadata), 4000);
        repo.save(new AuditEvent(organizationId, actorUserId, actorType, action, resourceType,
                resourceId == null ? null : Json.truncate(String.valueOf(resourceId), 64),
                RequestContext.clientIp(), RequestContext.userAgent(), RequestContext.requestId(), meta, clock.instant()));
        log.info("audit action={} org={} actor={} resource={}:{}", action, organizationId, actorUserId, resourceType, resourceId);
    }
}
