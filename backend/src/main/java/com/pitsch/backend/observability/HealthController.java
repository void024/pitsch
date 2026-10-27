package com.pitsch.backend.observability;

import java.util.LinkedHashMap;
import java.util.Map;

import com.pitsch.backend.ai.AiClient;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Probes for the platform: {@code /health/live} (process is alive) and {@code /health/ready} (database reachable and
 * the application accepts traffic). Neither exposes versions, hosts or stack traces.
 */
@RestController
public class HealthController {

    private final ApplicationAvailability availability;
    private final JdbcTemplate jdbc;
    private final AiClient ai;

    public HealthController(ApplicationAvailability availability, JdbcTemplate jdbc, AiClient ai) {
        this.availability = availability;
        this.jdbc = jdbc;
        this.ai = ai;
    }

    @GetMapping("/health/live")
    public ResponseEntity<Map<String, Object>> live() {
        boolean up = availability.getLivenessState() == LivenessState.CORRECT;
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", up ? "UP" : "DOWN"));
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        boolean accepting = availability.getReadinessState() == ReadinessState.ACCEPTING_TRAFFIC;
        boolean database = databaseUp();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", accepting && database ? "UP" : "DOWN");
        body.put("database", database ? "UP" : "DOWN");
        return ResponseEntity.status(accepting && database ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Legacy public health endpoint used by the existing UI; also reports AI service reachability. */
    @GetMapping({"/api/health", "/api/v1/health"})
    @com.pitsch.backend.auth.PublicEndpoint
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", databaseUp() ? "ok" : "degraded");
        out.put("aiService", ai.isHealthy() ? "up" : "down");
        return out;
    }

    private boolean databaseUp() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
