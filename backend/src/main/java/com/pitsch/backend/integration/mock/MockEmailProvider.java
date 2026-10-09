package com.pitsch.backend.integration.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.provider.EmailProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PITSCH_MODE=demo only. Nothing leaves Pitsch: "sent" emails are kept in memory and every result is flagged as demo
 * so the UI and audit log can say so. Never registered in production or development mode.
 */
public class MockEmailProvider implements EmailProvider {

    private static final Logger log = LoggerFactory.getLogger(MockEmailProvider.class);
    private final Map<String, SentEmail> sent = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "demo-email";
    }

    @Override
    public boolean isDemo() {
        return true;
    }

    @Override
    public SentEmail send(IntegrationConnection connection, OutgoingEmail email, String idempotencyKey) {
        return sent.computeIfAbsent(idempotencyKey, k -> {
            log.info("[demo] email to {} recorded (not sent)", email.to());
            return new SentEmail("demo-" + UUID.randomUUID(), email.threadId() == null ? "demo-thread" : email.threadId());
        });
    }

    @Override
    public SentEmail findSentByIdempotencyKey(IntegrationConnection connection, String idempotencyKey) {
        return sent.get(idempotencyKey);
    }

    @Override
    public void modifyLabels(IntegrationConnection connection, String messageId, List<String> add, List<String> remove) {
        // Demo emails are not in a real mailbox; labels are kept on the Pitsch email record only.
    }

    @Override
    public FetchedMessage fetch(IntegrationConnection connection, String messageId) {
        throw new IntegrationException("Demo mode has no mailbox to read", false, false, 0);
    }

    @Override
    public byte[] fetchAttachment(IntegrationConnection connection, String messageId, String attachmentId) {
        throw new IntegrationException("Demo mode has no mailbox to read", false, false, 0);
    }

    @Override
    public ChangeBatch changesSince(IntegrationConnection connection, String cursor, int maxMessages) {
        return new ChangeBatch(List.of(), cursor, false);
    }

    @Override
    public ChangeBatch recent(IntegrationConnection connection, String query, int maxMessages) {
        return new ChangeBatch(List.of(), "0", false);
    }
}
