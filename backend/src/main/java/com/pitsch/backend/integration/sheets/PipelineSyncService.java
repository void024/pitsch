package com.pitsch.backend.integration.sheets;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pitsch.backend.audit.AuditAction;
import com.pitsch.backend.audit.AuditService;
import com.pitsch.backend.auth.AuthPrincipal;
import com.pitsch.backend.auth.Permission;
import com.pitsch.backend.auth.UserRepository;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.idempotency.ExternalOperationService;
import com.pitsch.backend.integration.Integration;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.integration.IntegrationService;
import com.pitsch.backend.integration.ProviderRegistry;
import com.pitsch.backend.integration.provider.SpreadsheetProvider;
import com.pitsch.backend.pitch.Pitch;
import com.pitsch.backend.pitch.PitchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the workspace's pipeline spreadsheet in sync, using the workspace's own column mapping (no hard-coded
 * columns). Rows are found by the Pitch ID key column, so users can sort or add their own columns safely; existing
 * rows are updated cell by cell so unmapped columns are never overwritten.
 */
@Service
public class PipelineSyncService {

    public record Mapping(String field, String column) { }

    public record ConfigView(String spreadsheetId, String worksheetTitle, int headerRow, List<Mapping> mapping,
                             String accountEmail, boolean connected) { }

    public record ConfigUpdate(String spreadsheet, String worksheetTitle, Integer headerRow, List<Mapping> mapping) { }

    private final PipelineSheetConfigRepository configs;
    private final PipelineSheetRowRepository rows;
    private final IntegrationConnectionRepository connections;
    private final IntegrationService integrations;
    private final ProviderRegistry providers;
    private final PitchRepository pitches;
    private final UserRepository users;
    private final ExternalOperationService ops;
    private final AuditService audit;
    private final PitschProperties props;
    private final Json json;
    private final Clock clock;

