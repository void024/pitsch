package com.pitsch.backend.activity;

import java.util.List;

import com.pitsch.backend.auth.AuthInterceptor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController {

    private final ActivityRepository repo;

    public ActivityController(ActivityRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/api/activity")
    public List<Activity> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return repo.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }
}
