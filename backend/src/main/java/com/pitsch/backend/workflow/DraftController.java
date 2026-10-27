package com.pitsch.backend.workflow;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Email draft review: Edit / Send (= approve) / Cancel. Sending is executed by a job after the approval is recorded. */
@RestController
public class DraftController {

    public record DraftEdit(@Size(max = 1000) String subject, @Size(max = 50000) String body) { }

    private final WorkflowEngine engine;
    private final WorkflowViews views;

    public DraftController(WorkflowEngine engine, WorkflowViews views) {
        this.engine = engine;
        this.views = views;
    }

    @PutMapping({"/api/drafts/{id}", "/api/v1/drafts/{id}"})
    @RequiresPermission(Permission.WORKFLOW_RUN)
    public WorkflowViews.DraftView edit(AuthPrincipal principal, @PathVariable Long id, @RequestBody DraftEdit edit) {
        return views.draftView(engine.updateDraft(principal, id, edit.subject(), edit.body()));
    }

    @PostMapping({"/api/drafts/{id}/send", "/api/v1/drafts/{id}/send"})
    @RequiresPermission(Permission.ACTION_APPROVE)
    public WorkflowViews.WorkflowView send(AuthPrincipal principal, @PathVariable Long id,
                                           @RequestBody(required = false) DraftEdit edit) {
        String subject = edit == null ? null : edit.subject();
        String body = edit == null ? null : edit.body();
        return views.detail(engine.sendDraft(principal, id, subject, body));
    }

    @PostMapping({"/api/drafts/{id}/cancel", "/api/v1/drafts/{id}/cancel"})
    @RequiresPermission(Permission.WORKFLOW_RUN)
    public WorkflowViews.WorkflowView cancel(AuthPrincipal principal, @PathVariable Long id) {
        return views.detail(engine.cancelDraft(principal, id));
    }
}
