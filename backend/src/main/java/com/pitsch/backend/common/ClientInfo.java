package com.pitsch.backend.common;

/** Client IP / user agent of the current request (from the MDC set by RequestIdFilter). */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo current() {
        return new ClientInfo(RequestContext.clientIp(), RequestContext.userAgent());
    }
}
