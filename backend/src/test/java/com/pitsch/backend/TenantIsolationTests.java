package com.pitsch.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pitsch.backend.org.Membership;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.org.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tenant isolation and server-side RBAC: a user can never read or change another workspace's data, client-supplied
 * organization IDs are ignored, and each role only reaches the actions it is allowed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(TestAiConfig.class)
class TenantIsolationTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired MembershipRepository memberships;
    TestSupport api;

    @BeforeEach
    void setUp() {
        api = new TestSupport(mvc, om);
    }

    @Test
    void workspaceDataIsInvisibleAcrossTenants() throws Exception {
        TestSupport.Account a = api.signup("Ada");
        TestSupport.Account b = api.signup("Ben");

        long pitchId = api.call("POST", "/api/v1/pitches", a.token(),
                "{\"companyName\":\"Secret Robotics\",\"founderEmail\":\"f@secret.example\"}", 201).path("id").asLong();
        long taskId = api.call("POST", "/api/v1/tasks", a.token(), "{\"title\":\"Call founder\"}", 201).path("id").asLong();
        long eventId = api.call("POST", "/api/v1/events", a.token(),
                "{\"title\":\"Partner sync\",\"startTime\":\"2030-01-01T10:00:00Z\",\"endTime\":\"2030-01-01T11:00:00Z\"}", 201)
                .path("id").asLong();

        // B cannot see or touch any of it — 404, not 403, so IDs are not even confirmed to exist.
        api.call("GET", "/api/v1/pitches/" + pitchId, b.token(), null, 404);
        api.call("PATCH", "/api/v1/pitches/" + pitchId, b.token(), "{\"dealStage\":\"PASSED\"}", 404);
        api.call("DELETE", "/api/v1/pitches/" + pitchId, b.token(), null, 404);
        api.call("GET", "/api/v1/pitches/" + pitchId + "/timeline", b.token(), null, 404);
        api.call("PUT", "/api/v1/tasks/" + taskId, b.token(), "{\"title\":\"hijack\"}", 404);
        api.call("DELETE", "/api/v1/tasks/" + taskId, b.token(), null, 404);
        api.call("PUT", "/api/v1/events/" + eventId, b.token(), "{\"title\":\"hijack\"}", 404);
        api.call("DELETE", "/api/v1/events/" + eventId, b.token(), null, 404);
        assertEquals(0, api.call("GET", "/api/v1/pitches", b.token(), null, 200).path("totalItems").asLong());
        assertEquals(0, api.call("GET", "/api/v1/tasks", b.token(), null, 200).size());
        assertEquals(0, api.call("GET", "/api/v1/dashboard", b.token(), null, 200).path("totalPitches").asLong());

        // A still has everything.
        assertEquals("Secret Robotics", api.call("GET", "/api/v1/pitches/" + pitchId, a.token(), null, 200)
                .path("pitch").path("companyName").asText());
        assertEquals(1, api.call("GET", "/api/v1/pitches", a.token(), null, 200).path("totalItems").asLong());
    }

    @Test
    void clientSuppliedOrganizationIdsAreIgnored() throws Exception {
        TestSupport.Account a = api.signup("Cara");
        TestSupport.Account b = api.signup("Dan");
        // B tries to create a pitch "in" A's workspace: the field is ignored, the pitch lands in B's workspace.
        long id = api.call("POST", "/api/v1/pitches", b.token(),
                "{\"companyName\":\"Planted\",\"organizationId\":" + a.organizationId() + "}", 201).path("id").asLong();
        api.call("GET", "/api/v1/pitches/" + id, a.token(), null, 404);
        api.call("GET", "/api/v1/pitches/" + id, b.token(), null, 200);
        // Switching to a workspace you are not a member of is refused.
        api.call("POST", "/api/v1/auth/switch-workspace", b.token(), "{\"organizationId\":" + a.organizationId() + "}", 403);
    }

    @Test
    void emailsAndWorkflowsAreTenantScoped() throws Exception {
        TestSupport.Account a = api.signup("Eve");
        TestSupport.Account b = api.signup("Finn");
        JsonNode created = api.call("POST", "/api/v1/emails", a.token(),
                "{\"sender\":\"founder@acme.example\",\"subject\":\"Acme seed round\",\"body\":\"We are raising.\"}", 202);
        long emailId = created.path("emailId").asLong();
        long workflowId = created.path("workflowId").asLong();
        api.call("GET", "/api/v1/emails/" + emailId, b.token(), null, 404);
        api.call("GET", "/api/v1/workflows/" + workflowId, b.token(), null, 404);
        api.call("POST", "/api/v1/workflows/" + workflowId + "/actions", b.token(), "{\"action\":\"STOP\"}", 404);
        api.call("GET", "/api/v1/workflows/" + workflowId + "/executions", b.token(), null, 404);
        assertEquals(0, api.call("GET", "/api/v1/workflows", b.token(), null, 200).path("totalItems").asLong());
        assertEquals(0, api.call("GET", "/api/v1/emails", b.token(), null, 200).path("totalItems").asLong());
        api.call("GET", "/api/v1/workflows/" + workflowId, a.token(), null, 200);

        // Erasure is tenant-scoped too; the owner's delete removes the email and its workflow.
        api.call("DELETE", "/api/v1/emails/" + emailId, b.token(), null, 404);
        api.call("DELETE", "/api/v1/emails/" + emailId, a.token(), null, 204);
        api.call("GET", "/api/v1/emails/" + emailId, a.token(), null, 404);
        api.call("GET", "/api/v1/workflows/" + workflowId, a.token(), null, 404);
    }

    @Test
    void rolesAreEnforcedOnTheServer() throws Exception {
        TestSupport.Account owner = api.signup("Olga");
        TestSupport.Account member = api.signup("Mia");
        TestSupport.Account analyst = api.signup("Ari");
        memberships.save(new Membership(owner.organizationId(), member.userId(), Role.MEMBER));
        memberships.save(new Membership(owner.organizationId(), analyst.userId(), Role.ANALYST));
        api.call("POST", "/api/v1/auth/switch-workspace", member.token(), "{\"organizationId\":" + owner.organizationId() + "}", 200);
        api.call("POST", "/api/v1/auth/switch-workspace", analyst.token(), "{\"organizationId\":" + owner.organizationId() + "}", 200);

        long pitchId = api.call("POST", "/api/v1/pitches", owner.token(), "{\"companyName\":\"Shared Co\"}", 201).path("id").asLong();

        // MEMBER: read-only on pitches.
        api.call("GET", "/api/v1/pitches/" + pitchId, member.token(), null, 200);
        JsonNode denied = api.call("PATCH", "/api/v1/pitches/" + pitchId, member.token(), "{\"dealStage\":\"SCREENING\"}", 403);
        assertEquals("FORBIDDEN", denied.path("code").asText());
        api.call("DELETE", "/api/v1/pitches/" + pitchId, member.token(), null, 403);
        api.call("POST", "/api/v1/emails", member.token(), "{\"sender\":\"x@y.example\",\"subject\":\"s\"}", 403);
        api.call("GET", "/api/v1/audit-events", member.token(), null, 403);
        api.call("GET", "/api/v1/workspace/export", member.token(), null, 403);
        api.call("POST", "/api/v1/workspace/invitations", member.token(), "{\"email\":\"z@example.com\",\"role\":\"MEMBER\"}", 403);
        api.call("POST", "/api/v1/workspace/delete", member.token(), "{\"confirmName\":\"Olga Capital\",\"password\":\"x\"}", 403);

        // ANALYST: may edit pitches and run workflows, may not delete, approve, or manage the workspace.
        api.call("PATCH", "/api/v1/pitches/" + pitchId, analyst.token(), "{\"dealStage\":\"SCREENING\"}", 200);
        api.call("DELETE", "/api/v1/pitches/" + pitchId, analyst.token(), null, 403);
        api.call("POST", "/api/v1/billing/checkout", analyst.token(), "{\"planCode\":\"PRO\"}", 403);
        api.call("GET", "/api/v1/audit-events", analyst.token(), null, 403);

        // OWNER can read the audit trail, which recorded the analyst's stage change.
        JsonNode audit = api.call("GET", "/api/v1/audit-events?action=PITCH_UPDATED", owner.token(), null, 200);
        assertTrue(audit.path("totalItems").asLong() >= 1);
        assertEquals(analyst.userId(), audit.path("items").get(0).path("actorUserId").asLong());

        // The role comes from the membership on every request: demoting takes effect immediately.
        Membership m = memberships.findByOrganizationIdAndUserId(owner.organizationId(), analyst.userId()).orElseThrow();
        m.setRole(Role.MEMBER);
        memberships.save(m);
        api.call("PATCH", "/api/v1/pitches/" + pitchId, analyst.token(), "{\"dealStage\":\"DILIGENCE\"}", 403);
    }

    @Test
    void lastOwnerCannotBeDemotedOrRemoved() throws Exception {
        TestSupport.Account owner = api.signup("Solo");
        JsonNode members = api.call("GET", "/api/v1/workspace/members", owner.token(), null, 200);
        long membershipId = members.get(0).path("membershipId").asLong();
        api.call("PATCH", "/api/v1/workspace/members/" + membershipId, owner.token(), "{\"role\":\"ADMIN\"}", 409);
        api.call("DELETE", "/api/v1/workspace/members/" + membershipId, owner.token(), null, 409);
        assertFalse(api.call("GET", "/api/v1/workspace/members", owner.token(), null, 200).isEmpty());
    }
}
