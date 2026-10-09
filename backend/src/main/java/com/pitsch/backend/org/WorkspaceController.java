package com.pitsch.backend.org;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.pitsch.backend.auth.AllowUnverified;
import com.pitsch.backend.auth.AllowWithoutWorkspace;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.AuthService;
import com.pitsch.backend.auth.MeService;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.PublicEndpoint;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Workspace settings, members and invitations. Tenant scope always comes from the session, never from the URL. */
@RestController
public class WorkspaceController {

    public record WorkspaceSettingsView(Long id, String name, String slug, String timezone, String workingHoursStart,
                                        String workingHoursEnd, List<String> workingDays, int meetingDurationMinutes,
                                        Integer dataRetentionDays, String gmailLabelPolicy, String sheetsSyncPolicy,
                                        boolean onboardingCompleted, Instant createdAt) { }

    public record CreateWorkspaceRequest(@NotBlank @Size(max = 200) String name, @Size(max = 64) String timezone) { }

    public record MemberView(Long membershipId, Long userId, String name, String email, String role, Instant joinedAt) { }

    public record InviteRequest(@NotBlank @Email @Size(max = 320) String email, @NotNull Role role) { }

    public record RoleRequest(@NotNull Role role) { }

    public record InvitationView(Long id, String email, String role, Instant expiresAt, Instant createdAt) { }

    public record InvitationLookup(String workspaceName, String email, String role, Instant expiresAt) { }

    private final OrganizationService service;
    private final OrganizationRepository orgs;
    private final MembershipRepository memberships;
    private final InvitationRepository invitations;
    private final UserRepository users;
    private final AuthService auth;
    private final MeService me;

    public WorkspaceController(OrganizationService service, OrganizationRepository orgs, MembershipRepository memberships,
                               InvitationRepository invitations, UserRepository users, AuthService auth, MeService me) {
        this.service = service;
        this.orgs = orgs;
        this.memberships = memberships;
        this.invitations = invitations;
        this.users = users;
        this.auth = auth;
        this.me = me;
    }

    /** Creates another workspace owned by the caller and switches the session to it. */
    @AllowWithoutWorkspace
    @PostMapping("/api/v1/workspaces")
    public ResponseEntity<MeService.MeView> create(AuthPrincipal principal, @Valid @RequestBody CreateWorkspaceRequest req) {
        User user = users.findById(principal.userId()).orElseThrow();
        Organization org = service.createWorkspace(user, req.name(), req.timezone());
        auth.switchWorkspace(principal, org.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(me.me(principal.userId(), org.getId()));
    }

    @GetMapping("/api/v1/workspace")
    public WorkspaceSettingsView get(AuthPrincipal principal) {
        return view(service.get(principal.orgId()));
    }

    @PatchMapping("/api/v1/workspace")
    @RequiresPermission(Permission.ORG_SETTINGS)
    public WorkspaceSettingsView update(AuthPrincipal principal, @RequestBody OrganizationService.SettingsUpdate req) {
        return view(service.updateSettings(principal, req));
    }

    @AllowUnverified
    @RequiresPermission(Permission.ORG_SETTINGS)
    @PostMapping("/api/v1/workspace/onboarding/complete")
    public WorkspaceSettingsView completeOnboarding(AuthPrincipal principal) {
        return view(service.completeOnboarding(principal));
    }

    @GetMapping("/api/v1/workspace/members")
    @RequiresPermission(Permission.MEMBER_READ)
    public List<MemberView> members(AuthPrincipal principal) {
        List<Membership> list = memberships.findByOrganizationIdOrderByCreatedAtAsc(principal.orgId());
        Map<Long, User> byId = users.findAllById(list.stream().map(Membership::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream().filter(m -> byId.containsKey(m.getUserId())).map(m -> {
            User u = byId.get(m.getUserId());
            return new MemberView(m.getId(), u.getId(), u.getName(), u.getEmail(), m.getRole().name(), m.getCreatedAt());
        }).toList();
    }

    @PatchMapping("/api/v1/workspace/members/{membershipId}")
    @RequiresPermission(Permission.MEMBER_MANAGE)
    public Map<String, Object> changeRole(AuthPrincipal principal, @PathVariable Long membershipId,
                                          @Valid @RequestBody RoleRequest req) {
        Membership m = service.changeRole(principal, membershipId, req.role());
        return Map.of("membershipId", m.getId(), "role", m.getRole().name());
    }

    /** Owners/admins remove members; any member may remove themselves (leave). */
    @AllowUnverified
    @DeleteMapping("/api/v1/workspace/members/{membershipId}")
    public ResponseEntity<Void> remove(AuthPrincipal principal, @PathVariable Long membershipId) {
        service.removeMember(principal, membershipId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/v1/workspace/invitations")
    @RequiresPermission(Permission.MEMBER_MANAGE)
    public List<InvitationView> invitations(AuthPrincipal principal) {
        Instant now = Instant.now();
        return invitations.findByOrganizationIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(principal.orgId())
                .stream().filter(i -> i.isPending(now))
                .map(i -> new InvitationView(i.getId(), i.getEmail(), i.getRole().name(), i.getExpiresAt(), i.getCreatedAt()))
                .toList();
    }

    @PostMapping("/api/v1/workspace/invitations")
    @RequiresPermission(Permission.MEMBER_MANAGE)
    public ResponseEntity<InvitationView> invite(AuthPrincipal principal, @Valid @RequestBody InviteRequest req) {
        Invitation i = service.invite(principal, req.email(), req.role());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new InvitationView(i.getId(), i.getEmail(), i.getRole().name(), i.getExpiresAt(), i.getCreatedAt()));
    }

    @DeleteMapping("/api/v1/workspace/invitations/{invitationId}")
    @RequiresPermission(Permission.MEMBER_MANAGE)
    public ResponseEntity<Void> revoke(AuthPrincipal principal, @PathVariable Long invitationId) {
        service.revokeInvitation(principal, invitationId);
        return ResponseEntity.noContent().build();
    }

    @PublicEndpoint
    @GetMapping("/api/v1/invitations/{token}")
    public InvitationLookup lookup(@PathVariable String token) {
        Invitation i = service.pendingInvitation(token);
        String name = orgs.findById(i.getOrganizationId()).map(Organization::getName).orElse("a workspace");
        return new InvitationLookup(name, i.getEmail(), i.getRole().name(), i.getExpiresAt());
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PostMapping("/api/v1/invitations/{token}/accept")
    public MeService.MeView accept(AuthPrincipal principal, @PathVariable String token) {
        User user = users.findById(principal.userId()).orElseThrow();
        Membership m = service.acceptInvitation(user, token);
        auth.switchWorkspace(principal, m.getOrganizationId());
        return me.me(principal.userId(), m.getOrganizationId());
    }

    static WorkspaceSettingsView view(Organization o) {
        if (o == null) {
            throw ApiException.notFound("Workspace");
        }
        return new WorkspaceSettingsView(o.getId(), o.getName(), o.getSlug(), o.getTimezone(), o.getWorkingHoursStart(),
                o.getWorkingHoursEnd(), Arrays.asList(o.getWorkingDays().split(",")), o.getMeetingDurationMinutes(),
                o.getDataRetentionDays(), o.getGmailLabelPolicy(), o.getSheetsSyncPolicy(),
                o.getOnboardingCompletedAt() != null, o.getCreatedAt());
    }
}
