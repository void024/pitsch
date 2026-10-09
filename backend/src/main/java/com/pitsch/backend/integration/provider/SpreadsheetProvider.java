package com.pitsch.backend.integration.provider;

import java.util.List;

import com.pitsch.backend.integration.IntegrationConnection;

/** The workspace's deal-pipeline spreadsheet. Column layout comes from the workspace's field mapping. */
public interface SpreadsheetProvider {

    record SpreadsheetInfo(String spreadsheetId, String title, List<String> worksheets) { }

    String name();

    boolean isDemo();

    SpreadsheetInfo describe(IntegrationConnection connection, String spreadsheetId);

    List<String> header(IntegrationConnection connection, String spreadsheetId, String worksheet, int headerRow);

    /** 1-based row index of the first row (below the header) whose {@code keyColumn} equals {@code key}, or null. */
    Integer findRow(IntegrationConnection connection, String spreadsheetId, String worksheet, int keyColumnIndex,
                    String key, int headerRow);

    /** Appends a row and returns its 1-based index. */
    int append(IntegrationConnection connection, String spreadsheetId, String worksheet, List<String> values);

    void update(IntegrationConnection connection, String spreadsheetId, String worksheet, int rowIndex, List<String> values);

    /** Writes only the given cells of one row (column index -> value), leaving the user's other columns untouched. */
    void updateCells(IntegrationConnection connection, String spreadsheetId, String worksheet, int rowIndex,
                     java.util.Map<Integer, String> cells);
}
