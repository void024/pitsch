package com.pitsch.backend.activity;

import com.pitsch.backend.common.Json;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    private final ActivityRepository repo;

    public ActivityService(ActivityRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public void log(Long organizationId, Long userId, String type, String message) {
        Activity a = new Activity();
        a.setOrganizationId(organizationId);
        a.setUserId(userId);
        a.setType(type);
        a.setMessage(Json.truncate(message, 1000));
        repo.save(a);
    }
}
