package com.pitsch.backend.privacy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditEventRepository;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.jobs.Job;
import com.pitsch.backend.jobs.JobHandler;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobRepository;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.org.Organization;
import com.pitsch.backend.org.OrganizationRepository;
import com.pitsch.backend.workflow.AgentExecutionRepository;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowSteps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodic housekeeping, each run de-duplicated through the job queue so that exactly one instance performs it:
 * <ul>
 *   <li>RETENTION_PURGE (daily): drops raw agent inputs/outputs after AGENT_IO_RETENTION_DAYS, applies each workspace's
 *   data-retention setting to email bodies and intermediate agent outputs, and expires audit events after
 *   AUDIT_RETENTION_DAYS.</li>
 *   <li>WORKFLOW_RECOVERY (every 10 minutes): a workflow that has been "working" for over 30 minutes without a queued
 *   or running job is marked FAILED with a timeout message, so the user sees it and can retry — it is never left
 *   spinning and AI work is never silently re-run.</li>
 * </ul>
 */
@Component
public class MaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceService.class);
    static final Duration WORKFLOW_TIMEOUT = Duration.ofMinutes(30);

    private final JobQueue jobs;
    private final PitschProperties props;
    private final Clock clock;

    public MaintenanceService(JobQueue jobs, PitschProperties props, Clock clock) {
        this.jobs = jobs;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "0 23 3 * * *")
    public void scheduleRetention() {
        if (!props.getJobs().isEnabled()) {
            return;
        }
        String day = clock.instant().toString().substring(0, 10);
        jobs.enqueue(JobType.RETENTION_PURGE, null, null, Map.of(), "retention:" + day, Duration.ZERO);
    }

    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void scheduleRecovery() {
        if (!props.getJobs().isEnabled()) {
            return;
        }
        long slot = clock.millis() / 600_000L;
        jobs.enqueue(JobType.WORKFLOW_RECOVERY, null, null, Map.of(), "workflow-recovery:" + slot, Duration.ZERO);
    }

    @Component
    public static class RetentionHandler implements JobHandler {

        private final AgentExecutionRepository executions;
        private final EmailRepository emails;
        private final WorkflowRepository workflows;
        private final OrganizationRepository organizations;
        private final AuditEventRepository auditEvents;
        private final AuditService audit;
        private final PitschProperties props;
        private final Clock clock;

        public RetentionHandler(AgentExecutionRepository executions, EmailRepository emails, WorkflowRepository workflows,
                                OrganizationRepository organizations, AuditEventRepository auditEvents, AuditService audit,
                                PitschProperties props, Clock clock) {
            this.executions = executions;
            this.emails = emails;
            this.workflows = workflows;
            this.organizations = organizations;
            this.auditEvents = auditEvents;
            this.audit = audit;
            this.props = props;
            this.clock = clock;
        }

        @Override
        public JobType type() {
            return JobType.RETENTION_PURGE;
        }

        @Override
        @Transactional
        public void handle(Job job, JsonNode payload) {
            Instant now = clock.instant();
            int agentIo = executions.purgePayloadsBefore(now.minus(Duration.ofDays(props.getRetention().getAgentIoDays())));
            int emailBodies = 0;
            int workflowOutputs = 0;
            for (Organization org : organizations.findAll()) {
                Integer days = org.getDataRetentionDays();
                if (days == null || days <= 0 || org.getDeletionRequestedAt() != null) {
                    continue;
                }
                Instant cutoff = now.minus(Duration.ofDays(days));
                emailBodies += emails.redactBodiesBefore(org.getId(), cutoff);
                workflowOutputs += workflows.purgeIntermediateOutputsBefore(org.getId(), cutoff);
            }
            int auditRows = auditEvents.deleteOlderThan(now.minus(Duration.ofDays(props.getRetention().getAuditDays())));
            audit.record(null, null, AuditAction.RETENTION_PURGE, "SYSTEM", null, Map.of("agentPayloads", agentIo,
                    "emailBodies", emailBodies, "workflowOutputs", workflowOutputs, "auditEvents", auditRows));
            log.info("Retention purge: {} agent payloads, {} email bodies, {} workflow outputs, {} audit events",
                    agentIo, emailBodies, workflowOutputs, auditRows);
        }
    }

    @Component
    public static class RecoveryHandler implements JobHandler {

        private final WorkflowRepository workflows;
        private final JobRepository jobRepo;
        private final WorkflowSteps steps;
        private final Clock clock;

        public RecoveryHandler(WorkflowRepository workflows, JobRepository jobRepo, WorkflowSteps steps, Clock clock) {
            this.workflows = workflows;
            this.jobRepo = jobRepo;
            this.steps = steps;
            this.clock = clock;
        }

        @Override
        public JobType type() {
            return JobType.WORKFLOW_RECOVERY;
        }

        @Override
        public void handle(Job job, JsonNode payload) {
            List<Workflow> stuck = workflows.findStuck(clock.instant().minus(WORKFLOW_TIMEOUT));
            for (Workflow wf : stuck) {
                boolean live = !jobRepo.findByWorkflowIdAndStatusIn(wf.getId(),
                        List.of(Job.Status.QUEUED.name(), Job.Status.RUNNING.name())).isEmpty();
                if (live) {
                    continue;
                }
                log.warn("Workflow {} timed out in {} without a live job; marking it failed", wf.getId(), wf.getStatus());
                steps.fail(wf.getId(), wf.getCurrentStep() == null ? "PROCESSING" : wf.getCurrentStep(),
                        "Processing timed out. Retry the workflow to continue.");
            }
        }
    }
}
