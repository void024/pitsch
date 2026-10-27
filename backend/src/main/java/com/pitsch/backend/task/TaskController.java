package com.pitsch.backend.task;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.pitch.PitchRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Diligence follow-up tasks, optionally linked to a pitch and assigned to a member. */
@RestController
public class TaskController {

    public record TaskInput(@Size(max = 255) String title, @Size(max = 10000) String description, String status,
                            String priority, String dueDate, Long pitchId, Long assigneeUserId) { }

    public record StatusInput(String status) { }

    private static final Set<String> STATUSES = Set.of("TODO", "IN_PROGRESS", "DONE");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private final TaskRepository repo;
    private final PitchRepository pitches;
    private final MembershipRepository memberships;
    private final ActivityService activity;

    public TaskController(TaskRepository repo, PitchRepository pitches, MembershipRepository memberships,
                          ActivityService activity) {
        this.repo = repo;
        this.pitches = pitches;
        this.memberships = memberships;
        this.activity = activity;
    }

    @GetMapping({"/api/tasks", "/api/v1/tasks"})
    @RequiresPermission(Permission.TASK_READ)
    public List<Task> list(AuthPrincipal principal, @RequestParam(required = false) Long pitchId) {
        return pitchId == null ? repo.findByOrganizationIdOrderByCreatedAtDesc(principal.orgId())
                : repo.findByOrganizationIdAndPitchIdOrderByCreatedAtDesc(principal.orgId(), pitchId);
    }

    @PostMapping({"/api/tasks", "/api/v1/tasks"})
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.TASK_WRITE)
    public Task create(AuthPrincipal principal, @Valid @RequestBody TaskInput in) {
        Task t = new Task();
        t.setOrganizationId(principal.orgId());
        t.setUserId(principal.userId());
        t.setCreatedByUserId(principal.userId());
        apply(principal, t, in);
        if (in.assigneeUserId() == null) {
            t.setAssigneeUserId(principal.userId());   // new tasks default to their creator
        }
        Task saved = repo.save(t);
        activity.log(principal.orgId(), principal.userId(), "TASK", "Task created: " + saved.getTitle());
        return saved;
    }

    @PutMapping({"/api/tasks/{id}", "/api/v1/tasks/{id}"})
    @RequiresPermission(Permission.TASK_WRITE)
    public Task update(AuthPrincipal principal, @PathVariable Long id, @Valid @RequestBody TaskInput in) {
        Task t = find(principal, id);
        apply(principal, t, in);
        return repo.save(t);
    }

    @PatchMapping({"/api/tasks/{id}/status", "/api/v1/tasks/{id}/status"})
    @RequiresPermission(Permission.TASK_WRITE)
    public Task setStatus(AuthPrincipal principal, @PathVariable Long id, @RequestBody StatusInput in) {
        Task t = find(principal, id);
        t.setStatus(checked(in.status(), STATUSES, "status"));
        Task saved = repo.save(t);
        if ("DONE".equals(saved.getStatus())) {
            activity.log(principal.orgId(), principal.userId(), "TASK", "Task completed: " + saved.getTitle());
        }
        return saved;
    }

    @DeleteMapping({"/api/tasks/{id}", "/api/v1/tasks/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.TASK_WRITE)
    public void delete(AuthPrincipal principal, @PathVariable Long id) {
        repo.delete(find(principal, id));
    }

    private Task find(AuthPrincipal principal, Long id) {
        return repo.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Task"));
    }

    private void apply(AuthPrincipal principal, Task t, TaskInput in) {
        if (in.title() == null || in.title().isBlank()) {
            throw ApiException.badRequest("Title is required.");
        }
        t.setTitle(in.title().trim());
        t.setDescription(in.description());
        if (in.status() != null) {
            t.setStatus(checked(in.status(), STATUSES, "status"));
        }
        if (in.priority() != null) {
            t.setPriority(checked(in.priority(), PRIORITIES, "priority"));
        }
        if (in.dueDate() == null || in.dueDate().isBlank()) {
            t.setDueDate(null);
        } else {
            try {
                t.setDueDate(LocalDate.parse(in.dueDate().substring(0, Math.min(10, in.dueDate().length()))));
            } catch (DateTimeParseException e) {
                throw ApiException.badRequest("dueDate must be YYYY-MM-DD");
            }
        }
        if (in.pitchId() != null) {
            pitches.findByIdAndOrganizationId(in.pitchId(), principal.orgId())
                    .orElseThrow(() -> ApiException.notFound("Pitch"));
        }
        t.setPitchId(in.pitchId());
        if (in.assigneeUserId() != null) {
            memberships.findByOrganizationIdAndUserId(principal.orgId(), in.assigneeUserId())
                    .orElseThrow(() -> ApiException.badRequest("The assignee is not a member of this workspace."));
        }
        t.setAssigneeUserId(in.assigneeUserId());   // PUT semantics: null unassigns
    }

    private static String checked(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " must be one of " + allowed);
        }
        return value;
    }
}
