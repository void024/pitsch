package com.pitsch.backend.activity;

import com.pitsch.backend.common.Json;
import org.springframework.stereotype.Service;

@Service
public class ActivityService {

    private final ActivityRepository repo;

    public ActivityService(ActivityRepository repo) {
        this.repo = repo;
    }

    public void log(Long userId, String type, String message) {
        Activity a = new Activity();
        a.setUserId(userId);
        a.setType(type);
        a.setMessage(Json.truncate(message, 1000));
        repo.save(a);
    }
}
