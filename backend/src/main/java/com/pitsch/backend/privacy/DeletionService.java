package com.pitsch.backend.privacy;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.PasswordHasher;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.SessionRepository;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.files.StorageProvider;
import com.pitsch.backend.files.StoredFile;
import com.pitsch.backend.files.StoredFileRepository;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobRepository;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationRepository;
import com.pitsch.backend.org.InvitationRepository;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.org.Role;
import com.pitsch.backend.integration.OAuthStateRepository;
import com.pitsch.backend.user.UserSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Right to erasure.
 * <ul>
 *   <li><b>Workspace deletion</b> (OWNER, password + typed name): access ends immediately (memberships and invitations
 *   removed, Google grants revoked), then a background job deletes stored objects and every workspace row
 *   (foreign keys cascade). A minimal audit record without content survives as proof of deletion.</li>
 *   <li><b>Account deletion</b> (password): refused while the user is the last owner of a workspace that has other
 *   members; workspaces where the user is the only member are deleted; the user's Google grants are revoked and the
 *   user row is removed (sessions, tokens, memberships, connections cascade). Records the user created inside shared
 *   workspaces belong to that workspace and remain.</li>
 * </ul>
 */
@Service
public class DeletionService implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DeletionService.class);

    private final OrganizationRepository organizations;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final SessionRepository sessions;
    private final IntegrationConnectionRepository connections;
    private final IntegrationService integrations;
    private final StoredFileRepository storedFiles;
    private final StorageProvider storage;
    private final JobRepository jobRepo;
    private final JobQueue jobs;
    private final OAuthStateRepository oauthStates;
    private final NotificationRepository notifications;
    private final UserSettingsRepository settings;
    private final PasswordHasher hasher;
    private final AuditService audit;
    private final Clock clock;

    public DeletionService(OrganizationRepository organizations, MembershipRepository memberships,
                           InvitationRepository invitations, UserRepository users, SessionRepository sessions,
                           IntegrationConnectionRepository connections, IntegrationService integrations,
                           StoredFileRepository storedFiles, StorageProvider storage, JobRepository jobRepo, JobQueue jobs,
                           OAuthStateRepository oauthStates, NotificationRepository notifications,
                           UserSettingsRepository settings, PasswordHasher hasher, AuditService audit, Clock clock) {
        this.organizations = organizations;
        this.memberships = memberships;
        this.invitations = invitations;
        this.users = users;
        this.sessions = sessions;
        this.connections = connections;
        this.integrations = integrations;
        this.storedFiles = storedFiles;
        this.storage = storage;
        this.jobRepo = jobRepo;
        this.jobs = jobs;
        this.oauthStates = oauthStates;
        this.notifications = notifications;
        this.settings = settings;
        this.hasher = hasher;
        this.audit = audit;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ workspace

    @Transactional
    public void requestWorkspaceDeletion(AuthPrincipal principal, String confirmName, String password) {
        principal.require(Permission.ORG_DELETE);
        Long orgId = principal.orgId();
        Organization org = organizations.findById(orgId).orElseThrow(() -> ApiException.notFound("Workspace"));
        if (confirmName == null || !confirmName.trim().equals(org.getName())) {
            throw ApiException.badRequest("Type the workspace name exactly to confirm deletion.");
        }
        User user = users.findById(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        checkPassword(user, password);
        startWorkspaceDeletion(org, principal.userId());
    }

    private void startWorkspaceDeletion(Organization org, Long actorUserId) {
        Long orgId = org.getId();
        org.setDeletionRequestedAt(clock.instant());
        organizations.save(org);
        for (IntegrationConnection c : connections.findByOrganizationId(orgId)) {
            integrations.revokeAndClear(c);
            connections.save(c);
        }
        memberships.deleteByOrganization(orgId);
        invitations.deleteByOrganization(orgId);
        audit.record(orgId, actorUserId, AuditAction.WORKSPACE_DELETION_REQUESTED, "WORKSPACE", orgId, null);
        jobs.enqueue(JobType.WORKSPACE_DELETE, orgId, null, Map.of("organizationId", orgId),
                "workspace-delete:" + orgId, Duration.ZERO);
    }

    @Override
    public JobType type() {
        return JobType.WORKSPACE_DELETE;
    }

    /** Background part of workspace deletion. Idempotent: safe to retry after a partial failure. */
    @Override
    @Transactional
    public void handle(Job job, JsonNode payload) {
        Long orgId = payload.path("organizationId").asLong();
        Organization org = organizations.findById(orgId).orElse(null);
        if (org == null) {
            return;
        }
        if (org.getDeletionRequestedAt() == null) {
            throw new IllegalStateException("Workspace " + orgId + " was not marked for deletion");
        }
        int objects = 0;
        for (StoredFile f : storedFiles.findByOrganizationId(orgId)) {
            storage.delete(f.getStorageKey());   // a failure throws and the job retries
            objects++;
        }
        jobRepo.deleteForOrganizationExcept(orgId, job.getId());
        oauthStates.deleteByOrganization(orgId);
        organizations.delete(org);   // cascades to every table with organization_id
        audit.record(null, null, AuditAction.WORKSPACE_DELETED, "WORKSPACE", orgId, Map.of("objectsDeleted", objects));
        log.info("Workspace {} deleted ({} stored objects removed)", orgId, objects);
    }

    // ------------------------------------------------------------------ account

    @Transactional
    public void deleteAccount(AuthPrincipal principal, String password) {
        User user = users.findById(principal.userId()).orElseThrow(() -> ApiException.notFound("User"));
        checkPassword(user, password);
        List<Organization> soleMemberWorkspaces = new ArrayList<>();
        for (Membership m : memberships.findByUserIdOrderByCreatedAtAsc(user.getId())) {
            long members = memberships.countByOrganizationId(m.getOrganizationId());
            if (members <= 1) {
                organizations.findById(m.getOrganizationId()).ifPresent(soleMemberWorkspaces::add);
            } else if (m.getRole() == Role.OWNER
                    && memberships.countByOrganizationIdAndRole(m.getOrganizationId(), Role.OWNER) <= 1) {
                String name = organizations.findById(m.getOrganizationId()).map(Organization::getName).orElse("a workspace");
                throw new ApiException(ErrorCode.CONFLICT, "You are the only owner of \"" + name
                        + "\". Make another member an owner or delete the workspace first.");
            }
        }
        for (Organization org : soleMemberWorkspaces) {
            startWorkspaceDeletion(org, user.getId());
        }
        for (IntegrationConnection c : connections.findByUserId(user.getId())) {
            integrations.revokeAndClear(c);
            connections.save(c);
        }
        notifications.deleteByUser(user.getId());
        settings.deleteByUser(user.getId());
        audit.record(null, user.getId(), AuditAction.ACCOUNT_DELETED, "USER", user.getId(),
                Map.of("workspacesDeleted", soleMemberWorkspaces.size()));
        users.delete(user);   // sessions, refresh tokens, user tokens, memberships and connections cascade
    }

    private void checkPassword(User user, String password) {
        if (PasswordHasher.NO_PASSWORD.equals(user.getPasswordHash())) {
            return;   // Google-only account: protected by the confirmation step and a fresh session
        }
        if (password == null || !hasher.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Your password is incorrect.");
        }
    }
}
