package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The endpoints the existing frontend pages use: auth, profile, settings, tasks, events, activity. */
@SpringBootTest
@AutoConfigureMockMvc
class AuthAndCrudTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    TestSupport api;

    @BeforeEach
    void setUp() {
        api = new TestSupport(mvc, om);
    }

    @Test
    void demoUserCanLoginAndSeeProfile() throws Exception {
        String token = api.login("demo@pitsch.com", "pitsch123");
        assertFalse(token.isBlank());
        JsonNode me = api.call("GET", "/api/auth/me", token, null, 200);
        assertEquals("demo@pitsch.com", me.path("email").asText());
    }

    @Test
    void wrongPasswordAndMissingTokenAreRejected() throws Exception {
        api.call("POST", "/api/auth/login", null, "{\"email\":\"demo@pitsch.com\",\"password\":\"wrong\"}", 401);
        api.call("GET", "/api/tasks", null, null, 401);
        api.call("GET", "/api/tasks", "not-a-token", null, 401);
    }

    @Test
    void signupThenDuplicateSignupConflicts() throws Exception {
        String body = "{\"name\":\"Riya\",\"email\":\"riya@example.com\",\"password\":\"longpassword\"}";
        JsonNode created = api.call("POST", "/api/auth/signup", null, body, 201);
        assertFalse(created.path("token").asText().isBlank());
        api.call("POST", "/api/auth/signup", null, body, 409);
        api.call("POST", "/api/auth/signup", null, "{\"name\":\"X\",\"email\":\"x@example.com\",\"password\":\"short\"}", 400);
    }

    @Test
    void tasksCrudAndStatus() throws Exception {
        String token = api.login("demo@pitsch.com", "pitsch123");
        JsonNode task = api.call("POST", "/api/tasks", token,
                "{\"title\":\"Call Krishi AI\",\"description\":\"\",\"status\":\"TODO\",\"priority\":\"HIGH\",\"dueDate\":\"2026-10-10\"}", 201);
        long id = task.path("id").asLong();
        assertEquals("2026-10-10", task.path("dueDate").asText());
        assertEquals("DONE", api.call("PATCH", "/api/tasks/" + id + "/status", token, "{\"status\":\"DONE\"}", 200).path("status").asText());
        api.call("PATCH", "/api/tasks/" + id + "/status", token, "{\"status\":\"FINISHED\"}", 400);
        api.call("DELETE", "/api/tasks/" + id, token, null, 204);
        api.call("DELETE", "/api/tasks/" + id, token, null, 404);
    }

    @Test
    void eventsAcceptFrontendIsoTimes() throws Exception {
        String token = api.login("demo@pitsch.com", "pitsch123");
        JsonNode ev = api.call("POST", "/api/events", token, "{\"title\":\"Board call\",\"description\":\"\",\"location\":\"\","
                + "\"startTime\":\"2026-10-12T08:30:00.000Z\",\"endTime\":\"2026-10-12T09:30:00.000Z\"}", 201);
        assertEquals("2026-10-12T08:30:00Z", ev.path("startTime").asText());
        api.call("POST", "/api/events", token, "{\"title\":\"Bad\",\"startTime\":\"2026-10-12T09:30:00Z\",\"endTime\":\"2026-10-12T08:30:00Z\"}", 400);
        assertTrue(api.call("GET", "/api/events", token, null, 200).size() >= 1);
    }

    @Test
    void settingsRoundTripAndActivityLog() throws Exception {
        String token = api.login("demo@pitsch.com", "pitsch123");
        JsonNode s = api.call("GET", "/api/users/me/settings", token, null, 200);
        assertEquals("12h", s.path("timeFormat").asText());
        JsonNode saved = api.call("PUT", "/api/users/me/settings", token, "{\"timeFormat\":\"24h\",\"compactMode\":true,"
                + "\"notifications\":{\"email\":true,\"taskReminders\":false,\"eventReminders\":true,\"workflowUpdates\":true},"
                + "\"timezone\":\"Asia/Kolkata\",\"firmName\":\"Northstar Ventures\"}", 200);
        assertEquals("24h", saved.path("timeFormat").asText());
        assertEquals("Northstar Ventures", saved.path("firmName").asText());
        api.call("PUT", "/api/users/me/settings", token, "{\"timezone\":\"Mars/Base\"}", 400);
        assertTrue(api.call("GET", "/api/activity", token, null, 200).isArray());
    }

    @Test
    void healthIsPublicAndUnknownRoutesAre404() throws Exception {
        JsonNode health = api.call("GET", "/api/health", null, null, 200);
        assertEquals("ok", health.path("status").asText());
        assertEquals("down", health.path("aiService").asText());   // AI service isn't running in tests
        String token = api.login("demo@pitsch.com", "pitsch123");
        api.call("GET", "/api/does-not-exist", token, null, 404);
    }
}
