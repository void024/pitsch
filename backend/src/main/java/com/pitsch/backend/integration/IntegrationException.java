package com.pitsch.backend.integration;

import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;

/** A provider call failed. {@code retryable} drives job retries; {@code needsReconnect} flags revoked grants. */
public class IntegrationException extends ApiException {

    private final boolean retryable;
    private final boolean needsReconnect;
    private final int providerStatus;

    public IntegrationException(String message, boolean retryable, boolean needsReconnect, int providerStatus) {
        super(needsReconnect ? ErrorCode.INTEGRATION_NOT_CONNECTED : ErrorCode.INTEGRATION_ERROR, message);
        this.retryable = retryable;
        this.needsReconnect = needsReconnect;
        this.providerStatus = providerStatus;
    }

    public boolean isRetryable() { return retryable; }
    public boolean isNeedsReconnect() { return needsReconnect; }
    public int getProviderStatus() { return providerStatus; }
}
