package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AiClient;
import com.pitsch.backend.config.AppConfig.WorkflowRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** The whole pitch workflow over HTTP, exactly as the frontend drives it (AI service faked with recorded responses). */
@SpringBootTest
@AutoConfigureMockMvc
class WorkflowFlowTests {

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        FakeAiClient fakeAiClient(ObjectMapper om) {
            return new FakeAiClient(om);
        }

        /** Run agent pipelines synchronously so tests don't need to poll. */
        @Bean
        @Primary
        WorkflowRunner syncRunner() {
            return Runnable::run;
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired AiClient ai;
    TestSupport api;
    String token;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestSupport(mvc, om);
        token = api.login("demo@pitsch.com", "pitsch123");
    }

    @Test
    void fullPitchWorkflow() throws Exception {
        FakeAiClient fake = (FakeAiClient) ai;

        // 1. Email arrives (with a deck) -> classified -> waiting for "Handle this pitch?"
        String email = "{\"sender\":\"Ananya@KrishiAI.in\",\"senderName\":\"Ananya Rao\",\"subject\":\"Krishi AI - seed round\","
                + "\"body\":\"Hi, we're raising a seed round for Krishi AI. Deck attached.\",\"threadId\":\"thread-1\","
                + "\"attachments\":[{\"filename\":\"deck.pdf\",\"mimeType\":\"application/pdf\","
                + "\"contentBase64\":\"data:application/pdf;base64,JVBERi0xLjQK\"}]}";
        JsonNode created = api.call("POST", "/api/emails/process", token, email, 202);
        long id = created.path("workflowId").asLong();
        JsonNode wf = api.call("GET", "/api/workflows/" + id, token, null, 200);
        assertEquals("AWAITING_USER", wf.path("status").asText());
        assertEquals("NEW_PITCH", wf.path("type").asText());
        assertEquals("[\"COMPLETE_WORKFLOW\",\"STOP\"]", wf.path("availableActions").toString());
        JsonNode classifierInput = fake.callsTo(Agent.EMAIL_CLASSIFIER).get(0).input();
        assertEquals("ananya@krishiai.in", classifierInput.path("sender").path("email").asText());
        assertEquals("deck.pdf", classifierInput.path("attachments").get(0).path("filename").asText());
        assertTrue(fake.callsTo(Agent.EMAIL_CLASSIFIER).get(0).executionId().startsWith(id + ":EMAIL_CLASSIFIER:"));

        // Actions not on offer are refused; free text is refused.
        api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"PLAN_MEETING\"}", 409);
        api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"plan a meeting\"}", 400);

        // 2. Handle Pitch -> Document, Research, Verification, Analysis -> brief
        wf = api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 202);
        assertEquals("WAITING_FOR_APPROVAL", wf.path("status").asText(), wf.path("error").asText());
        assertTrue(wf.path("brief").path("claimsMatrix").size() > 0);
        assertTrue(wf.path("briefMarkdown").asText().contains("Research Brief"));
        JsonNode docInput = fake.callsTo(Agent.DOCUMENT_AGENT).get(0).input();
        assertEquals("JVBERi0xLjQK", docInput.path("documents").get(0).path("contentBase64").asText());  // data URL stripped
        JsonNode researchInput = fake.callsTo(Agent.RESEARCH_AGENT).get(0).input();
        assertFalse(researchInput.path("companyName").asText().isBlank());
        assertTrue(researchInput.path("claims").size() > 0);
        JsonNode analysisInput = fake.callsTo(Agent.ANALYSIS_AGENT).get(0).input();
        assertTrue(analysisInput.path("document").isObject() && analysisInput.path("research").isObject()
                && analysisInput.path("verification").isObject());

        // 3. Plan meeting -> slots -> pick one (approval) -> meeting in the calendar
        wf = api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"PLAN_MEETING\"}", 202);
        assertEquals("MEETING_SLOTS_READY", wf.path("currentStep").asText());
        JsonNode slots = api.call("GET", "/api/calendar/availability?workflowId=" + id, token, null, 200).path("slots");
        assertTrue(slots.size() > 0);
        String pick = "{\"workflowId\":" + id + ",\"start\":\"" + slots.get(0).path("start").asText()
                + "\",\"end\":\"" + slots.get(0).path("end").asText() + "\"}";
        wf = api.call("POST", "/api/calendar/meeting", token, pick, 200);
        assertEquals("MEETING_SCHEDULED", wf.path("currentStep").asText());
        assertTrue(fake.callsTo(Agent.ACTION_AGENT).stream().anyMatch(c -> c.input().has("approval")));
        JsonNode events = api.call("GET", "/api/events", token, null, 200);
        boolean meetingInCalendar = false;
        for (JsonNode e : events) {
            meetingInCalendar |= "PITSCH".equals(e.path("source").asText());
        }
        assertTrue(meetingInCalendar);

        // 4. Draft email -> edit -> send (approval)
        wf = api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"PLAN_EMAIL_RESPONSE\"}", 202);
        assertEquals("EMAIL_DRAFT_READY", wf.path("currentStep").asText());
        JsonNode emailInput = fake.callsTo(Agent.EMAIL_RESPONSE_AGENT).get(0).input();
        assertEquals("CONFIRM_MEETING", emailInput.path("purpose").asText());   // meeting already scheduled
        assertTrue(emailInput.path("meeting").path("start").asText().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*"));
        long draftId = wf.path("draft").path("id").asLong();
        api.call("PUT", "/api/drafts/" + draftId, token, "{\"subject\":\"Re: Krishi AI\",\"body\":\"Edited body\"}", 200);
        wf = api.call("POST", "/api/drafts/" + draftId + "/send", token, null, 200);
        assertEquals("SENT", wf.path("draft").path("status").asText());
        api.call("POST", "/api/drafts/" + draftId + "/send", token, null, 409);

        // 5. Complete
        wf = api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 202);
        assertEquals("COMPLETED", wf.path("status").asText());
        assertEquals(0, wf.path("availableActions").size());

        // Pitch detail carries the brief; notifications were created; list view works.
        long pitchId = wf.path("pitchId").asLong();
        JsonNode pitch = api.call("GET", "/api/pitches/" + pitchId, token, null, 200);
        assertTrue(pitch.path("brief").path("claimsMatrix").size() > 0);
        assertTrue(api.call("GET", "/api/notifications", token, null, 200).size() > 0);
        assertTrue(api.call("GET", "/api/workflows", token, null, 200).size() > 0);
        api.call("GET", "/api/workflows/" + id + "/brief.md", token, null, 200);
    }

    @Test
    void stopAndValidation() throws Exception {
        api.call("POST", "/api/emails/process", token, "{\"sender\":\"not-an-email\",\"subject\":\"x\"}", 400);
        JsonNode created = api.call("POST", "/api/emails/process", token,
                "{\"sender\":\"founder@zeta.io\",\"subject\":\"Zeta pre-seed\",\"body\":\"Raising $500k\"}", 202);
        long id = created.path("workflowId").asLong();
        JsonNode wf = api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"STOP\"}", 202);
        assertEquals("STOPPED", wf.path("status").asText());
        api.call("POST", "/api/workflows/" + id + "/action", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 409);
        api.call("GET", "/api/workflows/999999", token, null, 404);
    }
}
