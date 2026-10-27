package com.pitsch.backend.auth;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.org.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds GET /me: the user, active workspace, role, effective permissions and environment features. */
@Service
public class MeService {

    public record WorkspaceView(Long id, String name, String slug, String timezone, String role, String plan,
                                boolean onboardingCompleted, Instant createdAt) { }

    public record MembershipView(Long organizationId, String organizationName, String role) { }

    public record Features(String mode, boolean googleConfigured, boolean googleLoginEnabled, boolean billingEnabled,
                           boolean demo) { }

    public record MeView(UserView user, WorkspaceView workspace, List<String> permissions,
                         List<MembershipView> memberships, Features features) { }

    private final UserRepository users;
    private final MembershipRepository memberships;
    private final OrganizationRepository orgs;
    private final EntitlementService entitlements;
    private final PitschProperties props;

    public MeService(UserRepository users, MembershipRepository memberships, OrganizationRepository orgs,
                     EntitlementService entitlements, PitschProperties props) {
        this.users = users;
        this.memberships = memberships;
        this.orgs = orgs;
        this.entitlements = entitlements;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public MeView me(Long userId, Long activeOrgId) {
        User user = users.findById(userId).orElseThrow();
        List<Membership> mine = memberships.findByUserIdOrderByCreatedAtAsc(userId);
        Map<Long, Organization> byId = orgs.findAllById(mine.stream().map(Membership::getOrganizationId).toList())
                .stream().collect(Collectors.toMap(Organization::getId, Function.identity()));
        Membership active = mine.stream().filter(m -> m.getOrganizationId().equals(activeOrgId)).findFirst().orElse(null);
        WorkspaceView workspace = null;
        Role role = null;
        if (active != null && byId.containsKey(active.getOrganizationId())) {
            Organization o = byId.get(active.getOrganizationId());
            role = active.getRole();
            workspace = new WorkspaceView(o.getId(), o.getName(), o.getSlug(), o.getTimezone(), role.name(),
                    entitlements.planCode(o.getId()), o.getOnboardingCompletedAt() != null, o.getCreatedAt());
        }
        List<MembershipView> list = mine.stream().filter(m -> byId.containsKey(m.getOrganizationId()))
                .map(m -> new MembershipView(m.getOrganizationId(), byId.get(m.getOrganizationId()).getName(), m.getRole().name()))
                .toList();
        List<String> permissions = role == null ? List.of() : role.permissions().stream().map(Enum::name).sorted().toList();
        Features features = new Features(props.mode().name().toLowerCase(), props.getGoogle().isConfigured() || props.isDemo(),
                props.getGoogle().isConfigured() && props.getGoogle().isLoginEnabled(),
                "stripe".equalsIgnoreCase(props.getBilling().getProvider()), props.isDemo());
        return new MeView(UserView.of(user, role == null ? null : role.name()), workspace, permissions, list, features);
    }
}
