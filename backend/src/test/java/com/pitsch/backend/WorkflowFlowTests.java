package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AiClient;
import com.pitsch.backend.idempotency.ExternalOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The whole pitch workflow over HTTP, as the frontend drives it, with the AI faked from recorded agent responses and
 * Google replaced by the demo providers (PITSCH_MODE=demo semantics: nothing leaves the process).
 * Covers: human-in-the-loop gates, approvals, idempotent side effects, dedupe, failure + retry, and the state machine.
 */
@SpringBootTest(properties = "pitsch.mode=demo")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestAiConfig.class)
class WorkflowFlowTests {

    static final String KRISHI_EMAIL = "{\"sender\":\"Ananya@KrishiAI.in\",\"senderName\":\"Ananya Rao\",\"subject\":\"Krishi AI - seed round\","
            + "\"body\":\"Hi, we're raising a seed round for Krishi AI. Deck attached.\",\"threadId\":\"thread-1\","
            + "\"messageId\":\"<msg-%s@krishi.example>\","
            + "\"attachments\":[{\"filename\":\"deck.pdf\",\"mimeType\":\"application/pdf\","
            + "\"contentBase64\":\"data:application/pdf;base64,JVBERi0xLjQK\"}]}";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired AiClient ai;
    @Autowired ExternalOperationRepository operations;
    TestSupport api;
    FakeAiClient fake;
    TestSupport.Account investor;

    @BeforeEach
    void setUp() throws Exception {
        api = new TestSupport(mvc, om);
        fake = (FakeAiClient) ai;
        fake.reset();
        investor = api.signup("Ivy");
    }

