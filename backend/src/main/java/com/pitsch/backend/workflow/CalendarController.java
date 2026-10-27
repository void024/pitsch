package com.pitsch.backend.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CalendarController {

    public record MeetingRequest(Long workflowId, String start, String end, @Size(max = 200) String title) { }

    private final WorkflowRepository workflows;
    private final WorkflowEngine engine;
    private final WorkflowViews views;
    private final Json json;

    public CalendarController(WorkflowRepository workflows, WorkflowEngine engine, WorkflowViews views, Json json) {
        this.workflows = workflows;
        this.engine = engine;
        this.views = views;
        this.json = json;
    }

    /** Slots suggested by the Calendar Agent for this workflow (run PLAN_MEETING first). */
    @GetMapping({"/api/calendar/availability", "/api/v1/calendar/availability"})
    @RequiresPermission(Permission.PITCH_READ)
    public Map<String, Object> availability(AuthPrincipal principal, @RequestParam Long workflowId) {
        Workflow wf = workflows.findByIdAndOrganizationId(workflowId, principal.orgId())
                .orElseThrow(() -> ApiException.notFound("Workflow"));
        JsonNode calendar = json.read(wf.getCalendarJson());
        if (calendar == null) {
            throw ApiException.notFound("Meeting slots (use the PLAN_MEETING action first)");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("workflowId", workflowId);
        out.put("timezone", Json.text(calendar, "timezone"));
        out.put("slots", calendar.get("slots"));
        out.put("warnings", calendar.get("warnings"));
        out.put("reviewReasons", calendar.get("reviewReasons"));
        return out;
    }

    /** The investor picks a slot: this request is the approval; the event is created by a job. */
    @PostMapping({"/api/calendar/meeting", "/api/v1/calendar/meetings"})
    @RequiresPermission(Permission.ACTION_APPROVE)
    public WorkflowViews.WorkflowView meeting(AuthPrincipal principal, @RequestBody MeetingRequest req) {
        if (req.workflowId() == null) {
            throw ApiException.badRequest("workflowId is required");
        }
        return views.detail(engine.approveMeeting(principal, req.workflowId(), req.start(), req.end(), req.title()));
    }

    @PostMapping("/api/v1/workflows/{workflowId}/meeting/cancel")
    @RequiresPermission(Permission.ACTION_APPROVE)
    public WorkflowViews.WorkflowView cancel(AuthPrincipal principal, @PathVariable Long workflowId) {
        return views.detail(engine.cancelMeeting(principal, workflowId));
    }
}
