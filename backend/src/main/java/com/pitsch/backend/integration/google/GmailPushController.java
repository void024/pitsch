package com.pitsch.backend.integration.google;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.pitsch.backend.auth.PublicEndpoint;
import com.pitsch.backend.common.ApiException;
import com.pitsch.backend.common.ErrorCode;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.integration.InboundEventService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gmail push notifications via Cloud Pub/Sub authenticated push. The request must carry a Google-signed OIDC token
 * for the configured service account and audience; the body only says "mailbox X changed", so it triggers a sync
 * job (which reads Gmail with the stored credentials) and returns immediately.
 */
@RestController
public class GmailPushController {

    private final GoogleIdTokenVerifier verifier;
    private final GmailSyncService sync;
    private final InboundEventService inbound;
    private final PitschProperties props;
    private final Json json;

    public GmailPushController(GoogleIdTokenVerifier verifier, GmailSyncService sync, InboundEventService inbound,
                               PitschProperties props, Json json) {
        this.verifier = verifier;
        this.sync = sync;
        this.inbound = inbound;
        this.props = props;
        this.json = json;
    }

    @PublicEndpoint
    @PostMapping("/api/v1/webhooks/gmail")
    public ResponseEntity<Void> push(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody JsonNode body) {
        PitschProperties.Google g = props.getGoogle();
        if (!g.isPushConfigured()) {
            throw ApiException.notFound("Endpoint");
        }
        String token = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : null;
        if (verifier.verify(token, g.getPubsubAudience(), g.getPubsubServiceAccount()) == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, "Invalid push token");
        }
        JsonNode message = body.path("message");
        String messageId = Json.text(message, "messageId");
        if (messageId == null || !inbound.firstDelivery("GMAIL_PUSH", messageId)) {
            return ResponseEntity.noContent().build();
        }
        String data = Json.text(message, "data");
        if (data != null) {
            JsonNode payload = json.readSafely(new String(Base64.getDecoder().decode(data), StandardCharsets.UTF_8));
            String email = Json.text(payload, "emailAddress");
            String historyId = Json.text(payload, "historyId");
            if (email != null && historyId != null) {
                sync.onPush(email, historyId);
            }
        }
        return ResponseEntity.noContent().build();
    }
}
