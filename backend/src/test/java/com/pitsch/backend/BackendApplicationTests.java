package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.auth.PublicEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Context starts on the real Flyway schema (ddl-auto=validate); probes, headers and the public-endpoint allowlist. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAiConfig.class)
class BackendApplicationTests {

    /**
     * Every endpoint reachable without a session. Adding one is a security decision: update this list deliberately.
     */
    static final Set<String> PUBLIC = Set.of(
            "/api/auth/login", "/api/auth/signin", "/api/auth/signup", "/api/auth/logout",
            "/api/v1/auth/login", "/api/v1/auth/signup", "/api/v1/auth/logout", "/api/v1/auth/refresh",
            "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/verify-email",
            "/api/v1/auth/google/start", "/api/v1/integrations/google/callback", "/api/v1/invitations/{token}",
            "/api/v1/webhooks/gmail", "/api/v1/webhooks/stripe", "/api/v1/files/local", "/api/health", "/api/v1/health");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void probesWork() throws Exception {
        TestSupport api = new TestSupport(mvc, om);
        assertEquals("UP", api.call("GET", "/health/live", null, null, 200).path("status").asText());
        assertEquals("UP", api.call("GET", "/health/ready", null, null, 200).path("database").asText());
        assertEquals("ok", api.call("GET", "/api/health", null, null, 200).path("status").asText());
    }

    @Test
    void securityHeadersAndRequestIdOnEveryResponse() throws Exception {
        MvcResult r = new TestSupport(mvc, om).perform("GET", "/api/v1/pitches", null, null, null);
        assertEquals(401, r.getResponse().getStatus());
        assertEquals("nosniff", r.getResponse().getHeader("X-Content-Type-Options"));
        assertEquals("DENY", r.getResponse().getHeader("X-Frame-Options"));
        assertNotNull(r.getResponse().getHeader("X-Request-Id"));
        assertTrue(r.getResponse().getContentAsString().contains("\"requestId\""));
    }

    @Test
    void onlyAllowlistedEndpointsArePublic() {
        Set<String> actual = new TreeSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            if (h.hasMethodAnnotation(PublicEndpoint.class) || h.getBeanType().isAnnotationPresent(PublicEndpoint.class)) {
                actual.addAll(e.getKey().getPatternValues());
            }
        }
        assertEquals(new TreeSet<>(PUBLIC), actual);
    }
}
