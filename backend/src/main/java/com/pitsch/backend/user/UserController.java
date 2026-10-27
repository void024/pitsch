package com.pitsch.backend.user;

import java.time.DateTimeException;
import java.time.ZoneId;

import com.pitsch.backend.auth.AllowUnverified;
import com.pitsch.backend.auth.AllowWithoutWorkspace;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.AuthService;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserView;
import com.pitsch.backend.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in user's profile and personal preferences (not workspace settings). */
@RestController
public class UserController {

    /** Changing {@code email} requires {@code currentPassword} and is confirmed from the new address. */
    public record ProfileRequest(@Size(max = 120) String name, @Size(max = 320) String email,
                                 @Size(max = 200) String currentPassword) { }

    public record Notifications(boolean email, boolean taskReminders, boolean eventReminders, boolean workflowUpdates) { }

    public record SettingsDto(String timeFormat, boolean compactMode, Notifications notifications,
                              String timezone, String firmName, String investorTitle) { }

    private final AuthService auth;
    private final SettingsService settings;

    public UserController(AuthService auth, SettingsService settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PutMapping({"/api/v1/users/me", "/api/users/me"})
    public UserView updateProfile(AuthPrincipal principal, @Valid @RequestBody ProfileRequest req) {
        User user = auth.updateProfile(principal, req.name(), req.email(), req.currentPassword());
        return UserView.of(user, principal.role() == null ? null : principal.role().name());
    }

    @AllowWithoutWorkspace
    @GetMapping({"/api/v1/users/me/settings", "/api/users/me/settings"})
    public SettingsDto getSettings(AuthPrincipal principal) {
        return toDto(settings.forUser(principal.userId()), settings.timezone(principal.userId()));
    }

    @AllowUnverified
    @AllowWithoutWorkspace
    @PutMapping({"/api/v1/users/me/settings", "/api/users/me/settings"})
    public SettingsDto updateSettings(AuthPrincipal principal, @RequestBody SettingsDto req) {
        UserSettings s = settings.forUser(principal.userId());
        if (req.timeFormat() != null) {
            if (!req.timeFormat().equals("12h") && !req.timeFormat().equals("24h")) {
                throw ApiException.badRequest("timeFormat must be 12h or 24h");
            }
            s.setTimeFormat(req.timeFormat());
        }
        s.setCompactMode(req.compactMode());
        if (req.notifications() != null) {
            s.setNotifyEmail(req.notifications().email());
            s.setNotifyTaskReminders(req.notifications().taskReminders());
            s.setNotifyEventReminders(req.notifications().eventReminders());
            s.setNotifyWorkflowUpdates(req.notifications().workflowUpdates());
        }
        if (req.timezone() != null && !req.timezone().isBlank()) {
            try {
                ZoneId.of(req.timezone());
            } catch (DateTimeException e) {
                throw ApiException.badRequest("Unknown time zone: " + req.timezone());
            }
            s.setTimezone(req.timezone());
        }
        if (req.firmName() != null) {
            s.setFirmName(trim(req.firmName(), 200));
        }
        if (req.investorTitle() != null) {
            s.setInvestorTitle(trim(req.investorTitle(), 200));
        }
        settings.save(s);
        return toDto(s, settings.timezone(principal.userId()));
    }

    private static String trim(String v, int max) {
        String t = v.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static SettingsDto toDto(UserSettings s, String timezone) {
        return new SettingsDto(s.getTimeFormat(), s.isCompactMode(),
                new Notifications(s.isNotifyEmail(), s.isNotifyTaskReminders(), s.isNotifyEventReminders(),
                        s.isNotifyWorkflowUpdates()),
                timezone, s.getFirmName(), s.getInvestorTitle());
    }
}
