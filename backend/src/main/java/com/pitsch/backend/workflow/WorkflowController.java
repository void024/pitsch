package com.pitsch.backend.workflow;

import java.util.List;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
public class WorkflowController {

    private final WorkflowRepository workflows;
    private final WorkflowEngine engine;
    private final WorkflowViews views;
    private final EmailDraftRepository drafts;
    private final AgentExecutionRepository executions;

    public WorkflowController(WorkflowRepository workflows, WorkflowEngine engine, WorkflowViews views,
                              EmailDraftRepository drafts, AgentExecutionRepository executions) {
        this.workflows = workflows;
        this.engine = engine;
        this.views = views;
        this.drafts = drafts;
        this.executions = executions;
    }

    @GetMapping
    public List<WorkflowViews.WorkflowView> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return workflows.findByUserIdOrderByCreatedAtDesc(userId).stream().map(views::summary).toList();
    }

    @GetMapping("/{id}")
    public WorkflowViews.WorkflowView get(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        return views.detail(find(userId, id));
    }

    /** Body: {"action": "COMPLETE_WORKFLOW" | "STOP" | "PLAN_MEETING" | "PLAN_EMAIL_RESPONSE" | "RETRY", ...} */
    @PostMapping("/{id}/action")
    public ResponseEntity<WorkflowViews.WorkflowView> action(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                             @PathVariable Long id, @RequestBody ActionRequest req) {
        Workflow wf = engine.applyAction(userId, id, req);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(views.detail(wf));
    }

    @GetMapping("/{id}/draft")
    public WorkflowViews.DraftView draft(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        find(userId, id);
        return drafts.findFirstByWorkflowIdOrderByIdDesc(id).map(views::draftView)
                .orElseThrow(() -> ApiException.notFound("Draft"));
    }

    /** Full audit trail of agent calls for this workflow (inputs redacted). */
    @GetMapping("/{id}/executions")
    public List<AgentExecution> executions(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        find(userId, id);
        return executions.findByWorkflowIdOrderByIdAsc(id);
    }

    /** The research brief as a Markdown file (for download / sharing). */
    @GetMapping(value = "/{id}/brief.md", produces = "text/markdown")
    public ResponseEntity<String> briefMarkdown(@RequestAttribute(AuthInterceptor.USER_ID) Long userId,
                                                @PathVariable Long id) {
        Workflow wf = find(userId, id);
        if (wf.getBriefMarkdown() == null) {
            throw ApiException.notFound("Brief");
        }
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown; charset=UTF-8"))
                .body(wf.getBriefMarkdown());
    }

    private Workflow find(Long userId, Long id) {
        return workflows.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Workflow"));
    }
}
