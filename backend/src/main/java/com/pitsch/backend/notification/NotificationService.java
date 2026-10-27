package com.pitsch.backend.notification;

import java.util.EnumMap;
import java.util.Map;

import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.RateLimiter;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.user.SettingsService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications plus optional email (queued, never inline). Anti-spam: one notification per dedupe key,
 * per-type preferences, the user's master email switch and at most 20 notification emails per user per minute window.
 */
@Service
public class NotificationService {

    private static final int EMAILS_PER_MINUTE = 20;

    private final NotificationRepository repo;
    private final NotificationPreferenceRepository prefs;
    private final SettingsService settings;
    private final JobQueue jobs;
    private final RateLimiter limiter;

    public NotificationService(NotificationRepository repo, NotificationPreferenceRepository prefs,
                               SettingsService settings, JobQueue jobs, RateLimiter limiter) {
        this.repo = repo;
        this.prefs = prefs;
        this.settings = settings;
        this.jobs = jobs;
        this.limiter = limiter;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notify(Long orgId, Long userId, NotificationType type, String title, String message, Long workflowId,
                       String dedupeKey) {
        if (userId == null) {
            return;
        }
        if (dedupeKey != null && repo.existsByUserIdAndDedupeKey(userId, dedupeKey)) {
            return;
        }
        Map<NotificationType, NotificationPreference> p = preferences(userId, orgId);
        boolean inApp = p.containsKey(type) ? p.get(type).isInApp() : true;
        boolean email = (p.containsKey(type) ? p.get(type).isEmail() : NotificationType.EMAIL_BY_DEFAULT.contains(type))
                && settings.forUser(userId).isNotifyEmail();
        if (!inApp && !email) {
            return;
        }
        Notification n = new Notification();
        n.setOrganizationId(orgId);
        n.setUserId(userId);
        n.setType(type.name());
        n.setTitle(Json.truncate(title, 255));
        n.setMessage(Json.truncate(message, 1000));
        n.setWorkflowId(workflowId);
        n.setDedupeKey(dedupeKey);
        n.setRead(!inApp);
        boolean sendEmail = email && limiter.tryAcquire("notify-email:" + userId, EMAILS_PER_MINUTE);
        n.setEmailStatus(sendEmail ? "PENDING" : "SKIPPED");
        try {
            n = repo.saveAndFlush(n);
        } catch (DataIntegrityViolationException duplicate) {
            return;
        }
        if (sendEmail) {
            jobs.enqueue(JobType.NOTIFICATION_EMAIL, orgId, workflowId, Map.of("notificationId", n.getId()));
        }
    }

    @Transactional(readOnly = true)
    public Map<NotificationType, NotificationPreference> preferences(Long userId, Long orgId) {
        Map<NotificationType, NotificationPreference> out = new EnumMap<>(NotificationType.class);
        for (NotificationPreference pref : prefs.findByUserIdAndOrganizationId(userId, orgId)) {
            try {
                out.put(NotificationType.valueOf(pref.getType()), pref);
            } catch (IllegalArgumentException ignored) {
                // a type that no longer exists
            }
        }
        return out;
    }

    @Transactional
    public void setPreference(Long userId, Long orgId, NotificationType type, boolean inApp, boolean email) {
        NotificationPreference p = prefs.findByUserIdAndOrganizationIdAndType(userId, orgId, type.name()).orElseGet(() -> {
            NotificationPreference x = new NotificationPreference();
            x.setUserId(userId);
            x.setOrganizationId(orgId);
            x.setType(type.name());
            return x;
        });
        p.setInApp(inApp);
        p.setEmail(email);
        prefs.save(p);
    }
}
