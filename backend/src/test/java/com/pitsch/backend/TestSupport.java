package com.pitsch.backend;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** HTTP helper over MockMvc: call(method, url, token, body, expectedStatus) -> parsed JSON, plus account helpers. */
public final class TestSupport {

    public static final String PASSWORD = "correct-horse-42";

    /** A signed-up user: access token, refresh cookie value, ids. */
    public record Account(String email, String token, String refreshToken, long userId, long organizationId) { }

    private final MockMvc mvc;
    private final ObjectMapper om;

    public TestSupport(MockMvc mvc, ObjectMapper om) {
        this.mvc = mvc;
        this.om = om;
    }

    public JsonNode call(String method, String url, String token, Object body, int expectedStatus) throws Exception {
        MvcResult result = perform(method, url, token, body, null);
        int status = result.getResponse().getStatus();
        String text = result.getResponse().getContentAsString();
        if (status != expectedStatus) {
            throw new AssertionError(method + " " + url + " expected HTTP " + expectedStatus + " but got " + status + ": " + text);
        }
        return text == null || text.isBlank() ? om.createObjectNode() : om.readTree(text);
    }

    public MvcResult perform(String method, String url, String token, Object body, String idempotencyKey) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), url);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (idempotencyKey != null) {
            req.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : om.writeValueAsString(body));
        }
        return mvc.perform(req).andReturn();
    }

    /** Signs up a fresh user (own workspace). */
    public Account signup(String name) throws Exception {
        String email = name.toLowerCase().replaceAll("[^a-z]", "") + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        MvcResult r = perform("POST", "/api/v1/auth/signup", null,
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                        + "\",\"workspaceName\":\"" + name + " Capital\",\"timezone\":\"UTC\"}", null);
        if (r.getResponse().getStatus() != 201) {
            throw new AssertionError("signup failed: " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString());
        }
        JsonNode body = om.readTree(r.getResponse().getContentAsString());
        Cookie cookie = r.getResponse().getCookie("pitsch_refresh");
        return new Account(email, body.path("accessToken").asText(), cookie == null ? null : cookie.getValue(),
                body.path("user").path("id").asLong(), body.path("me").path("workspace").path("id").asLong());
    }

    public String login(String email, String password) throws Exception {
        return call("POST", "/api/v1/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", 200).path("accessToken").asText();
    }

    /** POST /api/v1/auth/refresh with the refresh cookie (and the CSRF header). */
    public MvcResult refresh(String refreshToken) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.post("/api/v1/auth/refresh")
                .header("X-Requested-With", "pitsch");
        if (refreshToken != null) {
            req.cookie(new Cookie("pitsch_refresh", refreshToken));
        }
        return mvc.perform(req).andReturn();
    }

    public ObjectMapper om() {
        return om;
    }
}
