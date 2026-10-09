package com.pitsch.backend.pitch;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.org.MembershipRepository;
import com.pitsch.backend.task.TaskRepository;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Pitch records: created from classified emails, enriched by the Document and Analysis agents. */
@Service
public class PitchService {

    /** Fields a user may set on a manual pitch or edit later. Null means "unchanged". */
    public record PitchInput(String companyName, String founderName, String founderEmail, String website,
                             String sector, String stage, String oneLiner, String description, String amountRequested,
                             String dealStage, Long ownerUserId) { }

    private static final java.util.regex.Pattern EMAIL = java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final PitchRepository pitches;
    private final WorkflowRepository workflows;
    private final TaskRepository tasks;
    private final MembershipRepository memberships;
    private final AuditService audit;
    private final Clock clock;

    public PitchService(PitchRepository pitches, WorkflowRepository workflows, TaskRepository tasks,
                        MembershipRepository memberships, AuditService audit, Clock clock) {
        this.pitches = pitches;
        this.workflows = workflows;
        this.tasks = tasks;
        this.memberships = memberships;
        this.audit = audit;
        this.clock = clock;
    }

    /** Manual entry by a user (no AI involved). */
    @Transactional
    public Pitch createManual(Long orgId, Long actorUserId, PitchInput in) {
        if (in.companyName() == null || in.companyName().isBlank()) {
            throw ApiException.badRequest("companyName is required");
        }
        Pitch p = new Pitch();
        p.setOrganizationId(orgId);
        p.setUserId(actorUserId);
        p.setOwnerUserId(actorUserId);
        p.setSource("MANUAL");
        p.setStatus("NEW");
        apply(orgId, p, in);
        p = pitches.save(p);
        audit.record(orgId, actorUserId, AuditAction.PITCH_CREATED, "PITCH", p.getId(), java.util.Map.of("source", "MANUAL"));
        return p;
    }

    /** Partial update with optimistic locking: a stale {@code expectedVersion} is a 409, not a silent overwrite. */
    @Transactional
    public Pitch update(Long orgId, Long actorUserId, Long pitchId, Long expectedVersion, PitchInput in) {
        Pitch p = pitches.findByIdAndOrganizationId(pitchId, orgId).orElseThrow(() -> ApiException.notFound("Pitch"));
        if (expectedVersion != null && expectedVersion != p.getVersion()) {
            throw new ApiException(ErrorCode.CONFLICT, "This pitch was changed by someone else. Reload and try again.");
        }
        String oldStage = p.getDealStage();
        Long oldOwner = p.getOwnerUserId();
        apply(orgId, p, in);
        p.setLastActivityAt(clock.instant());
        p = pitches.save(p);
        java.util.Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (!java.util.Objects.equals(oldStage, p.getDealStage())) {
            meta.put("dealStage", oldStage + "->" + p.getDealStage());
        }
        if (!java.util.Objects.equals(oldOwner, p.getOwnerUserId())) {
            meta.put("ownerUserId", p.getOwnerUserId());
        }
        audit.record(orgId, actorUserId, AuditAction.PITCH_UPDATED, "PITCH", p.getId(), meta);
        return p;
    }

    /** Deletes the pitch record; its workflows and tasks are kept (audit history) but detached. */
    @Transactional
    public void delete(Long orgId, Long actorUserId, Long pitchId) {
        Pitch p = pitches.findByIdAndOrganizationId(pitchId, orgId).orElseThrow(() -> ApiException.notFound("Pitch"));
        if (workflows.countByOrganizationIdAndPitchIdAndStatusIn(orgId, pitchId, WorkflowStatus.ACTIVE) > 0) {
            throw new ApiException(ErrorCode.CONFLICT, "Agents are still working on this pitch. Stop the workflow first.");
        }
        workflows.detachPitch(orgId, pitchId);
        tasks.detachPitch(orgId, pitchId);
        pitches.delete(p);
        audit.record(orgId, actorUserId, AuditAction.PITCH_DELETED, "PITCH", pitchId,
                java.util.Map.of("companyName", p.getCompanyName() == null ? "" : p.getCompanyName()));
    }