    @Test
    void fullPitchWorkflow() throws Exception {
        String token = investor.token();

        // 1. Email arrives (with a deck) -> classified -> waiting for "Handle this pitch?"
        JsonNode created = api.call("POST", "/api/v1/emails", token, KRISHI_EMAIL.formatted("full"), 202);
        long id = created.path("workflowId").asLong();
        JsonNode wf = api.call("GET", "/api/v1/workflows/" + id, token, null, 200);
        assertEquals("AWAITING_USER", wf.path("status").asText(), wf.toString());
        assertEquals("NEW_PITCH", wf.path("type").asText());
        assertEquals("[\"COMPLETE_WORKFLOW\",\"STOP\"]", wf.path("availableActions").toString());
        JsonNode classifierInput = fake.callsTo(Agent.EMAIL_CLASSIFIER).get(0).input();
        assertEquals("ananya@krishiai.in", classifierInput.path("sender").path("email").asText());
        assertEquals("deck.pdf", classifierInput.path("attachments").get(0).path("filename").asText());

        // Nothing has been researched yet: the AI does not start deep work without the user's go-ahead.
        assertTrue(fake.callsTo(Agent.DOCUMENT_AGENT).isEmpty());

        // Actions not on offer are refused; free text is refused.
        api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"PLAN_MEETING\"}", 409);
        api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"plan a meeting\"}", 400);

        // 2. Handle pitch -> Document, Research, Verification, Analysis -> brief, waiting for the next decision
        wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 202);
        assertEquals("WAITING_FOR_APPROVAL", wf.path("status").asText(), wf.path("error").asText());
        assertTrue(wf.path("brief").path("claimsMatrix").size() > 0);
        assertFalse(wf.path("briefMarkdown").asText().isBlank());
        JsonNode docInput = fake.callsTo(Agent.DOCUMENT_AGENT).get(0).input();
        assertEquals("JVBERi0xLjQK", docInput.path("documents").get(0).path("contentBase64").asText());
        JsonNode analysisInput = fake.callsTo(Agent.ANALYSIS_AGENT).get(0).input();
        assertTrue(analysisInput.path("document").isObject() && analysisInput.path("research").isObject()
                && analysisInput.path("verification").isObject());
        // Agent execution records carry model, tokens and cost.
        JsonNode executions = api.call("GET", "/api/v1/workflows/" + id + "/executions", token, null, 200);
        assertTrue(executions.size() >= 5);
        assertEquals("fake-model", executions.get(0).path("model").asText());
        assertTrue(executions.get(0).path("promptTokens").asLong() > 0);

        // 3. Plan meeting -> slots -> pick one (= approval) -> meeting recorded (demo calendar)
        wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"PLAN_MEETING\"}", 202);
        assertEquals("MEETING_SLOTS_READY", wf.path("currentStep").asText(), wf.toString());
        JsonNode slots = api.call("GET", "/api/v1/calendar/availability?workflowId=" + id, token, null, 200).path("slots");
        assertTrue(slots.size() > 0);
        String pick = "{\"workflowId\":" + id + ",\"start\":\"" + slots.get(0).path("start").asText()
                + "\",\"end\":\"" + slots.get(0).path("end").asText() + "\"}";
        wf = api.call("POST", "/api/v1/calendar/meetings", token, pick, 200);
        assertEquals("MEETING_SCHEDULED", wf.path("currentStep").asText(), wf.toString());
        assertEquals("DEMO", wf.path("meeting").path("syncStatus").asText());
        JsonNode approvals = api.call("GET", "/api/v1/workflows/" + id + "/approvals", token, null, 200);
        assertTrue(approvals.toString().contains("CREATE_MEETING"));
        assertTrue(approvals.toString().contains("EXECUTED"));
        boolean meetingInCalendar = false;
        for (JsonNode e : api.call("GET", "/api/v1/events", token, null, 200)) {
            meetingInCalendar |= "PITSCH".equals(e.path("source").asText());
        }
        assertTrue(meetingInCalendar);

        // 4. Draft email -> edit -> send (= approval) -> sent exactly once
        wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"PLAN_EMAIL_RESPONSE\"}", 202);
        assertEquals("EMAIL_DRAFT_READY", wf.path("currentStep").asText(), wf.toString());
        JsonNode emailInput = fake.callsTo(Agent.EMAIL_RESPONSE_AGENT).get(0).input();
        assertEquals("CONFIRM_MEETING", emailInput.path("purpose").asText());
        long draftId = wf.path("draft").path("id").asLong();
        // The recipient is the founder of record, never chosen by the model.
        assertEquals("ananya@krishiai.in", wf.path("draft").path("recipient").asText());
        api.call("PUT", "/api/v1/drafts/" + draftId, token, "{\"subject\":\"Re: Krishi AI\",\"body\":\"Edited body\"}", 200);
        wf = api.call("POST", "/api/v1/drafts/" + draftId + "/send", token, null, 200);
        assertEquals("DEMO_SENT", wf.path("draft").path("status").asText(), wf.toString());
        assertEquals("Edited body", wf.path("draft").path("body").asText());
        // A double click / retry does not send twice.
        api.call("POST", "/api/v1/drafts/" + draftId + "/send", token, null, 409);
        long sends = operations.findAll().stream().filter(o -> "GMAIL_SEND".equals(o.getOperationType())
                && o.getIdempotencyKey().startsWith("draft:" + draftId + ":")).count();
        assertEquals(1, sends);

        // 5. Complete: final state, no further actions
        wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 202);
        assertEquals("COMPLETED", wf.path("status").asText());
        assertEquals(0, wf.path("availableActions").size());
        api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"STOP\"}", 409);

        // Pitch detail carries the brief; timeline, notifications, lists and the markdown download work.
        long pitchId = wf.path("pitchId").asLong();
        JsonNode pitch = api.call("GET", "/api/v1/pitches/" + pitchId, token, null, 200);
        assertTrue(pitch.path("brief").path("claimsMatrix").size() > 0);
        assertFalse(pitch.path("pitch").path("riskLevel").asText().isEmpty());
        JsonNode timeline = api.call("GET", "/api/v1/pitches/" + pitchId + "/timeline", token, null, 200);
        assertTrue(timeline.toString().contains("EMAIL_RECEIVED"));
        assertTrue(api.call("GET", "/api/v1/notifications", token, null, 200).path("totalItems").asLong() > 0);
        assertTrue(api.call("GET", "/api/v1/workflows?status=COMPLETED", token, null, 200).path("totalItems").asLong() >= 1);
        MvcResult brief = api.perform("GET", "/api/v1/workflows/" + id + "/brief.md", token, null, null);
        assertEquals(200, brief.getResponse().getStatus());
        assertTrue(brief.getResponse().getContentType().startsWith("text/markdown"));
        assertFalse(brief.getResponse().getContentAsString().isBlank());
    }

    @Test
    void duplicateEmailsAreProcessedOnce() throws Exception {
        String token = investor.token();
        JsonNode first = api.call("POST", "/api/v1/emails", token, KRISHI_EMAIL.formatted("dup"), 202);
        JsonNode second = api.call("POST", "/api/v1/emails", token, KRISHI_EMAIL.formatted("dup"), 200);
        assertTrue(second.path("duplicate").asBoolean());
        assertEquals(first.path("emailId").asLong(), second.path("emailId").asLong());
        assertEquals(first.path("workflowId").asLong(), second.path("workflowId").asLong());
        assertEquals(1, fake.callsTo(Agent.EMAIL_CLASSIFIER).size());
    }

    @Test
    void agentFailureIsVisibleAndRetryable() throws Exception {
        String token = investor.token();
        long id = api.call("POST", "/api/v1/emails", token, KRISHI_EMAIL.formatted("fail"), 202).path("workflowId").asLong();
        fake.failing = Agent.DOCUMENT_AGENT;
        JsonNode wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 202);
        assertEquals("FAILED", wf.path("status").asText(), wf.toString());
        assertTrue(wf.path("error").asText().contains("simulated provider failure"));
        assertEquals("[\"RETRY\",\"STOP\"]", wf.path("availableActions").toString());
        JsonNode executions = api.call("GET", "/api/v1/workflows/" + id + "/executions", token, null, 200);
        assertTrue(executions.toString().contains("PROVIDER"), "failed agent calls are recorded with an error category");

        fake.failing = null;
        wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"RETRY\"}", 202);
        assertEquals("WAITING_FOR_APPROVAL", wf.path("status").asText(), wf.toString());
    }

    @Test
    void stopCancelsAndValidationIsStrict() throws Exception {
        String token = investor.token();
        JsonNode err = api.call("POST", "/api/v1/emails", token, "{\"sender\":\"not-an-email\",\"subject\":\"x\"}", 400);
        assertEquals("BAD_REQUEST", err.path("code").asText());
        api.call("POST", "/api/v1/emails", token, "{\"sender\":\"a@b.example\"}", 400);   // nothing to process
        long id = api.call("POST", "/api/v1/emails", token,
                "{\"sender\":\"founder@zeta.example\",\"subject\":\"Zeta pre-seed\",\"body\":\"Raising $500k\"}", 202)
                .path("workflowId").asLong();
        JsonNode wf = api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"STOP\"}", 202);
        assertEquals("STOPPED", wf.path("status").asText());
        api.call("POST", "/api/v1/workflows/" + id + "/actions", token, "{\"action\":\"COMPLETE_WORKFLOW\"}", 409);
        api.call("GET", "/api/v1/workflows/999999", token, null, 404);
        // Unsupported attachment types are rejected by content, not by the declared name.
        api.call("POST", "/api/v1/emails", token, "{\"sender\":\"x@y.example\",\"subject\":\"exe\",\"attachments\":"
                + "[{\"filename\":\"deck.pdf\",\"contentBase64\":\"TVqQAAMAAAAEAAAA//8AALgAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAA\"}]}", 415);
    }

    @Test
    void idempotencyKeyReplaysTheFirstResponse() throws Exception {
        String token = investor.token();
        String body = "{\"companyName\":\"Idem Co\"}";
        MvcResult first = api.perform("POST", "/api/v1/pitches", token, body, "create-idem-co-1");
        MvcResult second = api.perform("POST", "/api/v1/pitches", token, body, "create-idem-co-1");
        assertEquals(201, first.getResponse().getStatus());
        assertEquals(201, second.getResponse().getStatus());
        assertEquals("true", second.getResponse().getHeader("Idempotent-Replayed"));
        assertEquals(om.readTree(first.getResponse().getContentAsString()).path("id"),
                om.readTree(second.getResponse().getContentAsString()).path("id"));
        assertEquals(1, api.call("GET", "/api/v1/pitches?q=Idem", token, null, 200).path("totalItems").asLong());
        // Same key, different body: rejected as an idempotency-key reuse.
        // The API contract uses 422 IDEMPOTENCY_KEY_REUSED for this case.
        MvcResult reused = api.perform("POST", "/api/v1/pitches", token, "{\"companyName\":\"Other\"}", "create-idem-co-1");
        assertEquals(422, reused.getResponse().getStatus());
        assertEquals("IDEMPOTENCY_KEY_REUSED", om.readTree(reused.getResponse().getContentAsString()).path("code").asText());
    }

    @Test
    void pipelineSearchFilterAndStageMoves() throws Exception {
        String token = investor.token();
        long a = api.call("POST", "/api/v1/pitches", token, "{\"companyName\":\"Alpha Analytics\",\"sector\":\"Fintech\"}", 201).path("id").asLong();
        api.call("POST", "/api/v1/pitches", token, "{\"companyName\":\"Beta Bio\",\"sector\":\"Biotech\"}", 201);
        JsonNode moved = api.call("PATCH", "/api/v1/pitches/" + a, token, "{\"dealStage\":\"DILIGENCE\",\"version\":0}", 200);
        assertEquals("DILIGENCE", moved.path("dealStage").asText());
        // A stale version is a conflict, not a silent overwrite.
        api.call("PATCH", "/api/v1/pitches/" + a, token, "{\"dealStage\":\"PASSED\",\"version\":0}", 409);
        api.call("PATCH", "/api/v1/pitches/" + a, token, "{\"dealStage\":\"NOT_A_STAGE\"}", 400);

        assertEquals(1, api.call("GET", "/api/v1/pitches?dealStage=DILIGENCE", token, null, 200).path("totalItems").asLong());
        assertEquals(1, api.call("GET", "/api/v1/pitches?sector=bio", token, null, 200).path("totalItems").asLong());
        assertEquals(1, api.call("GET", "/api/v1/pitches?q=alpha", token, null, 200).path("totalItems").asLong());
        JsonNode sorted = api.call("GET", "/api/v1/pitches?sort=companyName,asc&size=1", token, null, 200);
        assertEquals("Alpha Analytics", sorted.path("items").get(0).path("companyName").asText());
        assertEquals(2, sorted.path("totalPages").asInt());
        api.call("GET", "/api/v1/pitches?sort=passwordHash,asc", token, null, 400);
        api.call("GET", "/api/v1/pitches?size=1000", token, null, 400);   // page size is bounded
        JsonNode stages = api.call("GET", "/api/v1/pitches/stages", token, null, 200);
        assertTrue(stages.toString().contains("\"DILIGENCE\""));

        api.call("DELETE", "/api/v1/pitches/" + a, token, null, 204);
        api.call("GET", "/api/v1/pitches/" + a, token, null, 404);
    }

    @Test
    void workspaceExportIsAZip() throws Exception {
        api.call("POST", "/api/v1/pitches", investor.token(), "{\"companyName\":\"Export Co\"}", 201);
        MvcResult r = api.perform("GET", "/api/v1/workspace/export", investor.token(), null, null);
        assertEquals(200, r.getResponse().getStatus());
        assertEquals("application/zip", r.getResponse().getContentType());
        byte[] zip = r.getResponse().getContentAsByteArray();
        assertTrue(zip.length > 4 && zip[0] == 'P' && zip[1] == 'K');
        try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            var entry = in.getNextEntry();
            assertEquals("workspace.json", entry.getName());
            JsonNode data = om.readTree(in.readAllBytes());
            assertEquals("Export Co", data.path("pitches").get(0).path("companyName").asText());
            assertFalse(data.toString().contains("passwordHash"));
        }
    }
}
