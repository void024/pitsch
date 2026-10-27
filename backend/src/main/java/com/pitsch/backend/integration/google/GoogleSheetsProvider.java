package com.pitsch.backend.integration.google;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.provider.SpreadsheetProvider;
import org.springframework.stereotype.Component;

/** Google Sheets API v4 implementation. Values are written RAW so cell content is never interpreted as a formula. */
@Component
public class GoogleSheetsProvider implements SpreadsheetProvider {

    static final String API = "https://sheets.googleapis.com/v4/spreadsheets";
    private static final Pattern ROW = Pattern.compile("![A-Z]+(\\d+)");

    private final GoogleApiClient google;
    private final Json json;

    public GoogleSheetsProvider(GoogleApiClient google, Json json) {
        this.google = google;
        this.json = json;
    }

    @Override
    public String name() {
        return "google-sheets";
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public SpreadsheetInfo describe(IntegrationConnection c, String spreadsheetId) {
        JsonNode s = google.get(c, "sheets.get", API + "/{id}?fields=spreadsheetId,properties.title,sheets.properties.title", spreadsheetId);
        List<String> sheets = new ArrayList<>();
        s.path("sheets").forEach(x -> sheets.add(x.path("properties").path("title").asText()));
        return new SpreadsheetInfo(spreadsheetId, s.path("properties").path("title").asText(), sheets);
    }

    @Override
    public List<String> header(IntegrationConnection c, String spreadsheetId, String worksheet, int headerRow) {
        JsonNode v = google.get(c, "sheets.values.get", API + "/{id}/values/{range}", spreadsheetId,
                a1(worksheet, "A" + headerRow + ":ZZ" + headerRow));
        List<String> out = new ArrayList<>();
        v.path("values").path(0).forEach(cell -> out.add(cell.asText().trim()));
        return out;
    }

    @Override
    public Integer findRow(IntegrationConnection c, String spreadsheetId, String worksheet, int keyColumnIndex,
                           String key, int headerRow) {
        String col = column(keyColumnIndex);
        JsonNode v = google.get(c, "sheets.values.get", API + "/{id}/values/{range}", spreadsheetId,
                a1(worksheet, col + ":" + col));
        JsonNode rows = v.path("values");
        for (int i = headerRow; i < rows.size(); i++) {
            if (key.equals(rows.path(i).path(0).asText())) {
                return i + 1;
            }
        }
        return null;
    }

    @Override
    public int append(IntegrationConnection c, String spreadsheetId, String worksheet, List<String> values) {
        JsonNode res = google.post(c, "sheets.append",
                API + "/{id}/values/{range}:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                body(values), spreadsheetId, a1(worksheet, "A1"));
        String range = res.path("updates").path("updatedRange").asText("");
        Matcher m = ROW.matcher(range);
        if (!m.find()) {
            throw new IntegrationException("Google Sheets did not report the appended row", false, false, 0);
        }
        return Integer.parseInt(m.group(1));
    }

    @Override
    public void update(IntegrationConnection c, String spreadsheetId, String worksheet, int rowIndex, List<String> values) {
        google.put(c, "sheets.update", API + "/{id}/values/{range}?valueInputOption=RAW", body(values), spreadsheetId,
                a1(worksheet, "A" + rowIndex + ":" + column(values.size() - 1) + rowIndex));
    }

    @Override
    public void updateCells(IntegrationConnection c, String spreadsheetId, String worksheet, int rowIndex,
                            java.util.Map<Integer, String> cells) {
        if (cells.isEmpty()) {
            return;
        }
        ObjectNode body = json.obj();
        body.put("valueInputOption", "RAW");
        ArrayNode data = body.putArray("data");
        cells.forEach((col, value) -> {
            ObjectNode range = data.addObject();
            range.put("range", a1(worksheet, column(col) + rowIndex));
            range.put("majorDimension", "ROWS");
            range.putArray("values").addArray().add(value == null ? "" : value);
        });
        google.post(c, "sheets.batchUpdate", API + "/{id}/values:batchUpdate", body, spreadsheetId);
    }

    private ObjectNode body(List<String> values) {
        ObjectNode body = json.obj();
        body.put("majorDimension", "ROWS");
        ArrayNode row = body.putArray("values").addArray();
        values.forEach(v -> row.add(v == null ? "" : v));
        return body;
    }

    static String a1(String worksheet, String range) {
        return "'" + worksheet.replace("'", "''") + "'!" + range;
    }

    /** 0 -> A, 25 -> Z, 26 -> AA ... */
    static String column(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index + 1;
        while (n > 0) {
            int rem = (n - 1) % 26;
            sb.insert(0, (char) ('A' + rem));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }
}
