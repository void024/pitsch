package com.pitsch.backend.integration.provider;

import java.time.Instant;
import java.util.List;

import com.pitsch.backend.integration.IntegrationConnection;

/**
 * The investor's mailbox. Implementations: GmailProvider (production) and MockEmailProvider (PITSCH_MODE=demo only).
 * All methods are called by the backend after authorization/approval checks — never by the AI service.
 */
public interface EmailProvider {

    record OutgoingEmail(String to, String subject, String body, String threadId, String inReplyToMessageId) { }

    record SentEmail(String messageId, String threadId) { }

    record Attachment(String filename, String mimeType, long sizeBytes, String attachmentId) { }

    record FetchedMessage(String id, String threadId, String rfcMessageId, String inReplyTo, String fromEmail,
                          String fromName, List<String> to, String subject, String textBody, Instant receivedAt,
                          List<String> labelIds, List<Attachment> attachments) { }

    /** Message IDs changed since {@code cursor} (a Gmail history ID) and the cursor to use next time. */
    record ChangeBatch(List<String> messageIds, String nextCursor, boolean fullResyncNeeded) { }

    String name();

    boolean isDemo();

    /**
     * Sends a message. {@code idempotencyKey} is embedded as the RFC 5322 Message-ID so a retried send can be detected
     * with {@link #findSentByIdempotencyKey}.
     */
    SentEmail send(IntegrationConnection connection, OutgoingEmail email, String idempotencyKey);

    /** Looks for a message previously sent with this idempotency key (crash-recovery for ambiguous sends). */
    SentEmail findSentByIdempotencyKey(IntegrationConnection connection, String idempotencyKey);

    void modifyLabels(IntegrationConnection connection, String messageId, List<String> addLabelNames,
                      List<String> removeLabelNames);

    FetchedMessage fetch(IntegrationConnection connection, String messageId);

    byte[] fetchAttachment(IntegrationConnection connection, String messageId, String attachmentId);

    ChangeBatch changesSince(IntegrationConnection connection, String cursor, int maxMessages);

    /** IDs of recent messages matching the ingest query (initial sync / full resync) plus a fresh cursor. */
    ChangeBatch recent(IntegrationConnection connection, String query, int maxMessages);
}
