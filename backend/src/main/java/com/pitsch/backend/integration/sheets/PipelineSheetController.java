package com.pitsch.backend.integration.sheets;

import java.util.List;
import java.util.Map;

import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.RequiresPermission;
import com.pitsch.backend.integration.provider.SpreadsheetProvider;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PipelineSheetController {

    private final PipelineSyncService service;
    private final PitchRepository pitches;
    private final JobQueue jobs;

    public PipelineSheetController(PipelineSyncService service, PitchRepository pitches, JobQueue jobs) {
        this.service = service;
        this.pitches = pitches;
        this.jobs = jobs;
    }

    @GetMapping("/api/v1/integrations/sheets/config")
    @RequiresPermission(Permission.PITCH_READ)
    public ResponseEntity<PipelineSyncService.ConfigView> config(AuthPrincipal principal) {
        PipelineSyncService.ConfigView v = service.view(principal.orgId());
        return v == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(v);
    }

    @GetMapping("/api/v1/integrations/sheets/fields")
    public List<String> fields() {
        return java.util.Arrays.stream(PipelineField.values()).map(Enum::name).toList();
    }

    @GetMapping("/api/v1/integrations/sheets/preview")
    @RequiresPermission(Permission.INTEGRATION_MANAGE)
    public Map<String, Object> preview(AuthPrincipal principal, @RequestParam String spreadsheet,
                                       @RequestParam(required = false) String worksheet,
                                       @RequestParam(defaultValue = "1") int headerRow) {
        SpreadsheetProvider.SpreadsheetInfo info = service.preview(principal, spreadsheet);
        String ws = worksheet == null || worksheet.isBlank() ? (info.worksheets().isEmpty() ? null : info.worksheets().get(0)) : worksheet;
        List<String> header = ws == null ? List.of() : service.header(principal, spreadsheet, ws, headerRow);
        return Map.of("spreadsheetId", info.spreadsheetId(), "title", info.title(), "worksheets", info.worksheets(),
                "worksheet", ws == null ? "" : ws, "header", header);
    }

    @PutMapping("/api/v1/integrations/sheets/config")
    @RequiresPermission(Permission.INTEGRATION_MANAGE)
    public PipelineSyncService.ConfigView configure(AuthPrincipal principal, @RequestBody PipelineSyncService.ConfigUpdate req) {
        return service.configure(principal, req);
    }

    @DeleteMapping("/api/v1/integrations/sheets/config")
    @RequiresPermission(Permission.INTEGRATION_MANAGE)
    public ResponseEntity<Void> remove(AuthPrincipal principal) {
        service.removeConfig(principal);
        return ResponseEntity.noContent().build();
    }

    /** An explicit user request to push every pitch to the sheet (the click is the approval). */
    @PostMapping("/api/v1/integrations/sheets/sync-all")
    @RequiresPermission(Permission.ACTION_APPROVE)
    public ResponseEntity<Map<String, Object>> syncAll(AuthPrincipal principal) {
        int n = 0;
        long stamp = System.currentTimeMillis();
        for (Pitch p : pitches.findByOrganizationId(principal.orgId())) {
            jobs.enqueue(JobType.PIPELINE_SHEET_SYNC, principal.orgId(), null,
                    Map.of("pitchId", p.getId(), "key", "sheet:" + p.getId() + ":manual:" + stamp));
            n++;
        }
        return ResponseEntity.accepted().body(Map.of("queued", n));
    }
}
