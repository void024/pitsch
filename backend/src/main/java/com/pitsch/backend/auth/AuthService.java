package com.pitsch.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ClientInfo;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.mail.EmailTemplates;
import com.pitsch.backend.mail.MailMessage;
import com.pitsch.backend.mail.MailService;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Account lifecycle: signup, login (with lockout), verification, password reset/change, email change. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final Duration VERIFY_TTL = Duration.ofHours(48);
    private static final Duration RESET_TTL = Duration.ofHours(1);
    private static final String INVALID = "Invalid email or password.";

    public record SignupCommand(String name, String email, String password, String workspaceName, String timezone,
                                String inviteToken) { }

    private final UserRepository users;
    private final UserTokenRepository userTokens;
    private final MembershipRepository memberships;
    private final OrganizationService organizations;
    private final SessionService sessions;
    private final PasswordHasher hasher;
    private final AuditService audit;
    private final MailService mail;
    private final PitschProperties props;
    private final Clock clock;

    public AuthService(UserRepository users, UserTokenRepository userTokens, MembershipRepository memberships,
                       OrganizationService organizations, SessionService sessions, PasswordHasher hasher,
                       AuditService audit, MailService mail, PitschProperties props, Clock clock) {
        this.users = users;
        this.userTokens = userTokens;
        this.memberships = memberships;
        this.organizations = organizations;
        this.sessions = sessions;
        this.hasher = hasher;
        this.audit = audit;
        this.mail = mail;
        this.props = props;
        this.clock = clock;
    }

    @Transactional
    public SessionService.Issued signup(SignupCommand cmd, ClientInfo client) {
        String email = OrganizationService.normalizeEmail(cmd.email());
        String name = cmd.name() == null ? "" : cmd.name().trim();
        if (name.isEmpty() || name.length() > 120) {
            throw ApiException.badRequest("Name is required (max 120 characters).");
        }
        String violation = PasswordHasher.policyViolation(cmd.password(), email);
        if (violation != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, violation);
        }
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict("An account with this email already exists. Sign in or reset your password.");
        }
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPasswordHash(hasher.hash(cmd.password()));
        user.setPasswordChangedAt(clock.instant());
        user = users.save(user);

        Long activeOrg;
        if (cmd.inviteToken() != null && !cmd.inviteToken().isBlank()) {
            Membership m = organizations.acceptInvitation(user, cmd.inviteToken());
            activeOrg = m.getOrganizationId();
            user.setDefaultOrganizationId(activeOrg);
            users.save(user);
        } else {
            Organization org = organizations.createWorkspace(user, cmd.workspaceName(), cmd.timezone());
            activeOrg = org.getId();
        }
        audit.record(activeOrg, user.getId(), AuditAction.SIGNUP, "USER", user.getId(), null);
        if (!user.isEmailVerified()) {
            sendVerification(user);
        }
        return sessions.create(user.getId(), activeOrg, client);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public SessionService.Issued login(String rawEmail, String password, ClientInfo client) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        Instant now = clock.instant();
        User user = users.findByEmailIgnoreCase(email).orElse(null);
        if (user == null) {
            hasher.matches(password, "pbkdf2$100000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
            audit.recordIndependent(null, null, AuditAction.LOGIN_FAILED, "USER", null, Map.of("reason", "unknown_email"));
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID);
        }
        if (user.isLocked(now)) {
            throw new ApiException(ErrorCode.ACCOUNT_LOCKED,
                    "Too many failed attempts. Try again in a few minutes or reset your password.");
        }
        if (!hasher.matches(password, user.getPasswordHash())) {
            int attempts = user.getFailedLoginAttempts() + 1;
            user.setFailedLoginAttempts(attempts);
            boolean lock = attempts >= props.getSecurity().getMaxFailedLogins();
            if (lock) {
                user.setLockedUntil(now.plus(Duration.ofMinutes(props.getSecurity().getLockoutMinutes())));
                user.setFailedLoginAttempts(0);
            }
            users.save(user);
            audit.recordIndependent(user.getDefaultOrganizationId(), user.getId(),
                    lock ? AuditAction.ACCOUNT_LOCKED : AuditAction.LOGIN_FAILED, "USER", user.getId(), null);
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, INVALID);
        }
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        if (hasher.needsRehash(user.getPasswordHash())) {
            user.setPasswordHash(hasher.hash(password));
        }
        users.save(user);
        Long org = activeOrganizationFor(user);
        audit.record(org, user.getId(), AuditAction.LOGIN, "USER", user.getId(), null);
        return sessions.create(user.getId(), org, client);
    }

    /** Google sign-in: links to an existing account with the same verified email, or creates one. */
    @Transactional
    public SessionService.Issued loginWithGoogle(String email, String name, ClientInfo client) {
        String normalized = OrganizationService.normalizeEmail(email);
        User user = users.findByEmailIgnoreCase(normalized).orElse(null);
        if (user == null) {
            user = new User();
            user.setName(name == null || name.isBlank() ? normalized.substring(0, normalized.indexOf('@')) : name.trim());
            user.setEmail(normalized);
            user.setPasswordHash(PasswordHasher.NO_PASSWORD);
            user.setEmailVerifiedAt(clock.instant());
            user = users.save(user);
            Organization org = organizations.createWorkspace(user, null, null);
            audit.record(org.getId(), user.getId(), AuditAction.SIGNUP, "USER", user.getId(), Map.of("method", "google"));
        } else if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(clock.instant());
        }
        user.setLastLoginAt(clock.instant());
        users.save(user);
        Long org = activeOrganizationFor(user);
        audit.record(org, user.getId(), AuditAction.LOGIN, "USER", user.getId(), Map.of("method", "google"));
        return sessions.create(user.getId(), org, client);
    }

    @Transactional
    public void verifyEmail(String token) {
        UserToken t = consume(token, UserToken.Purpose.VERIFY_EMAIL);
        User user = users.findById(t.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(clock.instant());
            users.save(user);
        }
        audit.record(user.getDefaultOrganizationId(), user.getId(), AuditAction.EMAIL_VERIFIED, "USER", user.getId(), null);
    }

    @Transactional
    public void resendVerification(Long userId) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        if (!user.isEmailVerified()) {
            sendVerification(user);
        }
    }

    /** Always succeeds from the caller's point of view, so it cannot be used to discover accounts. */
    @Transactional
    public void forgotPassword(String rawEmail) {
        String email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        users.findByEmailIgnoreCase(email).ifPresent(user -> {
            String token = issueToken(user.getId(), UserToken.Purpose.RESET_PASSWORD, RESET_TTL, null);
            deliver(EmailTemplates.passwordReset(user.getEmail(), user.getName(),
                    props.getFrontendUrl() + "/reset-password?token=" + token));
            audit.recordIndependent(user.getDefaultOrganizationId(), user.getId(), AuditAction.PASSWORD_RESET_REQUESTED,
                    "USER", user.getId(), null);
        });
    }

    @Transactional
    public void resetPassword(String token, String newPassword) {
        UserToken t = consume(token, UserToken.Purpose.RESET_PASSWORD);
        User user = users.findById(t.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        setPassword(user, newPassword);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        if (!user.isEmailVerified()) {
            user.setEmailVerifiedAt(clock.instant());
        }
        users.save(user);
        sessions.revokeOthers(user.getId(), null, "PASSWORD_RESET");
        audit.record(user.getDefaultOrganizationId(), user.getId(), AuditAction.PASSWORD_RESET, "USER", user.getId(), null);
    }

    @Transactional
    public void changePassword(AuthPrincipal principal, String currentPassword, String newPassword) {
        User user = users.findById(principal.userId()).orElseThrow();
        if (!PasswordHasher.NO_PASSWORD.equals(user.getPasswordHash()) && !hasher.matches(currentPassword, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Current password is incorrect.");
        }
        setPassword(user, newPassword);
        users.save(user);
        int revoked = sessions.revokeOthers(user.getId(), principal.sessionId(), "PASSWORD_CHANGED");
        audit.record(principal.organizationId(), user.getId(), AuditAction.PASSWORD_CHANGED, "USER", user.getId(),
                Map.of("otherSessionsRevoked", revoked));
    }

    /** Name changes apply immediately; an email change needs the password and confirmation from the new address. */
    @Transactional
    public User updateProfile(AuthPrincipal principal, String name, String newEmail, String currentPassword) {
        User user = users.findById(principal.userId()).orElseThrow();
        if (name != null) {
            if (name.isBlank() || name.length() > 120) {
                throw ApiException.badRequest("Name is required (max 120 characters).");
            }
            user.setName(name.trim());
        }
        if (newEmail != null && !newEmail.equalsIgnoreCase(user.getEmail())) {
            String email = OrganizationService.normalizeEmail(newEmail);
            if (!hasher.matches(currentPassword, user.getPasswordHash())) {
                throw new ApiException(ErrorCode.INVALID_CREDENTIALS, "Enter your current password to change your email.");
            }
            if (users.existsByEmailIgnoreCase(email)) {
                throw ApiException.conflict("That email is already used by another account.");
            }
            String token = issueToken(user.getId(), UserToken.Purpose.CHANGE_EMAIL, VERIFY_TTL, email);
            deliver(EmailTemplates.changeEmail(email, user.getName(), props.getFrontendUrl() + "/verify-email?token=" + token));
            audit.record(principal.organizationId(), user.getId(), AuditAction.EMAIL_CHANGE_REQUESTED, "USER", user.getId(), null);
        }
        return users.save(user);
    }

    @Transactional
    public void confirmEmailChange(String token) {
        UserToken t = consume(token, UserToken.Purpose.CHANGE_EMAIL);
        User user = users.findById(t.getUserId()).orElseThrow(() -> ApiException.notFound("User"));
        if (users.existsByEmailIgnoreCase(t.getNewEmail())) {
            throw ApiException.conflict("That email is already used by another account.");
        }
        user.setEmail(t.getNewEmail());
        user.setEmailVerifiedAt(clock.instant());
        users.save(user);
        sessions.revokeOthers(user.getId(), null, "EMAIL_CHANGED");
        audit.record(user.getDefaultOrganizationId(), user.getId(), AuditAction.EMAIL_CHANGED, "USER", user.getId(), null);
    }

    /** Verify-email links serve both purposes; the token itself says which. */
    @Transactional
    public void confirmEmailToken(String token) {
        UserToken t = userTokens.findByTokenHash(Hashing.sha256Hex(token == null ? "" : token))
                .orElseThrow(() -> ApiException.badRequest("This link is invalid or has expired."));
        if (t.getPurpose() == UserToken.Purpose.CHANGE_EMAIL) {
            confirmEmailChange(token);
        } else {
            verifyEmail(token);
        }
    }

    @Transactional
    public void switchWorkspace(AuthPrincipal principal, Long organizationId) {
        memberships.findByOrganizationIdAndUserId(organizationId, principal.userId())
                .orElseThrow(() -> ApiException.forbidden("You are not a member of that workspace."));
        sessions.switchOrganization(principal.sessionId(), organizationId);
        User user = users.findById(principal.userId()).orElseThrow();
        user.setDefaultOrganizationId(organizationId);
        users.save(user);
        audit.record(organizationId, principal.userId(), AuditAction.WORKSPACE_SWITCHED, "ORGANIZATION", organizationId, null);
    }

    // ------------------------------------------------------------------ helpers

    private Long activeOrganizationFor(User user) {
        List<Membership> mine = memberships.findByUserIdOrderByCreatedAtAsc(user.getId());
        if (user.getDefaultOrganizationId() != null
                && mine.stream().anyMatch(m -> m.getOrganizationId().equals(user.getDefaultOrganizationId()))) {
            return user.getDefaultOrganizationId();
        }
        return mine.isEmpty() ? null : mine.get(0).getOrganizationId();
    }

    private void setPassword(User user, String newPassword) {
        String violation = PasswordHasher.policyViolation(newPassword, user.getEmail());
        if (violation != null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, violation);
        }
        user.setPasswordHash(hasher.hash(newPassword));
        user.setPasswordChangedAt(clock.instant());
    }

    private void sendVerification(User user) {
        String token = issueToken(user.getId(), UserToken.Purpose.VERIFY_EMAIL, VERIFY_TTL, null);
        deliver(EmailTemplates.verifyEmail(user.getEmail(), user.getName(), props.getFrontendUrl() + "/verify-email?token=" + token));
    }

    private String issueToken(Long userId, UserToken.Purpose purpose, Duration ttl, String newEmail) {
        String raw = Hashing.randomToken();
        UserToken t = new UserToken();
        t.setUserId(userId);
        t.setPurpose(purpose);
        t.setTokenHash(Hashing.sha256Hex(raw));
        t.setNewEmail(newEmail);
        t.setCreatedAt(clock.instant());
        t.setExpiresAt(clock.instant().plus(ttl));
        userTokens.save(t);
        return raw;
    }

    private UserToken consume(String token, UserToken.Purpose purpose) {
        UserToken t = userTokens.findByTokenHash(Hashing.sha256Hex(token == null ? "" : token))
                .filter(x -> x.getPurpose() == purpose)
                .orElseThrow(() -> ApiException.badRequest("This link is invalid or has expired."));
        if (!t.isUsable(clock.instant())) {
            throw ApiException.badRequest("This link is invalid or has expired.");
        }
        t.setUsedAt(clock.instant());
        userTokens.save(t);
        return t;
    }

    private void deliver(MailMessage message) {
        try {
            mail.send(message);
        } catch (MailService.MailDeliveryException e) {
            log.warn("Transactional email could not be delivered ({}).", message.subject());
        }
    }
}
