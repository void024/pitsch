package com.pitsch.backend.notification;

import com.pitsch.backend.common.Json;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    public void notify(Long userId, String type, String title, String message, Long workflowId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(Json.truncate(message, 1000));
        n.setWorkflowId(workflowId);
        repo.save(n);
    }
}
