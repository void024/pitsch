package com.pitsch.backend.ai;

/** The eight agents of the Pitsch AI service: endpoint path + the name used in agent_executions. */
public enum Agent {
    EMAIL_CLASSIFIER("email-classifier", "Email classification"),
    DOCUMENT_AGENT("document", "Pitch deck processing"),
    RESEARCH_AGENT("research", "Web research"),
    VERIFICATION_AGENT("verification", "Claim verification"),
    ANALYSIS_AGENT("analysis", "Research brief"),
    CALENDAR_AGENT("calendar", "Meeting slots"),
    EMAIL_RESPONSE_AGENT("email-response", "Email draft"),
    ACTION_AGENT("action", "Gmail / Calendar / Sheets actions");

    private final String path;
    private final String label;

    Agent(String path, String label) {
        this.path = path;
        this.label = label;
    }

    public String path() {
        return path;
    }

    public String label() {
        return label;
    }
}
