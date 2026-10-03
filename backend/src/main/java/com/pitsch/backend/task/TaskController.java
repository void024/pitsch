package com.pitsch.backend.task;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

import com.pitsch.backend.activity.ActivityService;
import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    public record TaskInput(String title, String description, String status, String priority, String dueDate) { }

    public record StatusInput(String status) { }

    private static final Set<String> STATUSES = Set.of("TODO", "IN_PROGRESS", "DONE");
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");

    private final TaskRepository repo;
    private final ActivityService activity;

    public TaskController(TaskRepository repo, ActivityService activity) {
        this.repo = repo;
        this.activity = activity;
    }

    @GetMapping
    public List<Task> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return repo.findByUserIdOrderByCreatedAtDesc(userId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Task create(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @RequestBody TaskInput in) {
        Task t = new Task();
        t.setUserId(userId);
        apply(t, in);
        Task saved = repo.save(t);
        activity.log(userId, "TASK", "Task created: " + saved.getTitle());
        return saved;
    }

    @PutMapping("/{id}")
    public Task update(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id,
                       @RequestBody TaskInput in) {
        Task t = find(userId, id);
        apply(t, in);
        return repo.save(t);
    }

    @PatchMapping("/{id}/status")
    public Task setStatus(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id,
                          @RequestBody StatusInput in) {
        Task t = find(userId, id);
        t.setStatus(checked(in.status(), STATUSES, "status"));
        Task saved = repo.save(t);
        if ("DONE".equals(saved.getStatus())) {
            activity.log(userId, "TASK", "Task completed: " + saved.getTitle());
        }
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        repo.delete(find(userId, id));
    }

    private Task find(Long userId, Long id) {
        return repo.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Task"));
    }

    private static void apply(Task t, TaskInput in) {
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
    }

    private static String checked(String value, Set<String> allowed, String field) {
        if (value == null || !allowed.contains(value)) {
            throw ApiException.badRequest(field + " must be one of " + allowed);
        }
        return value;
    }
}
