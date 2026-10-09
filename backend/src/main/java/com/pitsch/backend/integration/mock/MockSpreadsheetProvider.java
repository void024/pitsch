package com.pitsch.backend.integration.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.provider.SpreadsheetProvider;

/** PITSCH_MODE=demo only: an in-memory sheet with a fixed demo header. */
public class MockSpreadsheetProvider implements SpreadsheetProvider {

    public static final List<String> DEMO_HEADER = List.of("Pitch ID", "Company", "Stage", "Status", "Owner", "Sector",
            "Founder", "Founder Email", "Ask", "Supported claims", "Contradicted claims", "Brief", "Last updated");

    private final Map<String, List<List<String>>> sheets = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "demo-sheets";
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public SpreadsheetInfo describe(IntegrationConnection connection, String spreadsheetId) {
        return new SpreadsheetInfo(spreadsheetId, "Demo pipeline", List.of("Pipeline"));
    }

    @Override
    public List<String> header(IntegrationConnection connection, String spreadsheetId, String worksheet, int headerRow) {
        return DEMO_HEADER;
    }

    @Override
    public synchronized Integer findRow(IntegrationConnection connection, String spreadsheetId, String worksheet,
                                        int keyColumnIndex, String key, int headerRow) {
        List<List<String>> rows = rows(spreadsheetId, worksheet);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).size() > keyColumnIndex && key.equals(rows.get(i).get(keyColumnIndex))) {
                return i + headerRow + 1;
            }
        }
        return null;
    }

    @Override
    public synchronized int append(IntegrationConnection connection, String spreadsheetId, String worksheet, List<String> values) {
        List<List<String>> rows = rows(spreadsheetId, worksheet);
        rows.add(new ArrayList<>(values));
        return rows.size() + 1;
    }

    @Override
    public synchronized void update(IntegrationConnection connection, String spreadsheetId, String worksheet, int rowIndex,
                                    List<String> values) {
        List<List<String>> rows = rows(spreadsheetId, worksheet);
        int i = rowIndex - 2;
        if (i >= 0 && i < rows.size()) {
            rows.set(i, new ArrayList<>(values));
        }
    }

    @Override
    public synchronized void updateCells(IntegrationConnection connection, String spreadsheetId, String worksheet,
                                         int rowIndex, Map<Integer, String> cells) {
        List<List<String>> rows = rows(spreadsheetId, worksheet);
        int i = rowIndex - 2;
        if (i < 0 || i >= rows.size()) {
            return;
        }
        List<String> row = rows.get(i);
        cells.forEach((col, value) -> {
            while (row.size() <= col) {
                row.add("");
            }
            row.set(col, value);
        });
    }

    private List<List<String>> rows(String spreadsheetId, String worksheet) {
        return sheets.computeIfAbsent(spreadsheetId + "/" + worksheet, k -> new ArrayList<>());
    }
}
