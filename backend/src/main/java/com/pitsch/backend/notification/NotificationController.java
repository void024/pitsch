package com.pitsch.backend.notification;

import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Notification> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return repo.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    @PatchMapping("/{id}/read")
    public Notification markRead(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        Notification n = repo.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Notification"));
        n.setRead(true);
        return repo.save(n);
    }

    @PatchMapping("/read-all")
    public Map<String, Object> markAllRead(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        List<Notification> unread = repo.findByUserIdAndReadFalse(userId);
        unread.forEach(n -> n.setRead(true));
        repo.saveAll(unread);
        return Map.of("updated", unread.size());
    }
}
