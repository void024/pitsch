package com.pitsch.backend.dashboard;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.activity.Activity;
import com.pitsch.backend.activity.ActivityRepository;
import com.pitsch.backend.approval.Approval;
import com.pitsch.backend.approval.ApprovalRepository;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.billing.EntitlementService;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.event.EventController;
import com.pitsch.backend.notification.NotificationRepository;
import com.pitsch.backend.pitch.DealStage;
import com.pitsch.backend.pitch.PitchRepository;
import com.pitsch.backend.task.TaskRepository;
import com.pitsch.backend.workflow.AgentExecutionRepository;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** One round-trip for the dashboard: real counts from the database, never estimated. */
@RestController
public class DashboardController {

    public record AgentUsage(String agent, long calls, long tokens, BigDecimal costUsd, long avgLatencyMs) { }

    public record ActivityItem(Long id, String type, String message, Instant createdAt) { }

    public record Dashboard(Map<String, Long> pitchesByStage, long totalPitches, long workflowsNeedingAction,
                            long workflowsRunning, long pendingApprovals, long openTasks, long myOpenTasks,
                            long unreadNotifications, List<EventController.EventView> upcomingMeetings,
                            List<ActivityItem> recentActivity, List<AgentUsage> aiUsageThisMonth,
                            BigDecimal aiCostThisMonthUsd, Map<String, EntitlementService.Quota> quotas, String plan) { }

    private final PitchRepository pitches;
    private final WorkflowRepository workflows;
    private final ApprovalRepository approvals;
    private final TaskRepository tasks;
    private final NotificationRepository notifications;
    private final CalendarEventRepository events;
    private final ActivityRepository activities;
    private final AgentExecutionRepository executions;
    private final EntitlementService entitlements;
    private final Clock clock;

    public DashboardController(PitchRepository pitches, WorkflowRepository workflows, ApprovalRepository approvals,
                               TaskRepository tasks, NotificationRepository notifications, CalendarEventRepository events,
                               ActivityRepository activities, AgentExecutionRepository executions,
                               EntitlementService entitlements, Clock clock) {
        this.pitches = pitches;
        this.workflows = workflows;
        this.approvals = approvals;
        this.tasks = tasks;
        this.notifications = notifications;
        this.events = events;
        this.activities = activities;
        this.executions = executions;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    @GetMapping("/api/v1/dashboard")
    @RequiresPermission(Permission.PITCH_READ)
    public Dashboard dashboard(AuthPrincipal principal) {
        Long org = principal.orgId();
        Map<String, Long> byStage = new LinkedHashMap<>();
        for (DealStage s : DealStage.values()) {
            byStage.put(s.name(), 0L);
        }
        long total = 0;
        for (Object[] row : pitches.countByDealStage(org)) {
            long c = ((Number) row[1]).longValue();
            byStage.put(String.valueOf(row[0]), c);
            total += c;
        }
        Instant now = clock.instant();
        List<EventController.EventView> upcoming = events
                .findByOrganizationIdAndEndTimeAfterAndStartTimeBeforeOrderByStartTimeAsc(org, now, now.plus(Duration.ofDays(7)))
                .stream().limit(10).map(EventController::view).toList();
        List<ActivityItem> recent = activities.findTop50ByOrganizationIdOrderByCreatedAtDesc(org).stream().limit(15)
                .map((Activity a) -> new ActivityItem(a.getId(), a.getType(), a.getMessage(), a.getCreatedAt())).toList();

        Instant monthStart = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        List<AgentUsage> usage = new ArrayList<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (Object[] row : executions.statsSince(org, monthStart)) {
            BigDecimal c = row[3] == null ? BigDecimal.ZERO : new BigDecimal(row[3].toString());
            usage.add(new AgentUsage(String.valueOf(row[0]), ((Number) row[1]).longValue(),
                    row[2] == null ? 0 : ((Number) row[2]).longValue(), c,
                    row[4] == null ? 0 : Math.round(((Number) row[4]).doubleValue())));
            cost = cost.add(c);
        }
        Map<String, EntitlementService.Quota> quotas = new LinkedHashMap<>();
        entitlements.quotas(org).forEach((k, v) -> quotas.put(k.name(), v));

        return new Dashboard(byStage, total,
                workflows.countByOrganizationIdAndStatusIn(org, WorkflowStatus.NEEDS_USER),
                workflows.countByOrganizationIdAndStatusIn(org, WorkflowStatus.ACTIVE),
                approvals.countByOrganizationIdAndStatus(org, Approval.Status.PENDING.name()),
                tasks.countByOrganizationIdAndStatusNot(org, "DONE"),
                tasks.countByOrganizationIdAndAssigneeUserIdAndStatusNot(org, principal.userId(), "DONE"),
                notifications.countByUserIdAndOrganizationIdAndReadFalse(principal.userId(), org),
                upcoming, recent, usage, cost, quotas, entitlements.planCode(org));
    }
}