    private void apply(Long orgId, Pitch p, PitchInput in) {
        if (in.companyName() != null) {
            if (in.companyName().isBlank()) {
                throw ApiException.badRequest("companyName cannot be empty");
            }
            p.setCompanyName(Json.truncate(in.companyName().trim(), 255));
        }
        if (in.founderName() != null) {
            p.setFounderName(blankToNull(Json.truncate(in.founderName().trim(), 255)));
        }
        if (in.founderEmail() != null) {
            String e = in.founderEmail().trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && !EMAIL.matcher(e).matches()) {
                throw ApiException.badRequest("founderEmail must be a valid email address");
            }
            p.setFounderEmail(blankToNull(e));
            if (p.getCompanyDomain() == null && !e.isEmpty() && !Json.isFreeMail(Json.domainOf(e))) {
                p.setCompanyDomain(Json.domainOf(e));
            }
        }
        if (in.website() != null) {
            String w = in.website().trim();
            if (!w.isEmpty() && !(w.startsWith("https://") || w.startsWith("http://"))) {
                w = "https://" + w;
            }
            p.setWebsite(blankToNull(Json.truncate(w, 255)));
            if (!w.isEmpty()) {
                p.setCompanyDomain(Json.domainOf(w));
            }
        }
        if (in.sector() != null) {
            p.setSector(blankToNull(Json.truncate(in.sector().trim(), 255)));
        }
        if (in.stage() != null) {
            p.setStage(blankToNull(Json.truncate(in.stage().trim(), 255)));
        }
        if (in.oneLiner() != null) {
            p.setOneLiner(blankToNull(Json.truncate(in.oneLiner().trim(), 1000)));
        }
        if (in.description() != null) {
            p.setDescription(blankToNull(Json.truncate(in.description(), 20_000)));
        }
        if (in.amountRequested() != null) {
            p.setAmountRequested(blankToNull(Json.truncate(in.amountRequested().trim(), 255)));
        }
        if (in.dealStage() != null) {
            DealStage stage = com.pitsch.backend.common.Pagination.enumParam(DealStage.class, in.dealStage(), "dealStage");
            if (stage == null) {
                throw ApiException.badRequest("dealStage cannot be empty");
            }
            p.setDealStage(stage.name());
        }
        if (in.ownerUserId() != null) {
            if (memberships.findByOrganizationIdAndUserId(orgId, in.ownerUserId()).isEmpty()) {
                throw ApiException.badRequest("ownerUserId must be a member of this workspace");
            }
            p.setOwnerUserId(in.ownerUserId());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Transactional
    public Pitch createFromEmail(Long orgId, Long ownerId, Long workflowId, Email email, JsonNode classification) {
        List<String> companies = Json.strings(classification, "detectedCompanies");
        boolean forwarded = Json.bool(classification, "isForwarded");
        String original = Json.text(classification, "originalSenderEmail");
        String founderEmail = forwarded && original != null ? original.toLowerCase(Locale.ROOT) : email.getSender();
        String domain = Json.domainOf(founderEmail);

        Pitch p = new Pitch();
        p.setOrganizationId(orgId);
        p.setUserId(ownerId);
        p.setOwnerUserId(ownerId);
        p.setCompanyName(Json.truncate(firstText(companies.isEmpty() ? null : companies.get(0),
                Json.isFreeMail(domain) ? null : domain, email.getSenderName(), "Unknown company"), 255));
        p.setFounderName(forwarded ? null : email.getSenderName());
        p.setFounderEmail(founderEmail);
        p.setCompanyDomain(Json.isFreeMail(domain) ? null : domain);
        p.setSource("EMAIL");
        p.setStatus("NEW");
        p.setFirstEmailId(email.getId());
        p.setLatestWorkflowId(workflowId);
        if (classification != null && classification.has("confidence")) {
            p.setAiConfidence(classification.path("confidence").asDouble());
        }
        addThread(p, email.getThreadId());
        return pitches.save(p);
    }

    @Transactional
    public Pitch linkFollowUp(Long pitchId, Long workflowId, String threadId) {
        Pitch p = pitches.findById(pitchId).orElseThrow(() -> ApiException.notFound("Pitch"));
        addThread(p, threadId);
        p.setLatestWorkflowId(workflowId);
        p.setHasFollowUp(true);
        p.setStatus("FOLLOW_UP");
        p.setLastActivityAt(clock.instant());
        return pitches.save(p);
    }

    @Transactional
    public Pitch updateFromDocument(Long pitchId, JsonNode doc) {
        Pitch p = pitches.findById(pitchId).orElseThrow(() -> ApiException.notFound("Pitch"));
        JsonNode company = doc.path("company");
        if (Json.text(company, "name") != null) {
            p.setCompanyName(Json.truncate(Json.text(company, "name"), 255));
        }
        if (Json.text(company, "website") != null) {
            p.setWebsite(Json.truncate(Json.text(company, "website"), 255));
            if (p.getCompanyDomain() == null) {
                p.setCompanyDomain(Json.domainOf(Json.text(company, "website")));
            }
        }
        if (Json.text(company, "sector") != null) {
            p.setSector(Json.truncate(Json.text(company, "sector"), 255));
        }
        if (Json.text(company, "stage") != null) {
            p.setStage(Json.truncate(Json.text(company, "stage"), 255));
        }
        if (Json.text(company, "oneLiner") != null) {
            p.setOneLiner(Json.truncate(Json.text(company, "oneLiner"), 1000));
        }
        String amount = Json.text(doc.path("fundraise"), "amountRequested");
        if (amount != null) {
            p.setAmountRequested(Json.truncate(amount, 255));
        }
        JsonNode founders = doc.path("founders");
        if (p.getFounderName() == null && founders.size() > 0 && Json.text(founders.get(0), "name") != null) {
            p.setFounderName(Json.truncate(Json.text(founders.get(0), "name"), 255));
        }
        p.setLastActivityAt(clock.instant());
        return pitches.save(p);
    }

    /** Stores the claim-evidence summary of a finished brief for filtering ("risk", "confidence"). */
    @Transactional
    public Pitch recordBrief(Long pitchId, Long workflowId, JsonNode analysis) {
        Pitch p = pitches.findById(pitchId).orElseThrow(() -> ApiException.notFound("Pitch"));
        JsonNode summary = analysis.path("claimStatusSummary");
        int supported = summary.path("VERIFIED").asInt(0) + summary.path("PARTIALLY_VERIFIED").asInt(0);
        int contradicted = summary.path("CONTRADICTED").asInt(0);
        int unresolved = summary.path("UNVERIFIED").asInt(0) + summary.path("NOT_FOUND").asInt(0)
                + summary.path("NOT_CHECKED").asInt(0);
        p.setClaimsSupported(supported);
        p.setClaimsContradicted(contradicted);
        p.setClaimsUnresolved(unresolved);
        p.setRiskLevel(riskLevel(supported, contradicted, unresolved));
        p.setLatestBriefWorkflowId(workflowId);
        p.setLatestWorkflowId(workflowId);
        p.setStatus("AWAITING_REVIEW");
        p.setLastActivityAt(clock.instant());
        return pitches.save(p);
    }

    @Transactional
    public void setStatus(Long pitchId, String status) {
        pitches.findById(pitchId).ifPresent(p -> {
            p.setStatus(status);
            p.setLastActivityAt(clock.instant());
            pitches.save(p);
        });
    }

    /** Evidence-quality signal derived from verification counts. It describes the evidence, not the company. */
    static String riskLevel(int supported, int contradicted, int unresolved) {
        if (contradicted > 0) {
            return "HIGH";
        }
        if (unresolved > supported) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /** Existing pitches this email may belong to (thread, founder email, company domain, or company named). */
    @Transactional(readOnly = true)
    public List<Pitch> followUpCandidates(Email email) {
        String sender = email.getSender() == null ? "" : email.getSender().toLowerCase(Locale.ROOT);
        String domain = Json.domainOf(sender);
        String domainParam = domain == null || Json.isFreeMail(domain) ? PitchRepository.NO_MATCH : domain;
        String threadParam = email.getThreadId() == null || email.getThreadId().isBlank()
                ? PitchRepository.NO_MATCH : email.getThreadId();
        List<Pitch> out = new ArrayList<>(pitches.findCandidates(email.getOrganizationId(), sender, domainParam,
                threadParam, PageRequest.of(0, 20)));
        String text = ((email.getSubject() == null ? "" : email.getSubject()) + "\n"
                + (email.getBody() == null ? "" : email.getBody())).toLowerCase(Locale.ROOT);
        for (Pitch p : pitches.findRecent(email.getOrganizationId(), PageRequest.of(0, 200))) {
            if (out.size() >= 20) {
                break;
            }
            if (out.stream().noneMatch(x -> x.getId().equals(p.getId())) && mentions(text, p.getCompanyName())) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean mentions(String lowerText, String name) {
        if (name == null || name.trim().length() < 3) {
            return false;
        }
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(name.trim().toLowerCase(Locale.ROOT))
                + "(?![\\p{L}\\p{N}])").matcher(lowerText).find();
    }

    static void addThread(Pitch p, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        List<String> threads = Json.splitCsv(p.getThreadIds());
        if (!threads.contains(threadId)) {
            threads.add(threadId);
            while (Json.joinCsv(threads).length() > 2000 && threads.size() > 1) {
                threads.remove(0);
            }
            p.setThreadIds(Json.joinCsv(threads));
        }
    }

    static String firstText(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
