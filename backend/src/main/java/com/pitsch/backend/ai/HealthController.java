package com.pitsch.backend.ai;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final AiClient ai;

    public HealthController(AiClient ai) {
        this.ai = ai;
    }

    /** Public: lets the UI (and you) check that the backend and the AI service are both up. */
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", "ok");
        out.put("aiService", ai.isHealthy() ? "up" : "down");
        return out;
    }
}
