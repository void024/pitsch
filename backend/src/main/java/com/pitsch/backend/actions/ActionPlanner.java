package com.pitsch.backend.actions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.ai.Agent;
import com.pitsch.backend.ai.AgentResult;
import com.pitsch.backend.approval.ApprovalService;
import com.pitsch.backend.approval.ApprovalType;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.integration.sheets.PipelineSyncService;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.workflow.AgentInputs;
import com.pitsch.backend.workflow.AgentRunner;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns a workflow event into actions. The Action Agent proposes structured intent (label names, row values); the
 * backend decides what may happen:
 * <ul>
 *   <li>Pitsch-internal state (email labels shown in Pitsch) is always updated.</li>
 *   <li>Gmail labels and pipeline-sheet rows follow the workspace policy: AUTO (execute, audited), APPROVAL
 *       (create a pending approval) or OFF.</li>
 *   <li>Sending email and creating meetings are never taken from this path — they only run from an explicit user
 *       approval (see WorkflowEngine).</li>
 * </ul>
 */
@Service
public class ActionPlanner {

    private static final Logger log = LoggerFactory.getLogger(ActionPlanner.class);

    private final WorkflowStore store;
    private final EmailRepository emails;
    private final PitchRepository pitches;
    private final OrganizationRepository orgs;
    private final AgentRunner agents;
    private final AgentInputs inputs;
    private final ApprovalService approvals;
    private final ActionService actions;
    private final PipelineSyncService sheets;
    private final JobQueue jobs;
    private final PitschProperties props;
    private final Json json;

    public ActionPlanner(WorkflowStore store, EmailRepository emails, PitchRepository pitches, OrganizationRepository orgs,
                         AgentRunner agents, AgentInputs inputs, ApprovalService approvals, ActionService actions,
                         PipelineSyncService sheets, JobQueue jobs, PitschProperties props, Json json) {
        this.store = store;
        this.emails = emails;
        this.pitches = pitches;
        this.orgs = orgs;
        this.agents = agents;
        this.inputs = inputs;
        this.approvals = approvals;
        this.actions = actions;
        this.sheets = sheets;
        this.jobs = jobs;
        this.props = props;
        this.json = json;
    }

    public void handleEvent(Long workflowId, String event) {
        Workflow wf = store.load(workflowId);
        Organization org = orgs.findById(wf.getOrganizationId()).orElseThrow();
        Email email = wf.getEmailId() == null ? null : emails.findById(wf.getEmailId()).orElse(null);
        Pitch pitch = wf.getPitchId() == null ? null : pitches.findById(wf.getPitchId()).orElse(null);
        String briefUrl = props.getFrontendUrl() + "/workflows/" + wf.getId();

        AgentResult r = agents.run(wf.getOrganizationId(), workflowId, Agent.ACTION_AGENT,
                inputs.action(event, wf, email, pitch, briefUrl, json.read(wf.getAnalysisJson())));
        if (r.success()) {
            List<String> add = new ArrayList<>();
            List<String> remove = new ArrayList<>();
            for (JsonNode a : r.data().path("actions")) {
                String type = Json.text(a, "type");
                if ("GMAIL_ADD_LABELS".equals(type)) {
                    add.addAll(Json.strings(a.path("payload"), "labels"));
                } else if ("GMAIL_REMOVE_LABELS".equals(type)) {
                    remove.addAll(Json.strings(a.path("payload"), "labels"));
                } else if ("GMAIL_SEND_EMAIL".equals(type) || "CALENDAR_CREATE_EVENT".equals(type)) {
                    // Never executed from an event: these exist only behind an explicit user approval.
                    log.debug("Ignoring {} proposed for event {} on workflow {}", type, event, workflowId);
                }
            }
            if (email != null && (!add.isEmpty() || !remove.isEmpty())) {
                applyLabels(org, wf, email, event, add, remove);
            }
        } else {
            log.warn("Action Agent failed for {} on workflow {}: {}", event, workflowId, r.errorMessage());
        }

        if (pitch != null && sheets.configured(org.getId()) && !"NOT_PITCH".equals(event)) {
            String key = "sheet:" + pitch.getId() + ":" + workflowId + ":" + event;
            switch (org.sheetsSync()) {
                case AUTO -> jobs.enqueue(JobType.PIPELINE_SHEET_SYNC, org.getId(), workflowId,
                        Map.of("pitchId", pitch.getId(), "key", key), "job:" + key, java.time.Duration.ZERO);
                case APPROVAL -> {
                    ObjectNode payload = json.obj();
                    payload.put("pitchId", pitch.getId());
                    payload.put("event", event);
                    approvals.propose(org.getId(), workflowId, pitch.getId(), ApprovalType.SYNC_PIPELINE,
                            "Update pipeline sheet: " + (pitch.getCompanyName() == null ? "pitch " + pitch.getId()
                                    : pitch.getCompanyName()) + " → " + pitch.getStatus(), payload,
                            "Workflow event " + event, key);
                }
                case OFF -> { }
            }
        }
    }

    private void applyLabels(Organization org, Workflow wf, Email email, String event, List<String> add, List<String> remove) {
        Set<String> labels = new LinkedHashSet<>(Json.splitCsv(email.getLabels()));
        labels.addAll(add);
        remove.forEach(labels::remove);
        email.setLabels(Json.truncate(Json.joinCsv(new ArrayList<>(labels)), 1000));
        emails.save(email);

        boolean inGmail = Email.Source.GMAIL.name().equals(email.getSource()) && email.getGmailId() != null
                && email.getConnectionId() != null;
        if (!inGmail) {
            return;   // manual imports have no Gmail message to label
        }
        String key = "labels:" + wf.getId() + ":" + event;
        switch (org.gmailLabels()) {
            case AUTO -> actions.applyGmailLabels(org.getId(), wf.getId(), email, add, remove, key);
            case APPROVAL -> {
                ObjectNode payload = json.obj();
                payload.put("emailId", email.getId());
                payload.set("add", json.toNode(add));
                payload.set("remove", json.toNode(remove));
                approvals.propose(org.getId(), wf.getId(), wf.getPitchId(), ApprovalType.APPLY_LABELS,
                        "Apply Gmail labels " + add + " to \"" + Json.truncate(email.getSubject(), 80) + "\"", payload,
                        "Workflow event " + event, key);
            }
            case OFF -> { }
        }
    }
}
