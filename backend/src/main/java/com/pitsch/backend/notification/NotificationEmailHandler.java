package com.pitsch.backend.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.mail.EmailTemplates;
import com.pitsch.backend.mail.MailService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Delivers a notification by email. The email links back to Pitsch and never contains pitch content. */
@Component
public class NotificationEmailHandler implements JobHandler {

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final MailService mail;
    private final PitschProperties props;

    public NotificationEmailHandler(NotificationRepository notifications, UserRepository users, MailService mail,
                                    PitschProperties props) {
        this.notifications = notifications;
        this.users = users;
        this.mail = mail;
        this.props = props;
    }

    @Override
    public JobType type() {
        return JobType.NOTIFICATION_EMAIL;
    }

    @Override
    @Transactional
    public void handle(Job job, JsonNode payload) {
        Notification n = notifications.findById(payload.path("notificationId").asLong()).orElse(null);
        if (n == null || !"PENDING".equals(n.getEmailStatus())) {
            return;
        }
        var user = users.findById(n.getUserId()).orElse(null);
        if (user == null) {
            n.setEmailStatus("SKIPPED");
            notifications.save(n);
            return;
        }
        String link = props.getFrontendUrl() + (n.getWorkflowId() == null ? "/notifications" : "/workflows/" + n.getWorkflowId());
        mail.send(EmailTemplates.notification(user.getEmail(), n.getTitle(), n.getMessage(), link));
        n.setEmailStatus("SENT");
        notifications.save(n);
    }

    @Override
    public void onGiveUp(Job job, JsonNode payload, Exception lastError) {
        notifications.findById(payload.path("notificationId").asLong()).ifPresent(n -> {
            n.setEmailStatus("FAILED");
            notifications.save(n);
        });
    }
}
