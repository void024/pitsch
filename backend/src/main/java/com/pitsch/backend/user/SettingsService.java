package com.pitsch.backend.user;

import java.time.DateTimeException;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {

    private final UserSettingsRepository repo;
    private final String defaultTimezone;

    public SettingsService(UserSettingsRepository repo, @Value("${pitsch.default-timezone:Asia/Kolkata}") String defaultTimezone) {
        this.repo = repo;
        this.defaultTimezone = defaultTimezone;
    }

    public UserSettings forUser(Long userId) {
        return repo.findByUserId(userId).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            return repo.save(s);
        });
    }

    public UserSettings save(UserSettings settings) {
        return repo.save(settings);
    }

    /** The user's IANA time zone, falling back to the configured default if unset or invalid. */
    public String timezone(Long userId) {
        String tz = forUser(userId).getTimezone();
        if (tz != null && !tz.isBlank()) {
            try {
                ZoneId.of(tz);
                return tz;
            } catch (DateTimeException ignored) {
                // fall through to default
            }
        }
        return defaultTimezone;
    }
}
