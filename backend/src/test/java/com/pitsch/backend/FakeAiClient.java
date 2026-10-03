package com.pitsch.backend;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.ai.AiClient;

/**
 * Replays the AI service's recorded responses (ai-service/docs/examples, copied to test resources) and records
 * every input so tests can check the backend sends what each agent's schema requires.
 */
public class FakeAiClient implements AiClient {

    public record Call(Agent agent, String executionId, JsonNode input) { }

    private static final Map<Agent, String> EXAMPLES = Map.of(
            Agent.EMAIL_CLASSIFIER, "01-email-classifier.json",
            Agent.DOCUMENT_AGENT, "03-document.json",
            Agent.RESEARCH_AGENT, "04-research.json",
            Agent.VERIFICATION_AGENT, "05-verification.json",
            Agent.ANALYSIS_AGENT, "06-analysis.json",
            Agent.CALENDAR_AGENT, "08-calendar.json",
            Agent.EMAIL_RESPONSE_AGENT, "09-email-response.json");

    private final ObjectMapper om;
    public final List<Call> calls = new ArrayList<>();

    public FakeAiClient(ObjectMapper om) {
        this.om = om;
    }

    @Override
    public synchronized AgentResult call(Agent agent, String executionId, String traceId, JsonNode input) {
        calls.add(new Call(agent, executionId, input));
        JsonNode data = agent == Agent.ACTION_AGENT ? actions(input) : example(agent).path("response").path("data");
        return new AgentResult(true, agent.name(), data, null, null, false, om.createObjectNode());
    }

    @Override
    public boolean isHealthy() {
        return true;
    }

    public List<Call> callsTo(Agent agent) {
        return calls.stream().filter(c -> c.agent() == agent).toList();
    }

    private JsonNode example(Agent agent) {
        try (InputStream in = getClass().getResourceAsStream("/agent-examples/" + EXAMPLES.get(agent))) {
            return om.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal stand-in for the deterministic Action Agent (same output shape and approval rules). */
    private JsonNode actions(JsonNode input) {
        String event = input.path("event").asText();
        boolean approved = input.has("approval");
        ObjectNode out = om.createObjectNode();
        out.put("workflowId", input.path("workflowId").asText());
        out.put("event", event);
        ArrayNode actions = out.putArray("actions");
        ArrayNode blocked = out.putArray("blockedActionIds");
        if ("MEETING_APPROVED".equals(event)) {
            ObjectNode a = action(actions, event + "-cal", "CALENDAR_CREATE_EVENT", true, approved, blocked);
            ObjectNode ev = a.putObject("payload").putObject("event");
            ev.put("summary", "Pitch meeting: " + input.path("pitch").path("companyName").asText());
            ev.putObject("start").put("dateTime", input.path("meeting").path("start").asText());
            ev.putObject("end").put("dateTime", input.path("meeting").path("end").asText());
        } else if ("EMAIL_APPROVED".equals(event)) {
            ObjectNode a = action(actions, event + "-send", "GMAIL_SEND_EMAIL", true, approved, blocked);
            a.putObject("payload").put("to", input.path("email").path("recipient").asText())
                    .put("subject", input.path("email").path("subject").asText());
        } else if (!"NOT_PITCH".equals(event)) {
            action(actions, event + "-label", "GMAIL_ADD_LABELS", false, true, blocked)
                    .putObject("payload").putArray("labels").add("Pitsch/" + event);
        }
        out.putArray("warnings");
        return out;
    }

    private static ObjectNode action(ArrayNode actions, String id, String type, boolean needsApproval, boolean approved,
                                     ArrayNode blocked) {
        ObjectNode a = actions.addObject();
        a.put("actionId", id);
        a.put("type", type);
        a.put("target", "test");
        a.put("requiresApproval", needsApproval);
        a.put("approved", approved);
        a.putArray("dependsOn");
        a.put("reason", "test");
        if (needsApproval && !approved) {
            blocked.add(id);
        }
        return a;
    }
}
