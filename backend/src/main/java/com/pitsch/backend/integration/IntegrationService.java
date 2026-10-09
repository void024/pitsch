package com.pitsch.backend.integration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.AuthService;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.SessionService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ClientInfo;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.integration.google.GmailProvider;
import com.pitsch.backend.integration.google.GoogleOAuthClient;
import com.pitsch.backend.integration.provider.CalendarProvider;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.security.TokenCipher;
import com.pitsch.backend.workflow.AgentInputs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connecting, using and disconnecting Google accounts (and Google sign-in). */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    public static final String PROVIDER = "GOOGLE";

    public record IntegrationStatus(String integration, boolean configured, boolean demo, boolean connected,
                                    String status, String accountEmail, List<String> scopes, Instant lastSyncAt,
                                    String lastSyncStatus, String error, boolean pushEnabled) { }

    /** Result of an OAuth callback: where to send the browser, and (for sign-in) the session to start. */
    public record CallbackResult(String redirectUrl, SessionService.Issued session) { }

    private final IntegrationConnectionRepository connections;
    private final OAuthStateRepository states;
    private final GoogleOAuthClient oauth;
    private final GmailProvider gmail;
    private final ProviderRegistry providers;
    private final CalendarEventRepository events;
    private final TokenCipher cipher;
    private final JobQueue jobs;
    private final AuditService audit;
    private final AuthService auth;
    private final PitschProperties props;
    private final Clock clock;

    public IntegrationService(IntegrationConnectionRepository connections, OAuthStateRepository states,
                              GoogleOAuthClient oauth, GmailProvider gmail, ProviderRegistry providers,
                              CalendarEventRepository events, TokenCipher cipher, JobQueue jobs, AuditService audit,
                              AuthService auth, PitschProperties props, Clock clock) {
        this.connections = connections;
        this.states = states;
        this.oauth = oauth;
        this.gmail = gmail;
        this.providers = providers;
        this.events = events;
        this.cipher = cipher;
        this.jobs = jobs;
        this.audit = audit;
        this.auth = auth;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ status

    @Transactional(readOnly = true)
    public List<IntegrationStatus> statuses(AuthPrincipal principal) {
        IntegrationConnection mine = connections.findByOrganizationIdAndUserIdAndProvider(principal.orgId(),
                principal.userId(), PROVIDER).orElse(null);
        boolean configured = props.getGoogle().isConfigured() || providers.demo();
        List<IntegrationStatus> out = new ArrayList<>();
        for (Integration i : Integration.values()) {
            boolean connected = mine != null && mine.isUsableFor(i);
            boolean enabled = mine != null && mine.enabled().contains(i);
            out.add(new IntegrationStatus(i.name(), configured, providers.demo(), connected,
                    mine == null || !enabled ? "NOT_CONNECTED" : mine.getStatus(),
                    enabled ? mine.getAccountEmail() : null,
                    enabled ? i.scopes() : List.of(),
                    enabled && i == Integration.GMAIL ? mine.getLastSyncAt() : null,
                    enabled && i == Integration.GMAIL ? mine.getLastSyncStatus() : null,
                    enabled ? mine.getLastError() : null,
                    i == Integration.GMAIL && props.getGoogle().isPushConfigured()));
        }
        return out;
    }

    // ------------------------------------------------------------------ connect

    /** @return the URL to send the browser to (Google consent screen, or straight back in demo mode) */
    @Transactional
    public String startConnect(AuthPrincipal principal, Set<Integration> requested, String returnPath) {
        if (requested.isEmpty()) {
            throw ApiException.badRequest("Choose at least one integration.");
        }
        principal.require(requested.contains(Integration.SHEETS) ? Permission.INTEGRATION_MANAGE : Permission.INTEGRATION_CONNECT);
        if (providers.demo()) {
            IntegrationConnection c = connectionFor(principal.orgId(), principal.userId());
            c.setAccountEmail("demo-mailbox@pitsch.demo");
            c.setStatus(IntegrationConnection.Status.CONNECTED.name());
            Set<Integration> enabled = c.enabled();
            enabled.addAll(requested);
            c.setEnabled(enabled);
            c.setGrantedScopes("demo");
            connections.save(c);
            audit.record(principal.orgId(), principal.userId(), AuditAction.INTEGRATION_CONNECTED, "INTEGRATION", c.getId(),
                    Map.of("integrations", names(requested), "demo", true));
            return props.getFrontendUrl() + safeReturnPath(returnPath) + "?connected=" + names(requested).toLowerCase();
        }
        oauth.requireConfigured();
        IntegrationConnection existing = connections.findByOrganizationIdAndUserIdAndProvider(principal.orgId(),
                principal.userId(), PROVIDER).orElse(null);
        Set<String> scopes = new LinkedHashSet<>(Integration.IDENTITY_SCOPES);
        Set<Integration> all = EnumSet.copyOf(requested);
        if (existing != null && IntegrationConnection.Status.CONNECTED.name().equals(existing.getStatus())) {
            all.addAll(existing.enabled());   // keep previously granted integrations in the new grant
        }
        all.forEach(i -> scopes.addAll(i.scopes()));
        String state = Hashing.randomToken();
        String verifier = Hashing.randomToken() + Hashing.randomToken().substring(0, 20);
        OAuthState s = new OAuthState();
        s.setStateHash(Hashing.sha256Hex(state));
        s.setPurpose(OAuthState.Purpose.CONNECT.name());
        s.setOrganizationId(principal.orgId());
        s.setUserId(principal.userId());
        s.setIntegrations(names(all));
        s.setCodeVerifierEnc(cipher.encrypt(verifier));
        s.setReturnPath(safeReturnPath(returnPath));
        s.setCreatedAt(clock.instant());
        s.setExpiresAt(clock.instant().plus(STATE_TTL));
        states.save(s);
        return oauth.authorizationUrl(new ArrayList<>(scopes), state, verifier, principal.email());
    }

    @Transactional
    public String startLogin(String returnPath) {
        if (!props.getGoogle().isConfigured() || !props.getGoogle().isLoginEnabled()) {
            throw new ApiException(ErrorCode.INTEGRATION_NOT_CONFIGURED, "Google sign-in is not enabled on this server.");
        }
        String state = Hashing.randomToken();
        String verifier = Hashing.randomToken() + Hashing.randomToken().substring(0, 20);
        OAuthState s = new OAuthState();
        s.setStateHash(Hashing.sha256Hex(state));
        s.setPurpose(OAuthState.Purpose.LOGIN.name());
        s.setIntegrations("");
        s.setCodeVerifierEnc(cipher.encrypt(verifier));
        s.setReturnPath(safeReturnPath(returnPath));
        s.setCreatedAt(clock.instant());
        s.setExpiresAt(clock.instant().plus(STATE_TTL));
        states.save(s);
        return oauth.authorizationUrl(List.of("openid", "email", "profile"), state, verifier, null);
    }

    /**
     * Handles Google's redirect for both purposes. Never throws: failures redirect to the frontend with an error.
     * Deliberately not one transaction: the state is consumed first so a failed exchange cannot be replayed.
     */
    public CallbackResult completeCallback(String code, String state, String error, ClientInfo client) {
        OAuthState s = state == null ? null : states.findById(Hashing.sha256Hex(state)).orElse(null);
        if (s == null || s.getUsedAt() != null || s.getExpiresAt().isBefore(clock.instant())) {
            return new CallbackResult(props.getFrontendUrl() + "/integrations?error=expired", null);
        }
        s.setUsedAt(clock.instant());
        states.save(s);
        boolean login = OAuthState.Purpose.LOGIN.name().equals(s.getPurpose());
        String failurePage = login ? "/login" : s.getReturnPath();
        if (error != null || code == null) {
            return new CallbackResult(props.getFrontendUrl() + failurePage + "?error=" + (error == null ? "cancelled" : "denied"), null);
        }
        try {
            GoogleOAuthClient.Tokens tokens = oauth.exchange(code, cipher.decrypt(s.getCodeVerifierEnc()));
            GoogleOAuthClient.Identity identity = oauth.identity(tokens.idToken());
            if (login) {
                if (identity.email() == null || !identity.emailVerified()) {
                    return new CallbackResult(props.getFrontendUrl() + "/login?error=unverified", null);
                }
                SessionService.Issued session = auth.loginWithGoogle(identity.email(), identity.name(), client);
                oauth.revoke(tokens.accessToken());   // sign-in needs no API access
                return new CallbackResult(props.getFrontendUrl() + "/auth/callback", session);
            }
            Set<Integration> requested = parse(s.getIntegrations());
            Set<String> granted = tokens.scope() == null ? Set.of()
                    : new LinkedHashSet<>(Arrays.asList(tokens.scope().split(" ")));
            Set<Integration> grantedIntegrations = EnumSet.noneOf(Integration.class);
            for (Integration i : requested) {
                if (granted.containsAll(i.scopes())) {
                    grantedIntegrations.add(i);
                }
            }
            IntegrationConnection c = connectionFor(s.getOrganizationId(), s.getUserId());
            c.setAccountEmail(identity.email());
            c.setGrantedScopes(String.join(" ", granted));
            c.setAccessTokenEnc(cipher.encrypt(tokens.accessToken()));
            c.setAccessTokenExpiresAt(clock.instant().plusSeconds(tokens.expiresInSeconds()));
            if (tokens.refreshToken() != null) {
                c.setRefreshTokenEnc(cipher.encrypt(tokens.refreshToken()));
            }
            if (c.getRefreshTokenEnc() == null) {
                return new CallbackResult(props.getFrontendUrl() + s.getReturnPath() + "?error=no_refresh_token", null);
            }
            c.setEnabled(grantedIntegrations);
            c.setStatus(IntegrationConnection.Status.CONNECTED.name());
            c.setLastError(null);
            c = connections.save(c);
            audit.record(s.getOrganizationId(), s.getUserId(), AuditAction.INTEGRATION_CONNECTED, "INTEGRATION", c.getId(),
                    Map.of("integrations", names(grantedIntegrations)));
            if (grantedIntegrations.contains(Integration.GMAIL)) {
                jobs.enqueue(JobType.GMAIL_WATCH, s.getOrganizationId(), null, Map.of("connectionId", c.getId()));
                jobs.enqueue(JobType.GMAIL_SYNC, s.getOrganizationId(), null, Map.of("connectionId", c.getId()));
            }
            String missing = requested.size() > grantedIntegrations.size() ? "&partial=1" : "";
            return new CallbackResult(props.getFrontendUrl() + s.getReturnPath() + "?connected="
                    + names(grantedIntegrations).toLowerCase() + missing, null);
        } catch (ApiException e) {
            log.warn("Google OAuth callback failed: {}", e.getMessage());
            return new CallbackResult(props.getFrontendUrl() + failurePage + "?error=oauth_failed", null);
        }
    }

    // ------------------------------------------------------------------ disconnect

    @Transactional
    public void disconnect(AuthPrincipal principal, Integration integration) {
        principal.require(integration == Integration.SHEETS ? Permission.INTEGRATION_MANAGE : Permission.INTEGRATION_CONNECT);
        IntegrationConnection c = connections.findByOrganizationIdAndUserIdAndProvider(principal.orgId(), principal.userId(),
                PROVIDER).orElseThrow(() -> ApiException.notFound("Connection"));
        Set<Integration> enabled = c.enabled();
        if (integration == Integration.GMAIL && enabled.contains(Integration.GMAIL) && !providers.demo()
                && c.getGmailWatchExpiresAt() != null) {
            try {
                gmail.stopWatch(c);
            } catch (RuntimeException e) {
                log.info("Could not stop Gmail watch for connection {} (continuing)", c.getId());
            }
            c.setGmailWatchExpiresAt(null);
        }
        enabled.remove(integration);
        c.setEnabled(enabled);
        if (enabled.isEmpty()) {
            revokeAndClear(c);
        }
        connections.save(c);
        audit.record(principal.orgId(), principal.userId(), AuditAction.INTEGRATION_DISCONNECTED, "INTEGRATION", c.getId(),
                Map.of("integration", integration.name(), "revoked", enabled.isEmpty()));
    }

    /** Revokes the Google grant and deletes the encrypted tokens (also used by account/workspace deletion). */
    @Transactional
    public void revokeAndClear(IntegrationConnection c) {
        if (!providers.demo() && c.getRefreshTokenEnc() != null) {
            try {
                oauth.revoke(cipher.decrypt(c.getRefreshTokenEnc()));
            } catch (RuntimeException e) {
                log.info("Token revocation for connection {} failed; tokens deleted locally anyway", c.getId());
            }
        }
        c.setAccessTokenEnc(null);
        c.setRefreshTokenEnc(null);
        c.setAccessTokenExpiresAt(null);
        c.setGmailHistoryId(null);
        c.setEnabled(EnumSet.noneOf(Integration.class));
        c.setStatus(IntegrationConnection.Status.REVOKED.name());
    }

    // ------------------------------------------------------------------ use

    @Transactional(readOnly = true)
    public Optional<IntegrationConnection> usable(Long orgId, Long userId, Integration integration) {
        return connections.findByOrganizationIdAndUserIdAndProvider(orgId, userId, PROVIDER)
                .filter(c -> c.isUsableFor(integration));
    }

    public IntegrationConnection require(Long orgId, Long userId, Integration integration) {
        return usable(orgId, userId, integration).orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_NOT_CONNECTED,
                "Connect your Google " + label(integration) + " in Integrations first."));
    }

    /** The user's busy time: Google free/busy (when Calendar is connected) plus Pitsch calendar entries. */
    public List<AgentInputs.BusyInterval> busy(Long orgId, Long userId, Instant from, Instant to) {
        List<AgentInputs.BusyInterval> out = new ArrayList<>();
        Optional<IntegrationConnection> cal = usable(orgId, userId, Integration.CALENDAR);
        if (cal.isPresent() || providers.demo()) {
            for (CalendarProvider.Busy b : providers.calendar().freeBusy(cal.orElse(null),
                    cal.map(IntegrationConnection::getCalendarId).orElse(null), from, to)) {
                out.add(new AgentInputs.BusyInterval(b.start(), b.end()));
            }
        }
        for (CalendarEvent e : events.findByOrganizationIdAndUserIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(orgId,
                userId, from, to)) {
            if (!CalendarEvent.SyncStatus.CANCELLED.name().equals(e.getSyncStatus())
                    && !CalendarEvent.SyncStatus.CANCELLED_EXTERNALLY.name().equals(e.getSyncStatus())
                    && !CalendarEvent.SyncStatus.SYNCED.name().equals(e.getSyncStatus())) {   // synced ones are in free/busy
                out.add(new AgentInputs.BusyInterval(e.getStartTime(), e.getEndTime()));
            }
        }
        return out;
    }

    @Scheduled(cron = "0 41 * * * *")
    @Transactional
    public void purgeExpiredStates() {
        states.deleteExpired(clock.instant().minus(Duration.ofHours(1)));
    }

    // ------------------------------------------------------------------ helpers

    private IntegrationConnection connectionFor(Long orgId, Long userId) {
        return connections.findByOrganizationIdAndUserIdAndProvider(orgId, userId, PROVIDER).orElseGet(() -> {
            IntegrationConnection c = new IntegrationConnection();
            c.setOrganizationId(orgId);
            c.setUserId(userId);
            c.setProvider(PROVIDER);
            c.setStatus(IntegrationConnection.Status.REVOKED.name());
            return c;
        });
    }

    private static String safeReturnPath(String path) {
        if (path == null || !path.matches("^/[A-Za-z0-9/_-]{0,100}$")) {
            return "/integrations";
        }
        return path;
    }

    private static Set<Integration> parse(String csv) {
        Set<Integration> out = EnumSet.noneOf(Integration.class);
        if (csv != null) {
            for (String s : csv.split(",")) {
                if (!s.isBlank()) {
                    out.add(Integration.valueOf(s.trim()));
                }
            }
        }
        return out;
    }

    private static String names(Set<Integration> set) {
        return set.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static String label(Integration i) {
        return switch (i) {
            case GMAIL -> "Gmail";
            case CALENDAR -> "Calendar";
            case SHEETS -> "Sheets";
        };
    }
}
