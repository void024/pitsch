package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Signup, login, sessions, refresh-token rotation and reuse detection, lockout, error format. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestAiConfig.class)
class AuthTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    TestSupport api;

    @BeforeEach
    void setUp() {
        api = new TestSupport(mvc, om);
    }

    @Test
    void signupCreatesUserWorkspaceAndSession() throws Exception {
        TestSupport.Account a = api.signup("Alice");
        assertNotNull(a.token());
        assertNotNull(a.refreshToken(), "refresh token must be set as an httpOnly cookie");
        JsonNode me = api.call("GET", "/api/v1/me", a.token(), null, 200);
        assertEquals(a.email(), me.path("user").path("email").asText());
        assertEquals("OWNER", me.path("workspace").path("role").asText());
        assertEquals("Alice Capital", me.path("workspace").path("name").asText());
        assertTrue(me.path("permissions").toString().contains("ORG_DELETE"));
        // The password hash is never part of any response.
        assertFalse(me.toString().contains("passwordHash"));
    }

    @Test
    void refreshCookieIsHttpOnlyAndScoped() throws Exception {
        MvcResult r = api.perform("POST", "/api/v1/auth/signup", null,
                "{\"name\":\"Cookie\",\"email\":\"cookie-" + System.nanoTime() + "@example.com\",\"password\":\""
                        + TestSupport.PASSWORD + "\"}", null);
        assertEquals(201, r.getResponse().getStatus());
        Cookie c = r.getResponse().getCookie("pitsch_refresh");
        assertNotNull(c);
        assertTrue(c.isHttpOnly());
        assertEquals("/api/v1/auth", c.getPath());
    }

    @Test
    void weakPasswordsAndDuplicateEmailsAreRejected() throws Exception {
        api.call("POST", "/api/v1/auth/signup", null,
                "{\"name\":\"Weak\",\"email\":\"weak@example.com\",\"password\":\"short\"}", 400);
        api.call("POST", "/api/v1/auth/signup", null,
                "{\"name\":\"Weak\",\"email\":\"weak@example.com\",\"password\":\"onlyletterspassword\"}", 400);
        TestSupport.Account a = api.signup("Dup");
        JsonNode err = api.call("POST", "/api/v1/auth/signup", null,
                "{\"name\":\"Dup\",\"email\":\"" + a.email() + "\",\"password\":\"" + TestSupport.PASSWORD + "\"}", 409);
        assertEquals("CONFLICT", err.path("code").asText());
    }

    @Test
    void loginRejectsWrongPasswordWithGenericMessage() throws Exception {
        TestSupport.Account a = api.signup("Bob");
        JsonNode err = api.call("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + a.email() + "\",\"password\":\"wrong-password-1\"}", 401);
        assertEquals("INVALID_CREDENTIALS", err.path("code").asText());
        JsonNode unknown = api.call("POST", "/api/v1/auth/login", null,
                "{\"email\":\"nobody-" + System.nanoTime() + "@example.com\",\"password\":\"wrong-password-1\"}", 401);
        assertEquals(err.path("message").asText(), unknown.path("message").asText(), "no account enumeration");
        assertNotNull(api.login(a.email(), TestSupport.PASSWORD));
        // Legacy endpoint still works for older clients.
        assertFalse(api.call("POST", "/api/auth/login", null,
                "{\"email\":\"" + a.email() + "\",\"password\":\"" + TestSupport.PASSWORD + "\"}", 200).path("token").asText().isEmpty());
    }

    @Test
    void accountLocksAfterRepeatedFailures() throws Exception {
        TestSupport.Account a = api.signup("Locky");
        for (int i = 0; i < 5; i++) {
            api.call("POST", "/api/v1/auth/login", null, "{\"email\":\"" + a.email() + "\",\"password\":\"bad-password-" + i + "\"}", 401);
        }
        JsonNode locked = api.call("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + a.email() + "\",\"password\":\"" + TestSupport.PASSWORD + "\"}", 423);
        assertEquals("ACCOUNT_LOCKED", locked.path("code").asText());
    }

    @Test
    void refreshRotatesAndDetectsReuse() throws Exception {
        TestSupport.Account a = api.signup("Rota");
        MvcResult first = api.refresh(a.refreshToken());
        assertEquals(200, first.getResponse().getStatus(), first.getResponse().getContentAsString());
        String rotated = first.getResponse().getCookie("pitsch_refresh").getValue();
        assertNotEquals(a.refreshToken(), rotated);
        String newAccess = om.readTree(first.getResponse().getContentAsString()).path("accessToken").asText();
        api.call("GET", "/api/v1/me", newAccess, null, 200);

        // Without the CSRF header the refresh endpoint refuses.
        MvcResult noHeader = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/refresh").cookie(new Cookie("pitsch_refresh", rotated))).andReturn();
        assertEquals(403, noHeader.getResponse().getStatus());

        // Rotate again so the original token is two generations old, then replay it: the session is revoked.
        MvcResult second = api.refresh(rotated);
        assertEquals(200, second.getResponse().getStatus());
        String latest = second.getResponse().getCookie("pitsch_refresh").getValue();
        Thread.sleep(10);
        MvcResult reuse = api.refresh(a.refreshToken());
        assertEquals(401, reuse.getResponse().getStatus());
        // After reuse detection, even the latest token of that session is dead.
        assertEquals(401, api.refresh(latest).getResponse().getStatus());
    }

    @Test
    void logoutRevokesTheSession() throws Exception {
        TestSupport.Account a = api.signup("Leaver");
        api.call("GET", "/api/v1/me", a.token(), null, 200);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + a.token()).header("X-Requested-With", "pitsch")
                .cookie(new Cookie("pitsch_refresh", a.refreshToken()))).andReturn();
        api.call("GET", "/api/v1/me", a.token(), null, 401);
        assertEquals(401, api.refresh(a.refreshToken()).getResponse().getStatus());
    }

    @Test
    void changePasswordRevokesOtherSessions() throws Exception {
        TestSupport.Account a = api.signup("Changer");
        String other = api.login(a.email(), TestSupport.PASSWORD);
        api.call("GET", "/api/v1/me", other, null, 200);
        api.call("POST", "/api/v1/auth/change-password", a.token(),
                "{\"currentPassword\":\"" + TestSupport.PASSWORD + "\",\"newPassword\":\"a-brand-new-secret-9\"}", 200);
        api.call("GET", "/api/v1/me", other, null, 401);
        api.call("GET", "/api/v1/me", a.token(), null, 200);
        api.login(a.email(), "a-brand-new-secret-9");
    }

    @Test
    void protectedEndpointsRequireAValidToken() throws Exception {
        JsonNode err = api.call("GET", "/api/v1/pitches", null, null, 401);
        assertEquals("UNAUTHENTICATED", err.path("code").asText());
        assertFalse(err.path("requestId").asText().isEmpty(), "errors carry a request id");
        api.call("GET", "/api/v1/pitches", "not-a-jwt", null, 401);
        api.call("GET", "/api/v1/pitches", "eyJhbGciOiJub25lIn0.eyJzdWIiOiIxIn0.", null, 401);
    }

    @Test
    void forgotPasswordNeverRevealsWhetherAnAccountExists() throws Exception {
        TestSupport.Account a = api.signup("Forgetful");
        JsonNode known = api.call("POST", "/api/v1/auth/forgot-password", null, "{\"email\":\"" + a.email() + "\"}", 202);
        JsonNode unknown = api.call("POST", "/api/v1/auth/forgot-password", null,
                "{\"email\":\"ghost-" + System.nanoTime() + "@example.com\"}", 202);
        assertEquals(known.toString(), unknown.toString());
        api.call("POST", "/api/v1/auth/reset-password", null, "{\"token\":\"invalid-token\",\"password\":\"another-secret-77\"}", 400);
    }
}
