package com.pitsch.backend.pitch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.common.PageResponse;
import com.pitsch.backend.common.Pagination;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.event.CalendarEvent;
import com.pitsch.backend.event.CalendarEventRepository;
import com.pitsch.backend.task.Task;
import com.pitsch.backend.task.TaskRepository;
import com.pitsch.backend.workflow.EmailDraft;
import com.pitsch.backend.workflow.EmailDraftRepository;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowViews;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Deal pipeline: search, filter, sort and paginate pitches; manual create/edit; pitch history. */
@RestController
public class PitchController {

    /** API shape of a pitch (the entity is never serialised directly). */
    public record PitchView(Long id, String companyName, String founderName, String founderEmail, String companyDomain,
                            String website, String sector, String stage, String oneLiner, String description,
                            String amountRequested, String source, String status, String dealStage, Long ownerUserId,
                            Double aiConfidence, Integer claimsSupported, Integer claimsContradicted,
                            Integer claimsUnresolved, String riskLevel, boolean hasFollowUp, Long latestWorkflowId,
                            Long latestBriefWorkflowId, Instant lastActivityAt, Instant createdAt, Instant updatedAt,
                            long version) {
        public static PitchView of(Pitch p) {
            return new PitchView(p.getId(), p.getCompanyName(), p.getFounderName(), p.getFounderEmail(),
                    p.getCompanyDomain(), p.getWebsite(), p.getSector(), p.getStage(), p.getOneLiner(), p.getDescription(),
                    p.getAmountRequested(), p.getSource(), p.getStatus(), p.getDealStage(), p.getOwnerUserId(),
                    p.getAiConfidence(), p.getClaimsSupported(), p.getClaimsContradicted(), p.getClaimsUnresolved(),
                    p.getRiskLevel(), p.isHasFollowUp(), p.getLatestWorkflowId(), p.getLatestBriefWorkflowId(),
                    p.getLastActivityAt(), p.getCreatedAt(), p.getUpdatedAt(), p.getVersion());
        }
    }

    /** Create/update body. Accepts the original API's "title"/"email" aliases. */
    public record PitchRequest(String companyName, String title, String founderName, String founderEmail, String email,
                               String website, String sector, String stage, String oneLiner, String description,
                               String amountRequested, String dealStage, Long ownerUserId, Long version) {
        PitchService.PitchInput input() {
            return new PitchService.PitchInput(companyName != null ? companyName : title, founderName,
                    founderEmail != null ? founderEmail : email, website, sector, stage, oneLiner, description,
                    amountRequested, dealStage, ownerUserId);
        }
    }

    public record TimelineEntry(Instant at, String kind, String title, String detail, Long workflowId, Long refId) { }

    public record StageCount(String dealStage, long count) { }

    private static final Map<String, String> SORTABLE = Map.of("updatedAt", "updatedAt", "createdAt", "createdAt",
            "lastActivityAt", "lastActivityAt", "companyName", "companyName", "aiConfidence", "aiConfidence",
            "dealStage", "dealStage");
    private static final Set<String> RISK = Set.of("LOW", "MEDIUM", "HIGH");

    private final PitchRepository pitches;
    private final PitchService service;
    private final WorkflowRepository workflows;
    private final WorkflowViews views;
    private final EmailRepository emails;
    private final EmailDraftRepository drafts;
    private final CalendarEventRepository events;
    private final TaskRepository tasks;
    private final Json json;

    public PitchController(PitchRepository pitches, PitchService service, WorkflowRepository workflows,
                           WorkflowViews views, EmailRepository emails, EmailDraftRepository drafts,
                           CalendarEventRepository events, TaskRepository tasks, Json json) {
        this.pitches = pitches;
        this.service = service;
        this.workflows = workflows;
        this.views = views;
        this.emails = emails;
        this.drafts = drafts;
        this.events = events;
        this.tasks = tasks;
        this.json = json;
    }

    /** Legacy: newest 200 pitches as a plain array (original frontend contract). */
    @GetMapping("/api/pitches")
    @RequiresPermission(Permission.PITCH_READ)
    public List<PitchView> legacyList(AuthPrincipal principal) {
        return pitches.findAll(inOrg(principal.orgId()), Pagination.of(0, 100, null, SORTABLE, "updatedAt,desc"))
                .map(PitchView::of).getContent();
    }

