package com.pitsch.backend.workflow;

import com.pitsch.backend.auth.AuthInterceptor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Email draft review: Edit / Send / Cancel. Sending goes through the Action Agent with explicit approval. */
@RestController
@RequestMapping("/api/drafts")
public class DraftController {

    public record DraftEdit(String subject, String body) { }

    private final WorkflowEngine engine;
    private final WorkflowViews views;

    public DraftController(WorkflowEngine engine, WorkflowViews views) {
        this.engine = engine;
        this.views = views;
    }

    @PutMapping("/{id}")
    public WorkflowViews.DraftView edit(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id,
                                        @RequestBody DraftEdit edit) {
        return views.draftView(engine.updateDraft(userId, id, edit.subject(), edit.body()));
    }

    @PostMapping("/{id}/send")
    public WorkflowViews.WorkflowView send(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id,
                                           @RequestBody(required = false) DraftEdit edit) {
        String subject = edit == null ? null : edit.subject();
        String body = edit == null ? null : edit.body();
        return views.detail(engine.sendDraft(userId, id, subject, body));
    }

    @PostMapping("/{id}/cancel")
    public WorkflowViews.WorkflowView cancel(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        return views.detail(engine.cancelDraft(userId, id));
    }
}
