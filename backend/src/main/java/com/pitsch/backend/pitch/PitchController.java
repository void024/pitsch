package com.pitsch.backend.pitch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthInterceptor;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.workflow.Workflow;
import com.pitsch.backend.workflow.WorkflowRepository;
import com.pitsch.backend.workflow.WorkflowViews;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/pitches")
public class PitchController {

    private final PitchRepository pitches;
    private final WorkflowRepository workflows;
    private final WorkflowViews views;
    private final Json json;

    public PitchController(PitchRepository pitches, WorkflowRepository workflows, WorkflowViews views, Json json) {
        this.pitches = pitches;
        this.workflows = workflows;
        this.views = views;
        this.json = json;
    }

    @GetMapping
    public List<Pitch> list(@RequestAttribute(AuthInterceptor.USER_ID) Long userId) {
        return pitches.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    /** Pitch + its workflows (newest first) + the latest research brief, if any. */
    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        Pitch p = pitches.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Pitch"));
        List<Workflow> wfs = workflows.findByPitchIdOrderByCreatedAtDesc(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pitch", p);
        out.put("workflows", wfs.stream().map(views::summary).toList());
        Workflow briefWf = p.getLatestBriefWorkflowId() == null ? null
                : workflows.findById(p.getLatestBriefWorkflowId()).orElse(null);
        if (briefWf != null && briefWf.getAnalysisJson() != null) {
            out.put("briefWorkflowId", briefWf.getId());
            out.put("brief", json.read(briefWf.getAnalysisJson()).get("brief"));
            out.put("briefMarkdown", briefWf.getBriefMarkdown());
        }
        return out;
    }

    /** Manual entry (kept from the original API; accepts title/email as aliases). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Pitch create(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @RequestBody Pitch in) {
        if (in.getCompanyName() == null || in.getCompanyName().isBlank()) {
            throw ApiException.badRequest("companyName (or title) is required");
        }
        Pitch p = new Pitch();
        p.setUserId(userId);
        p.setCompanyName(in.getCompanyName().trim());
        p.setFounderName(in.getFounderName());
        p.setFounderEmail(in.getFounderEmail() == null ? null : in.getFounderEmail().trim().toLowerCase());
        p.setCompanyDomain(in.getCompanyDomain() != null ? in.getCompanyDomain() : Json.domainOf(in.getWebsite()));
        p.setWebsite(in.getWebsite());
        p.setSector(in.getSector());
        p.setStage(in.getStage());
        p.setDescription(in.getDescription());
        p.setSource("MANUAL");
        p.setStatus(in.getStatus() == null || in.getStatus().isBlank() ? "NEW" : in.getStatus());
        return pitches.save(p);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestAttribute(AuthInterceptor.USER_ID) Long userId, @PathVariable Long id) {
        pitches.delete(pitches.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.notFound("Pitch")));
    }
}
