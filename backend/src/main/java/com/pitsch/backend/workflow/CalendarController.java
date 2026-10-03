package com.pitsch.backend.workflow;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    public record MeetingRequest(Long workflowId, String start, String end, String title) { }

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
    @GetMapping("/availability")
    public Map<String, Object> availability(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                            @RequestParam Long workflowId) {
        Workflow wf = workflows.findByIdAndUserId(workflowId, userId).orElseThrow(() -> ApiException.notFound("Workflow"));
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

    /** Creates the meeting after the investor picked a slot (the explicit approval step). */
    @PostMapping("/meeting")
    public WorkflowViews.WorkflowView meeting(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                              @RequestBody MeetingRequest req) {
        if (req.workflowId() == null) {
            throw ApiException.badRequest("workflowId is required");
        }
        return views.detail(engine.approveMeeting(userId, req.workflowId(), req.start(), req.end(), req.title()));
    }
}
