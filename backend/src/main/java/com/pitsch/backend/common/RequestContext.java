package com.pitsch.backend.common;

import org.slf4j.MDC;

/** Per-request correlation data (stored in the SLF4J MDC so it appears in every log line). */
public final class RequestContext {

    public static final String REQUEST_ID = "requestId";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_AGENT = "userAgent";
    public static final String USER_ID = "userId";
    public static final String ORGANIZATION_ID = "organizationId";

    private RequestContext() { }

    public static String requestId() {
        return MDC.get(REQUEST_ID);
    }

    public static String clientIp() {
        return MDC.get(CLIENT_IP);
    }

    public static String userAgent() {
        return MDC.get(USER_AGENT);
    }
}