    @GetMapping("/api/v1/pitches")
    @RequiresPermission(Permission.PITCH_READ)
    public PageResponse<PitchView> list(AuthPrincipal principal,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String dealStage,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String sector,
                                        @RequestParam(required = false) Long ownerUserId,
                                        @RequestParam(required = false) String riskLevel,
                                        @RequestParam(required = false) Double minConfidence,
                                        @RequestParam(required = false) Boolean hasFollowUp,
                                        @RequestParam(required = false) String source,
                                        @RequestParam(required = false) LocalDate createdFrom,
                                        @RequestParam(required = false) LocalDate createdTo,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size,
                                        @RequestParam(required = false) String sort) {
        Long org = principal.orgId();
        List<String> stages = csvEnum(dealStage);
        List<String> statuses = status == null || status.isBlank() ? List.of()
                : Arrays.stream(status.split(",")).map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).toList();
        String risk = Pagination.oneOf(riskLevel == null ? null : riskLevel.toUpperCase(Locale.ROOT), RISK, "riskLevel");
        String src = Pagination.oneOf(source == null ? null : source.toUpperCase(Locale.ROOT), Set.of("EMAIL", "MANUAL"), "source");
        if (minConfidence != null && (minConfidence < 0 || minConfidence > 1)) {
            throw ApiException.badRequest("minConfidence must be between 0 and 1");
        }
        String like = q == null || q.isBlank() ? null : "%" + escapeLike(q.trim().toLowerCase(Locale.ROOT)) + "%";
        String sectorLike = sector == null || sector.isBlank() ? null : "%" + escapeLike(sector.trim().toLowerCase(Locale.ROOT)) + "%";
        Specification<Pitch> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("organizationId"), org));
            if (like != null) {
                p.add(cb.or(cb.like(cb.lower(root.get("companyName")), like, '\\'),
                        cb.like(cb.lower(root.get("founderName")), like, '\\'),
                        cb.like(cb.lower(root.get("founderEmail")), like, '\\'),
                        cb.like(cb.lower(root.get("companyDomain")), like, '\\'),
                        cb.like(cb.lower(root.get("oneLiner")), like, '\\')));
            }
            if (!stages.isEmpty()) {
                p.add(root.get("dealStage").in(stages));
            }
            if (!statuses.isEmpty()) {
                p.add(root.get("status").in(statuses));
            }
            if (sectorLike != null) {
                p.add(cb.like(cb.lower(root.get("sector")), sectorLike, '\\'));
            }
            if (ownerUserId != null) {
                p.add(cb.equal(root.get("ownerUserId"), ownerUserId));
            }
            if (risk != null) {
                p.add(cb.equal(root.get("riskLevel"), risk));
            }
            if (minConfidence != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("aiConfidence"), minConfidence));
            }
            if (hasFollowUp != null) {
                p.add(cb.equal(root.get("hasFollowUp"), hasFollowUp));
            }
            if (src != null) {
                p.add(cb.equal(root.get("source"), src));
            }
            if (createdFrom != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("createdAt"), createdFrom.atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            if (createdTo != null) {
                p.add(cb.lessThan(root.get("createdAt"), createdTo.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            }
            return cb.and(p.toArray(new Predicate[0]));
        };
        Page<Pitch> result = pitches.findAll(spec, Pagination.of(page, size, sort, SORTABLE, "lastActivityAt,desc"));
        return PageResponse.of(result, PitchView::of);
    }

    /** Pipeline board counts per deal stage. */
    @GetMapping("/api/v1/pitches/stages")
    @RequiresPermission(Permission.PITCH_READ)
    public List<StageCount> stages(AuthPrincipal principal) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (DealStage s : DealStage.values()) {
            counts.put(s.name(), 0L);
        }
        for (Object[] row : pitches.countByDealStage(principal.orgId())) {
            counts.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return counts.entrySet().stream().map(e -> new StageCount(e.getKey(), e.getValue())).toList();
    }

    /** Pitch + its workflows (newest first) + the latest investment brief, if any. */
    @GetMapping({"/api/pitches/{id}", "/api/v1/pitches/{id}"})
    @RequiresPermission(Permission.PITCH_READ)
    public Map<String, Object> get(AuthPrincipal principal, @PathVariable Long id) {
        Pitch p = find(principal, id);
        List<Workflow> wfs = workflows.findByPitchIdAndOrganizationIdOrderByCreatedAtDesc(id, principal.orgId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pitch", PitchView.of(p));
        out.put("workflows", views.summaries(wfs));
        Workflow briefWf = p.getLatestBriefWorkflowId() == null ? null
                : workflows.findByIdAndOrganizationId(p.getLatestBriefWorkflowId(), principal.orgId()).orElse(null);
        if (briefWf != null && briefWf.getAnalysisJson() != null) {
            JsonNode analysis = json.read(briefWf.getAnalysisJson());
            out.put("briefWorkflowId", briefWf.getId());
            out.put("brief", analysis == null ? null : analysis.get("brief"));
            out.put("briefMarkdown", briefWf.getBriefMarkdown());
            out.put("briefUpdatedAt", briefWf.getCompletedAt() != null ? briefWf.getCompletedAt() : briefWf.getUpdatedAt());
        }
        return out;
    }

    /** Everything that happened on this pitch, newest first: emails, agent runs, drafts, meetings, tasks. */
    @GetMapping("/api/v1/pitches/{id}/timeline")
    @RequiresPermission(Permission.PITCH_READ)
    public List<TimelineEntry> timeline(AuthPrincipal principal, @PathVariable Long id) {
        Long org = principal.orgId();
        Pitch p = find(principal, id);
        List<Workflow> wfs = workflows.findByPitchIdAndOrganizationIdOrderByCreatedAtDesc(id, org);
        List<TimelineEntry> out = new ArrayList<>();
        out.add(new TimelineEntry(p.getCreatedAt(), "PITCH_CREATED",
                "Pitch created" + ("MANUAL".equals(p.getSource()) ? " manually" : " from email"), p.getCompanyName(), null, p.getId()));
        List<Long> emailIds = wfs.stream().map(Workflow::getEmailId).filter(java.util.Objects::nonNull).toList();
        Map<Long, Email> emailById = new LinkedHashMap<>();
        emails.findAllById(emailIds).stream().filter(e -> org.equals(e.getOrganizationId()))
                .forEach(e -> emailById.put(e.getId(), e));
        for (Workflow wf : wfs) {
            Email e = emailById.get(wf.getEmailId());
            if (e != null) {
                out.add(new TimelineEntry(e.getReceivedAt(), "EMAIL_RECEIVED",
                        "Email from " + e.getSender(), e.getSubject(), wf.getId(), e.getId()));
            }
            if (wf.getCompletedAt() != null && wf.getBriefMarkdown() != null) {
                out.add(new TimelineEntry(wf.getCompletedAt(), "BRIEF_READY", "Investment brief ready", null, wf.getId(), wf.getId()));
            }
            out.add(new TimelineEntry(wf.getUpdatedAt(), "WORKFLOW_" + wf.getStatus(),
                    "Workflow " + wf.getStatus().toLowerCase(Locale.ROOT).replace('_', ' '), wf.getSummary(), wf.getId(), wf.getId()));
        }
        if (!wfs.isEmpty()) {
            for (EmailDraft d : drafts.findByWorkflowIdIn(wfs.stream().map(Workflow::getId).toList())) {
                if (!org.equals(d.getOrganizationId())) {
                    continue;
                }
                Instant at = d.getSentAt() != null ? d.getSentAt() : d.getCreatedAt();
                out.add(new TimelineEntry(at, "DRAFT_" + d.getStatus(), "Reply to " + d.getRecipient() + " — "
                        + d.getStatus().toLowerCase(Locale.ROOT), d.getSubject(), d.getWorkflowId(), d.getId()));
            }
        }
        for (CalendarEvent ev : events.findByOrganizationIdAndPitchIdOrderByStartTimeAsc(org, id)) {
            out.add(new TimelineEntry(ev.getCreatedAt(), "MEETING_" + ev.getSyncStatus(), ev.getTitle(),
                    ev.getStartTime().toString(), ev.getWorkflowId(), ev.getId()));
        }
        for (Task t : tasks.findByOrganizationIdAndPitchIdOrderByCreatedAtDesc(org, id)) {
            out.add(new TimelineEntry(t.getCreatedAt(), "TASK", t.getTitle(), t.getStatus(), null, t.getId()));
        }
        out.removeIf(e -> e.at() == null);
        out.sort(Comparator.comparing(TimelineEntry::at).reversed());
        return out;
    }

    @PostMapping({"/api/pitches", "/api/v1/pitches"})
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permission.PITCH_WRITE)
    public PitchView create(AuthPrincipal principal, @RequestBody PitchRequest in) {
        return PitchView.of(service.createManual(principal.orgId(), principal.userId(), in.input()));
    }

    /** Partial update (stage, owner, details). Send "version" to get a 409 instead of overwriting a concurrent edit. */
    @PatchMapping("/api/v1/pitches/{id}")
    @RequiresPermission(Permission.PITCH_WRITE)
    public PitchView update(AuthPrincipal principal, @PathVariable Long id, @RequestBody PitchRequest in) {
        return PitchView.of(service.update(principal.orgId(), principal.userId(), id, in.version(), in.input()));
    }

    @DeleteMapping({"/api/pitches/{id}", "/api/v1/pitches/{id}"})
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresPermission(Permission.PITCH_DELETE)
    public void delete(AuthPrincipal principal, @PathVariable Long id) {
        service.delete(principal.orgId(), principal.userId(), id);
    }

    private Pitch find(AuthPrincipal principal, Long id) {
        return pitches.findByIdAndOrganizationId(id, principal.orgId()).orElseThrow(() -> ApiException.notFound("Pitch"));
    }

    private static Specification<Pitch> inOrg(Long org) {
        return (root, q, cb) -> cb.equal(root.get("organizationId"), org);
    }

    private static List<String> csvEnum(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(",")).filter(s -> !s.isBlank())
                .map(s -> Pagination.enumParam(DealStage.class, s, "dealStage").name()).toList();
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
