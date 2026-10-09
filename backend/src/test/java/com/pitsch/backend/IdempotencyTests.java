package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** The Idempotency-Key contract: replay the first result, refuse key reuse for a different request. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestAiConfig.class)
class IdempotencyTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    TestSupport api;

    @BeforeEach
    void setUp() {
        api = new TestSupport(mvc, om);
    }

    @Test
    void repeatedRequestIsReplayedNotExecutedTwice() throws Exception {
        TestSupport.Account a = api.signup("Iris");
        String body = "{\"companyName\":\"Once Only Ltd\"}";
        MvcResult first = api.perform("POST", "/api/v1/pitches", a.token(), body, "create-pitch-0001");
        MvcResult second = api.perform("POST", "/api/v1/pitches", a.token(), body, "create-pitch-0001");
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertNull(first.getResponse().getHeader("Idempotent-Replayed"));
        assertEquals("true", second.getResponse().getHeader("Idempotent-Replayed"));
        JsonNode p1 = om.readTree(first.getResponse().getContentAsString());
        JsonNode p2 = om.readTree(second.getResponse().getContentAsString());
        assertEquals(p1.path("id").asLong(), p2.path("id").asLong());
        assertEquals(1, api.call("GET", "/api/v1/pitches", a.token(), null, 200).path("totalItems").asLong());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        TestSupport.Account a = api.signup("Jon");
        api.perform("POST", "/api/v1/pitches", a.token(), "{\"companyName\":\"First Co\"}", "create-pitch-0002");
        MvcResult reused = api.perform("POST", "/api/v1/pitches", a.token(), "{\"companyName\":\"Other Co\"}", "create-pitch-0002");
        assertEquals(422, reused.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_KEY_REUSED", om.readTree(reused.getResponse().getContentAsString()).path("code").asText());
    }

    @Test
    void keysAreScopedPerUserAndValidated() throws Exception {
        TestSupport.Account a = api.signup("Kai");
        TestSupport.Account b = api.signup("Lea");
        String body = "{\"companyName\":\"Shared Key Co\"}";
        assertEquals(201, api.perform("POST", "/api/v1/pitches", a.token(), body, "same-key-0003").getResponse().getStatus());
        MvcResult other = api.perform("POST", "/api/v1/pitches", b.token(), body, "same-key-0003");
        assertEquals(201, other.getResponse().getStatus());
        assertNull(other.getResponse().getHeader("Idempotent-Replayed"));   // B's request ran; A's response is never leaked
        assertEquals(400, api.perform("POST", "/api/v1/pitches", a.token(), body, "short").getResponse().getStatus());
    }
}
