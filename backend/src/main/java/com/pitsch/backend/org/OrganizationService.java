package com.pitsch.backend.org;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.billing.Subscription;
import com.pitsch.backend.billing.SubscriptionRepository;
import com.pitsch.backend.billing.UsageMetric;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.mail.EmailTemplates;
import com.pitsch.backend.mail.MailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Workspaces, memberships and invitations. Every mutating call checks the caller's permission server-side. */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);
    private static final Set<String> WEEKDAYS = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Duration INVITE_TTL = Duration.ofDays(7);

    public record SettingsUpdate(String name, String timezone, String workingHoursStart, String workingHoursEnd,
                                 List<String> workingDays, Integer meetingDurationMinutes, Integer dataRetentionDays,
                                 Boolean clearDataRetention, String gmailLabelPolicy, String sheetsSyncPolicy) { }

    private final OrganizationRepository orgs;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final SubscriptionRepository subscriptions;
    private final UserRepository users;
    private final EntitlementService entitlements;
    private final AuditService audit;
    private final MailService mail;
    private final PitschProperties props;
    private final Clock clock;

    public OrganizationService(OrganizationRepository orgs, MembershipRepository memberships,
                               InvitationRepository invitations, SubscriptionRepository subscriptions,
                               UserRepository users, EntitlementService entitlements, AuditService audit,
                               MailService mail, PitschProperties props, Clock clock) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.invitations = invitations;
        this.subscriptions = subscriptions;
        this.users = users;
        this.entitlements = entitlements;
        this.audit = audit;
        this.mail = mail;
        this.props = props;
        this.clock = clock;
    }

    /** Creates a workspace with the user as OWNER and a FREE subscription. */
    @Transactional
    public Organization createWorkspace(User owner, String name, String timezone) {
        Organization org = new Organization();
        String clean = name == null || name.isBlank() ? owner.getName() + "'s workspace" : name.trim();
        org.setName(truncate(clean, 200));
        org.setSlug(uniqueSlug(clean));
        org.setTimezone(validTimezone(timezone == null || timezone.isBlank() ? props.getDefaultTimezone() : timezone));
        org = orgs.save(org);
        memberships.save(new Membership(org.getId(), owner.getId(), Role.OWNER));
        Subscription sub = new Subscription();
        sub.setOrganizationId(org.getId());
        sub.setPlanCode("FREE");
        sub.setStatus(Subscription.Status.ACTIVE.name());
        sub.setProvider("NONE");
        subscriptions.save(sub);
        if (owner.getDefaultOrganizationId() == null) {
            owner.setDefaultOrganizationId(org.getId());
            users.save(owner);
        }
        audit.record(org.getId(), owner.getId(), AuditAction.WORKSPACE_CREATED, "ORGANIZATION", org.getId(), null);
        return org;
    }

    @Transactional(readOnly = true)
    public Organization get(Long orgId) {
        return orgs.findById(orgId).orElseThrow(() -> ApiException.notFound("Workspace"));
    }

    @Transactional
    public Organization updateSettings(AuthPrincipal principal, SettingsUpdate in) {
        principal.require(Permission.ORG_SETTINGS);
        Organization org = get(principal.orgId());
        if (in.name() != null) {
            if (in.name().isBlank()) {
                throw ApiException.badRequest("Workspace name is required.");
            }
            org.setName(truncate(in.name().trim(), 200));
        }
        if (in.timezone() != null) {
            org.setTimezone(validTimezone(in.timezone()));
        }
        if (in.workingHoursStart() != null || in.workingHoursEnd() != null) {
            LocalTime start = parseTime(in.workingHoursStart() != null ? in.workingHoursStart() : org.getWorkingHoursStart());
            LocalTime end = parseTime(in.workingHoursEnd() != null ? in.workingHoursEnd() : org.getWorkingHoursEnd());
            if (!end.isAfter(start)) {
                throw ApiException.badRequest("Working hours must end after they start.");
            }
            org.setWorkingHoursStart(start.toString());
            org.setWorkingHoursEnd(end.toString());
        }
        if (in.workingDays() != null) {
            List<String> days = in.workingDays().stream().map(d -> d.trim().toUpperCase(Locale.ROOT)).distinct().toList();
            if (days.isEmpty() || !WEEKDAYS.containsAll(days)) {
                throw ApiException.badRequest("workingDays must be a non-empty subset of " + WEEKDAYS);
            }
            org.setWorkingDays(String.join(",", days));
        }
        if (in.meetingDurationMinutes() != null) {
            int d = in.meetingDurationMinutes();
            if (d < 15 || d > 240) {
                throw ApiException.badRequest("meetingDurationMinutes must be between 15 and 240.");
            }
            org.setMeetingDurationMinutes(d);
        }
        if (Boolean.TRUE.equals(in.clearDataRetention())) {
            org.setDataRetentionDays(null);
        } else if (in.dataRetentionDays() != null) {
            if (in.dataRetentionDays() < 7 || in.dataRetentionDays() > 3650) {
                throw ApiException.badRequest("dataRetentionDays must be between 7 and 3650.");
            }
            org.setDataRetentionDays(in.dataRetentionDays());
        }
        if (in.gmailLabelPolicy() != null) {
            org.setGmailLabelPolicy(policy(in.gmailLabelPolicy(), "gmailLabelPolicy"));
        }
        if (in.sheetsSyncPolicy() != null) {
            org.setSheetsSyncPolicy(policy(in.sheetsSyncPolicy(), "sheetsSyncPolicy"));
        }
        Organization saved = orgs.save(org);
        audit.record(org.getId(), principal.userId(), AuditAction.WORKSPACE_UPDATED, "ORGANIZATION", org.getId(), null);
        return saved;
    }

    @Transactional
    public Organization completeOnboarding(AuthPrincipal principal) {
        Organization org = get(principal.orgId());
        if (org.getOnboardingCompletedAt() == null) {
            org.setOnboardingCompletedAt(clock.instant());
            orgs.save(org);
        }
        return org;
    }

    // ---------------------------------------------------------------- members

    @Transactional
    public Invitation invite(AuthPrincipal principal, String email, Role role) {
        principal.require(Permission.MEMBER_MANAGE);
        if (role == null) {
            throw ApiException.badRequest("role is required");
        }
        if (role == Role.OWNER && principal.role() != Role.OWNER) {
            throw ApiException.forbidden("Only owners can invite owners.");
        }
        String normalized = normalizeEmail(email);
        Long orgId = principal.orgId();
        users.findByEmailIgnoreCase(normalized)
                .flatMap(u -> memberships.findByOrganizationIdAndUserId(orgId, u.getId()))
                .ifPresent(m -> { throw ApiException.conflict("That person is already a member."); });
        entitlements.require(orgId, UsageMetric.MEMBERS, 1);
        String token = Hashing.randomToken();
        Invitation inv = new Invitation();
        inv.setOrganizationId(orgId);
        inv.setEmail(normalized);
        inv.setRole(role);
        inv.setTokenHash(Hashing.sha256Hex(token));
        inv.setInvitedByUserId(principal.userId());
        inv.setExpiresAt(clock.instant().plus(INVITE_TTL));
        inv = invitations.save(inv);
        Organization org = get(orgId);
        try {
            mail.send(EmailTemplates.invitation(normalized, principal.name(), org.getName(), role.name(),
                    props.getFrontendUrl() + "/invite/" + token));
        } catch (MailService.MailDeliveryException e) {
            log.warn("Invitation email could not be sent for invitation {}", inv.getId());
        }
        audit.record(orgId, principal.userId(), AuditAction.MEMBER_INVITED, "INVITATION", inv.getId(),
                Map.of("role", role.name()));
        return inv;
    }

    @Transactional
    public void revokeInvitation(AuthPrincipal principal, Long invitationId) {
        principal.require(Permission.MEMBER_MANAGE);
        Invitation inv = invitations.findByIdAndOrganizationId(invitationId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        inv.setRevokedAt(clock.instant());
        invitations.save(inv);
        audit.record(principal.orgId(), principal.userId(), AuditAction.INVITATION_REVOKED, "INVITATION", inv.getId(), null);
    }

    /** Public lookup so the invite page can show the workspace name before sign-in. */
    @Transactional(readOnly = true)
    public Invitation pendingInvitation(String token) {
        Invitation inv = invitations.findByTokenHash(Hashing.sha256Hex(token == null ? "" : token))
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (!inv.isPending(clock.instant())) {
            throw new ApiException(ErrorCode.CONFLICT, "This invitation has expired or was already used.");
        }
        return inv;
    }

    /** The signed-in user joins the workspace; the invitation must be addressed to their (verified) email. */
    @Transactional
    public Membership acceptInvitation(User user, String token) {
        Invitation inv = pendingInvitation(token);
        if (!inv.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw ApiException.forbidden("This invitation was sent to a different email address.");
        }
        Membership m = memberships.findByOrganizationIdAndUserId(inv.getOrganizationId(), user.getId())
                .orElseGet(() -> memberships.save(new Membership(inv.getOrganizationId(), user.getId(), inv.getRole())));
        inv.setAcceptedAt(clock.instant());
        invitations.save(inv);
        if (!user.isEmailVerified()) {
            // Possession of the emailed token proves ownership of the address.
            user.setEmailVerifiedAt(clock.instant());
            users.save(user);
        }
        audit.record(inv.getOrganizationId(), user.getId(), AuditAction.MEMBER_JOINED, "MEMBERSHIP", m.getId(),
                Map.of("role", m.getRole().name()));
        return m;
    }

    @Transactional
    public Membership changeRole(AuthPrincipal principal, Long membershipId, Role role) {
        principal.require(Permission.MEMBER_MANAGE);
        Membership m = memberships.findByIdAndOrganizationId(membershipId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Member"));
        if ((role == Role.OWNER || m.getRole() == Role.OWNER) && principal.role() != Role.OWNER) {
            throw ApiException.forbidden("Only owners can change owner roles.");
        }
        if (m.getRole() == Role.OWNER && role != Role.OWNER
                && memberships.countByOrganizationIdAndRole(principal.orgId(), Role.OWNER) <= 1) {
            throw ApiException.conflict("A workspace needs at least one owner.");
        }
        Role previous = m.getRole();
        m.setRole(role);
        memberships.save(m);
        audit.record(principal.orgId(), principal.userId(), AuditAction.MEMBER_ROLE_CHANGED, "MEMBERSHIP", m.getId(),
                Map.of("from", previous.name(), "to", role.name(), "userId", m.getUserId()));
        return m;
    }

    @Transactional
    public void removeMember(AuthPrincipal principal, Long membershipId) {
        Membership m = memberships.findByIdAndOrganizationId(membershipId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Member"));
        boolean self = m.getUserId().equals(principal.userId());
        if (!self) {
            principal.require(Permission.MEMBER_MANAGE);
            if (m.getRole() == Role.OWNER && principal.role() != Role.OWNER) {
                throw ApiException.forbidden("Only owners can remove owners.");
            }
        }
        if (m.getRole() == Role.OWNER && memberships.countByOrganizationIdAndRole(principal.orgId(), Role.OWNER) <= 1) {
            throw ApiException.conflict("A workspace needs at least one owner. Transfer ownership first.");
        }
        memberships.delete(m);
        audit.record(principal.orgId(), principal.userId(), AuditAction.MEMBER_REMOVED, "MEMBERSHIP", m.getId(),
                Map.of("userId", m.getUserId(), "self", self));
    }

    // ---------------------------------------------------------------- helpers

    private String uniqueSlug(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        base = base.isEmpty() ? "workspace" : truncate(base, 60);
        for (int i = 0; i < 10; i++) {
            String candidate = base + "-" + Hashing.randomToken().substring(0, 6).toLowerCase(Locale.ROOT)
                    .replaceAll("[^a-z0-9]", "x");
            if (!orgs.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a workspace slug");
    }

    public static String validTimezone(String tz) {
        try {
            return ZoneId.of(tz.trim()).getId();
        } catch (DateTimeException | NullPointerException e) {
            throw ApiException.badRequest("Unknown time zone: " + tz);
        }
    }

    private static LocalTime parseTime(String value) {
        try {
            return LocalTime.parse(value.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("Times must be HH:mm");
        }
    }

    private static String policy(String value, String name) {
        try {
            return Organization.ActionPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest(name + " must be one of " + Arrays.toString(Organization.ActionPolicy.values()));
        }
    }

    public static String normalizeEmail(String email) {
        String e = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        if (e.length() > 320 || !e.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.badRequest("Enter a valid email address.");
        }
        return e;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