    public PipelineSyncService(PipelineSheetConfigRepository configs, PipelineSheetRowRepository rows,
                               IntegrationConnectionRepository connections, IntegrationService integrations,
                               ProviderRegistry providers, PitchRepository pitches, UserRepository users,
                               ExternalOperationService ops, AuditService audit, PitschProperties props, Json json,
                               Clock clock) {
        this.configs = configs;
        this.rows = rows;
        this.connections = connections;
        this.integrations = integrations;
        this.providers = providers;
        this.pitches = pitches;
        this.users = users;
        this.ops = ops;
        this.audit = audit;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    public boolean configured(Long orgId) {
        return configs.findByOrganizationId(orgId).isPresent();
    }

    @Transactional(readOnly = true)
    public ConfigView view(Long orgId) {
        PipelineSheetConfig c = configs.findByOrganizationId(orgId).orElse(null);
        if (c == null) {
            return null;
        }
        IntegrationConnection conn = connections.findById(c.getConnectionId()).orElse(null);
        return new ConfigView(c.getSpreadsheetId(), c.getWorksheetTitle(), c.getHeaderRow(), mapping(c),
                conn == null ? null : conn.getAccountEmail(), conn != null && conn.isUsableFor(Integration.SHEETS));
    }

    /** Validates the spreadsheet, worksheet and mapping against the live header row before saving. */
    @Transactional
    public ConfigView configure(AuthPrincipal principal, ConfigUpdate in) {
        principal.require(Permission.INTEGRATION_MANAGE);
        IntegrationConnection conn = providers.demo() ? demoConnection(principal)
                : integrations.require(principal.orgId(), principal.userId(), Integration.SHEETS);
        String spreadsheetId = spreadsheetId(in.spreadsheet());
        SpreadsheetProvider sheets = providers.sheets();
        SpreadsheetProvider.SpreadsheetInfo info = sheets.describe(conn, spreadsheetId);
        String worksheet = in.worksheetTitle() == null || in.worksheetTitle().isBlank()
                ? (info.worksheets().isEmpty() ? null : info.worksheets().get(0)) : in.worksheetTitle().trim();
        if (worksheet == null || !info.worksheets().contains(worksheet)) {
            throw ApiException.badRequest("Worksheet not found. Available: " + info.worksheets());
        }
        int headerRow = in.headerRow() == null ? 1 : in.headerRow();
        if (headerRow < 1 || headerRow > 50) {
            throw ApiException.badRequest("headerRow must be between 1 and 50.");
        }
        List<String> header = sheets.header(conn, spreadsheetId, worksheet, headerRow);
        List<Mapping> mapping = in.mapping() == null ? List.of() : in.mapping();
        boolean hasKey = false;
        for (Mapping m : mapping) {
            try {
                PipelineField.valueOf(m.field());
            } catch (IllegalArgumentException | NullPointerException e) {
                throw ApiException.badRequest("Unknown field '" + m.field() + "'.");
            }
            if (m.column() == null || !header.contains(m.column().trim())) {
                throw ApiException.badRequest("Column '" + m.column() + "' is not in the header row " + header + ".");
            }
            hasKey |= "pitchId".equals(m.field());
        }
        if (!hasKey) {
            throw ApiException.badRequest("Map the pitchId field to a column — it identifies each pitch's row.");
        }
        PipelineSheetConfig c = configs.findByOrganizationId(principal.orgId()).orElseGet(PipelineSheetConfig::new);
        c.setOrganizationId(principal.orgId());
        c.setConnectionId(conn.getId());
        c.setSpreadsheetId(spreadsheetId);
        c.setWorksheetTitle(worksheet);
        c.setHeaderRow(headerRow);
        c.setFieldMappingJson(json.write(mapping.stream().map(m -> new Mapping(m.field(), m.column().trim())).toList()));
        configs.save(c);
        audit.record(principal.orgId(), principal.userId(), AuditAction.PIPELINE_SHEET_CONFIGURED, "INTEGRATION",
                conn.getId(), Map.of("columns", mapping.size()));
        return view(principal.orgId());
    }

    @Transactional
    public void removeConfig(AuthPrincipal principal) {
        principal.require(Permission.INTEGRATION_MANAGE);
        configs.findByOrganizationId(principal.orgId()).ifPresent(configs::delete);
    }

    public SpreadsheetProvider.SpreadsheetInfo preview(AuthPrincipal principal, String spreadsheet) {
        principal.require(Permission.INTEGRATION_MANAGE);
        IntegrationConnection conn = providers.demo() ? demoConnection(principal)
                : integrations.require(principal.orgId(), principal.userId(), Integration.SHEETS);
        return providers.sheets().describe(conn, spreadsheetId(spreadsheet));
    }

    public List<String> header(AuthPrincipal principal, String spreadsheet, String worksheet, int headerRow) {
        principal.require(Permission.INTEGRATION_MANAGE);
        IntegrationConnection conn = providers.demo() ? demoConnection(principal)
                : integrations.require(principal.orgId(), principal.userId(), Integration.SHEETS);
        return providers.sheets().header(conn, spreadsheetId(spreadsheet), worksheet, headerRow);
    }

    /** Upserts one pitch row. Idempotent by key column; external row index tracked in pipeline_sheet_rows. */
    public void sync(Long orgId, Long pitchId, String idempotencyKey) {
        PipelineSheetConfig c = configs.findByOrganizationId(orgId).orElse(null);
        if (c == null) {
            return;
        }
        IntegrationConnection conn = connections.findById(c.getConnectionId())
                .filter(x -> x.isUsableFor(Integration.SHEETS) || providers.demo())
                .orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_NOT_CONNECTED,
                        "The pipeline sheet's Google connection is not available. Reconnect Sheets."));
        Pitch p = pitches.findByIdAndOrganizationId(pitchId, orgId).orElseThrow(() -> ApiException.notFound("Pitch"));
        SpreadsheetProvider sheets = providers.sheets();
        List<String> header = sheets.header(conn, c.getSpreadsheetId(), c.getWorksheetTitle(), c.getHeaderRow());
        Map<String, String> values = values(p);
        Map<Integer, String> cells = new LinkedHashMap<>();
        int keyColumn = -1;
        for (Mapping m : mapping(c)) {
            int col = header.indexOf(m.column());
            if (col < 0) {
                continue;   // column was renamed/removed in the sheet; skip rather than write to the wrong place
            }
            cells.put(col, values.getOrDefault(m.field(), ""));
            if ("pitchId".equals(m.field())) {
                keyColumn = col;
            }
        }
        if (keyColumn < 0) {
            throw new ApiException(ErrorCode.INTEGRATION_ERROR, "The pipeline sheet's Pitch ID column is missing.");
        }
        var begin = ops.begin(orgId, idempotencyKey, "SHEETS_UPSERT", null);
        if (begin.alreadySucceeded()) {
            return;
        }
        Integer rowIndex = sheets.findRow(conn, c.getSpreadsheetId(), c.getWorksheetTitle(), keyColumn,
                String.valueOf(p.getId()), c.getHeaderRow());
        if (rowIndex == null) {
            List<String> row = new ArrayList<>();
            int width = cells.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
            for (int i = 0; i < width; i++) {
                row.add(cells.getOrDefault(i, ""));
            }
            rowIndex = sheets.append(conn, c.getSpreadsheetId(), c.getWorksheetTitle(), row);
        } else {
            sheets.updateCells(conn, c.getSpreadsheetId(), c.getWorksheetTitle(), rowIndex, cells);
        }
        recordRow(orgId, pitchId, c, rowIndex);
        ops.succeed(idempotencyKey, c.getSpreadsheetId() + "#" + rowIndex);
        audit.recordAi(orgId, AuditAction.PIPELINE_UPDATED, "PITCH", pitchId,
                Map.of("row", rowIndex, "demo", providers.demo()));
    }

    void recordRow(Long orgId, Long pitchId, PipelineSheetConfig c, int rowIndex) {
        PipelineSheetRow r = rows.findByOrganizationIdAndPitchIdAndSpreadsheetIdAndWorksheetTitle(orgId, pitchId,
                c.getSpreadsheetId(), c.getWorksheetTitle()).orElseGet(PipelineSheetRow::new);
        r.setOrganizationId(orgId);
        r.setPitchId(pitchId);
        r.setSpreadsheetId(c.getSpreadsheetId());
        r.setWorksheetTitle(c.getWorksheetTitle());
        r.setRowIndex(rowIndex);
        r.setLastSyncedAt(clock.instant());
        rows.save(r);
    }

    private Map<String, String> values(Pitch p) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("pitchId", String.valueOf(p.getId()));
        v.put("companyName", nz(p.getCompanyName()));
        v.put("founderName", nz(p.getFounderName()));
        v.put("founderEmail", nz(p.getFounderEmail()));
        v.put("website", nz(p.getWebsite()));
        v.put("sector", nz(p.getSector()));
        v.put("stage", nz(p.getStage()));
        v.put("dealStage", nz(p.getDealStage()));
        v.put("status", nz(p.getStatus()));
        v.put("owner", p.getOwnerUserId() == null ? "" : users.findById(p.getOwnerUserId()).map(u -> u.getName()).orElse(""));
        v.put("amountRequested", nz(p.getAmountRequested()));
        v.put("oneLiner", nz(p.getOneLiner()));
        v.put("claimsSupported", p.getClaimsSupported() == null ? "" : String.valueOf(p.getClaimsSupported()));
        v.put("claimsContradicted", p.getClaimsContradicted() == null ? "" : String.valueOf(p.getClaimsContradicted()));
        v.put("claimsUnresolved", p.getClaimsUnresolved() == null ? "" : String.valueOf(p.getClaimsUnresolved()));
        v.put("riskLevel", nz(p.getRiskLevel()));
        v.put("briefUrl", p.getLatestBriefWorkflowId() == null ? "" : props.getFrontendUrl() + "/workflows/" + p.getLatestBriefWorkflowId());
        v.put("lastActivityAt", p.getLastActivityAt() == null ? "" : p.getLastActivityAt().toString());
        v.put("createdAt", p.getCreatedAt() == null ? "" : p.getCreatedAt().toString());
        // Neutralise formula injection for spreadsheets that might later be edited with USER_ENTERED.
        v.replaceAll((k, s) -> s.startsWith("=") || s.startsWith("+") || s.startsWith("@") ? "'" + s : s);
        return v;
    }

    private List<Mapping> mapping(PipelineSheetConfig c) {
        List<Mapping> out = new ArrayList<>();
        JsonNode arr = json.read(c.getFieldMappingJson());
        if (arr instanceof ArrayNode a) {
            a.forEach(m -> out.add(new Mapping(m.path("field").asText(), m.path("column").asText())));
        }
        return out;
    }

    private IntegrationConnection demoConnection(AuthPrincipal principal) {
        return integrations.usable(principal.orgId(), principal.userId(), Integration.SHEETS)
                .orElseThrow(() -> new ApiException(ErrorCode.INTEGRATION_NOT_CONNECTED, "Connect (demo) Sheets first."));
    }

    /** Accepts a spreadsheet ID or a docs.google.com/spreadsheets/d/<id>/... URL. */
    static String spreadsheetId(String input) {
        if (input == null || input.isBlank()) {
            throw ApiException.badRequest("spreadsheet is required (URL or ID).");
        }
        String s = input.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]+)").matcher(s);
        String id = m.find() ? m.group(1) : s;
        if (!id.matches("^[A-Za-z0-9_-]{10,200}$")) {
            throw ApiException.badRequest("That doesn't look like a Google Sheets URL or ID.");
        }
        return id;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
