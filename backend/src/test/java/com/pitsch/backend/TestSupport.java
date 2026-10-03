package com.pitsch.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/** Small HTTP helper over MockMvc: call(method, url, token, body, expectedStatus) -> parsed JSON. */
public final class TestSupport {

    private final MockMvc mvc;
    private final ObjectMapper om;

    public TestSupport(MockMvc mvc, ObjectMapper om) {
        this.mvc = mvc;
        this.om = om;
    }

    public JsonNode call(String method, String url, String token, Object body, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder req = MockMvcRequestBuilders.request(HttpMethod.valueOf(method), url);
        if (token != null) {
            req.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            req.contentType(MediaType.APPLICATION_JSON).content(body instanceof String s ? s : om.writeValueAsString(body));
        }
        String text = mvc.perform(req)
                .andExpect(MockMvcResultMatchers.status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return text == null || text.isBlank() ? om.createObjectNode() : om.readTree(text);
    }

    public String login(String email, String password) throws Exception {
        return call("POST", "/api/auth/login", null,
                "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}", 200).path("token").asText();
    }
}
