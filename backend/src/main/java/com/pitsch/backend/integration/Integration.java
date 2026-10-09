package com.pitsch.backend.integration;

import java.util.List;

/** The three Google integrations and the least-privilege OAuth scopes each one needs. */
public enum Integration {
    GMAIL(List.of("https://www.googleapis.com/auth/gmail.modify")),
    CALENDAR(List.of("https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.freebusy",
            "https://www.googleapis.com/auth/calendar.calendarlist.readonly")),
    SHEETS(List.of("https://www.googleapis.com/auth/spreadsheets"));

    /** Requested with every grant so the connected account's email address is known. */
    public static final List<String> IDENTITY_SCOPES = List.of("openid", "email");

    private final List<String> scopes;

    Integration(List<String> scopes) {
        this.scopes = scopes;
    }

    public List<String> scopes() {
        return scopes;
    }
}
