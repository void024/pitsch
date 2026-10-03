package com.pitsch.backend.user;

import java.time.DateTimeException;
import java.time.ZoneId;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.auth.User;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.auth.UserView;
import com.pitsch.backend.common.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    public record ProfileRequest(@NotBlank String name, @NotBlank @Email String email) { }

    public record Notifications(boolean email, boolean taskReminders, boolean eventReminders, boolean workflowUpdates) { }

    /** Matches the frontend's UserSettings type, plus investor details used by the agents. */
    public record SettingsDto(String timeFormat, boolean compactMode, Notifications notifications,
                              String timezone, String firmName, String investorTitle) { }

    private final UserRepository users;
    private final SettingsService settings;

    public UserController(UserRepository users, SettingsService settings) {
        this.users = users;
        this.settings = settings;
    }

    @PutMapping
    public UserView updateProfile(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                  @Valid @RequestBody ProfileRequest req) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
        String email = req.email().trim().toLowerCase();
        users.findByEmailIgnoreCase(email)
                .filter(other -> !other.getId().equals(userId))
                .ifPresent(other -> { throw ApiException.conflict("That email is already used by another account."); });
        user.setName(req.name().trim());
        user.setEmail(email);
        return UserView.of(users.save(user));
    }

    @GetMapping("/settings")
    public SettingsDto getSettings(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return toDto(settings.forUser(userId), settings.timezone(userId));
    }

    @PutMapping("/settings")
    public SettingsDto updateSettings(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                      @RequestBody SettingsDto req) {
        UserSettings s = settings.forUser(userId);
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
            s.setFirmName(req.firmName().trim());
        }
        if (req.investorTitle() != null) {
            s.setInvestorTitle(req.investorTitle().trim());
        }
        settings.save(s);
        return toDto(s, settings.timezone(userId));
    }

    private static SettingsDto toDto(UserSettings s, String timezone) {
        return new SettingsDto(s.getTimeFormat(), s.isCompactMode(),
                new Notifications(s.isNotifyEmail(), s.isNotifyTaskReminders(), s.isNotifyEventReminders(),
                        s.isNotifyWorkflowUpdates()),
                timezone, s.getFirmName(), s.getInvestorTitle());
    }
}
