package com.pitsch.backend.integration.google;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.pitsch.backend.config.PitschProperties;
import com.pitsch.backend.email.Email;
import com.pitsch.backend.email.EmailIngestionService;
import com.pitsch.backend.email.EmailRepository;
import com.pitsch.backend.files.FileValidator;
import com.pitsch.backend.integration.Integration;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationConnectionRepository;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.ProviderRegistry;
import com.pitsch.backend.integration.provider.EmailProvider;
import com.pitsch.backend.jobs.JobQueue;
import com.pitsch.backend.jobs.JobType;
import com.pitsch.backend.notification.NotificationService;
import com.pitsch.backend.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Gmail ingestion. Push (Pub/Sub watch) triggers a sync immediately; a periodic sync every
 * GMAIL_SYNC_INTERVAL_MINUTES (default 10) is the safety net — never aggressive polling. Each message is ingested by
 * its own job keyed by the Gmail message ID, so it is processed exactly once even across instances.
 */
@Service
public class GmailSyncService {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);
    private static final Set<String> SKIP_LABELS = Set.of("SENT", "DRAFT", "SPAM", "TRASH", "CATEGORY_PROMOTIONS",
            "CATEGORY_SOCIAL", "CATEGORY_FORUMS");
    private static final int MAX_PER_SYNC = 200;

    private final IntegrationConnectionRepository connections;
    private final ProviderRegistry providers;
    private final GmailProvider gmail;
    private final EmailIngestionService ingestion;
    private final EmailRepository emails;
    private final JobQueue jobs;
    private final NotificationService notifications;
    private final PitschProperties props;
    private final Clock clock;

    public GmailSyncService(IntegrationConnectionRepository connections, ProviderRegistry providers, GmailProvider gmail,
                            EmailIngestionService ingestion, EmailRepository emails, JobQueue jobs,
                            NotificationService notifications, PitschProperties props, Clock clock) {
        this.connections = connections;
        this.providers = providers;
        this.gmail = gmail;
        this.ingestion = ingestion;
        this.emails = emails;
        this.jobs = jobs;
        this.notifications = notifications;
        this.props = props;
        this.clock = clock;
    }

    /** Lists new message IDs since the stored history cursor and queues one ingest job per message. */
    public void sync(Long connectionId) {
        IntegrationConnection c = connections.findById(connectionId).filter(x -> x.isUsableFor(Integration.GMAIL)).orElse(null);
        if (c == null || providers.demo()) {
            return;
        }
        EmailProvider provider = providers.email();
        try {
            EmailProvider.ChangeBatch batch;
            if (c.getGmailHistoryId() == null) {
                batch = provider.recent(c, props.getGoogle().getGmailIngestQuery() + " newer_than:2d", 25);
            } else {
                batch = provider.changesSince(c, c.getGmailHistoryId(), MAX_PER_SYNC);
                if (batch.fullResyncNeeded()) {
                    batch = provider.recent(c, props.getGoogle().getGmailIngestQuery() + " newer_than:3d", 50);
                }
            }
            for (String messageId : batch.messageIds()) {
                jobs.enqueue(JobType.GMAIL_INGEST_MESSAGE, c.getOrganizationId(), null,
                        Map.of("connectionId", c.getId(), "messageId", messageId),
                        "gmail-msg:" + c.getOrganizationId() + ":" + messageId, Duration.ZERO);
            }
            String next = batch.nextCursor();
            connections.findById(connectionId).ifPresent(fresh -> {
                if (next != null) {
                    fresh.setGmailHistoryId(next);
                }
                fresh.setLastSyncAt(clock.instant());
                fresh.setLastSyncStatus("OK");
                fresh.setLastError(null);
                connections.save(fresh);
            });
        } catch (IntegrationException e) {
            connections.findById(connectionId).ifPresent(fresh -> {
                fresh.setLastSyncAt(clock.instant());
                fresh.setLastSyncStatus("ERROR");
                fresh.setLastError(e.getMessage());
                connections.save(fresh);
            });
            if (e.isNeedsReconnect()) {
                notifications.notify(c.getOrganizationId(), c.getUserId(), NotificationType.INTEGRATION_ERROR,
                        "Reconnect Gmail", "Pitsch lost access to " + c.getAccountEmail() + ". Reconnect it in Integrations.",
                        null, "gmail-reconnect:" + c.getId() + ":" + clock.instant().toString().substring(0, 10));
                return;
            }
            throw e;
        }
    }

    /** Fetches one message and hands it to the shared ingestion path. */
    public void ingest(Long connectionId, String messageId) {
        IntegrationConnection c = connections.findById(connectionId).filter(x -> x.isUsableFor(Integration.GMAIL)).orElse(null);
        if (c == null) {
            return;
        }
        if (emails.existsByOrganizationIdAndDedupeKey(c.getOrganizationId(), "gmail:" + messageId)) {
            return;
        }
        EmailProvider provider = providers.email();
        EmailProvider.FetchedMessage m = provider.fetch(c, messageId);
        if (m.labelIds().stream().anyMatch(SKIP_LABELS::contains)
                || (m.fromEmail() != null && m.fromEmail().equalsIgnoreCase(c.getAccountEmail()))) {
            return;   // outgoing, spam or bulk mail
        }
        List<EmailIngestionService.IncomingFile> files = new ArrayList<>();
        for (EmailProvider.Attachment a : m.attachments()) {
            if (files.size() >= props.getUploads().getMaxFiles() || a.sizeBytes() > props.getUploads().maxFileBytes()
                    || !supported(a.filename(), a.mimeType())) {
                continue;
            }
            files.add(new EmailIngestionService.IncomingFile(a.filename(), provider.fetchAttachment(c, messageId, a.attachmentId())));
        }
        ingestion.ingest(c.getOrganizationId(), c.getUserId(), new EmailIngestionService.IngestCommand(Email.Source.GMAIL,
                m.fromEmail(), m.fromName(), m.subject(), m.textBody(), m.threadId(), m.rfcMessageId(), m.inReplyTo(),
                m.id(), c.getId(), m.receivedAt(), m.to(), files, true));
    }

    public void renewWatch(Long connectionId) {
        if (!props.getGoogle().isPushConfigured() || providers.demo()) {
            return;
        }
        IntegrationConnection c = connections.findById(connectionId).filter(x -> x.isUsableFor(Integration.GMAIL)).orElse(null);
        if (c == null) {
            return;
        }
        Instant expiry = gmail.watch(c, props.getGoogle().getPubsubTopic());
        connections.findById(connectionId).ifPresent(fresh -> {
            fresh.setGmailWatchExpiresAt(expiry);
            connections.save(fresh);
        });
    }

    /** Safety-net sync. The dedupe key (connection + time slot) makes this safe when several instances run it. */
    @Scheduled(fixedDelayString = "#{${pitsch.google.gmail-sync-interval-minutes:10} * 60000}", initialDelay = 60_000)
    public void scheduleSyncs() {
        if (providers.demo() || !props.getJobs().isEnabled()) {
            return;
        }
        long slot = clock.millis() / (Math.max(1, props.getGoogle().getGmailSyncIntervalMinutes()) * 60_000L);
        for (IntegrationConnection c : connections.findByStatus(IntegrationConnection.Status.CONNECTED.name())) {
            if (c.isUsableFor(Integration.GMAIL)) {
                jobs.enqueue(JobType.GMAIL_SYNC, c.getOrganizationId(), null, Map.of("connectionId", c.getId()),
                        "gmail-sync:" + c.getId() + ":" + slot, Duration.ZERO);
            }
        }
    }

    /** Gmail watches expire after 7 days; renew daily those expiring within 2 days. */
    @Scheduled(cron = "0 7 2 * * *")
    public void scheduleWatchRenewals() {
        if (providers.demo() || !props.getGoogle().isPushConfigured()) {
            return;
        }
        String day = clock.instant().toString().substring(0, 10);
        Instant soon = clock.instant().plus(Duration.ofDays(2));
        for (IntegrationConnection c : connections.findByStatus(IntegrationConnection.Status.CONNECTED.name())) {
            if (c.isUsableFor(Integration.GMAIL) && (c.getGmailWatchExpiresAt() == null || c.getGmailWatchExpiresAt().isBefore(soon))) {
                jobs.enqueue(JobType.GMAIL_WATCH, c.getOrganizationId(), null, Map.of("connectionId", c.getId()),
                        "gmail-watch:" + c.getId() + ":" + day, Duration.ZERO);
            }
        }
    }

    /** Called by the push webhook: sync every connection for that mailbox. */
    public void onPush(String emailAddress, String historyId) {
        for (IntegrationConnection c : connections.findByProviderAndAccountEmailIgnoreCase("GOOGLE", emailAddress)) {
            if (c.isUsableFor(Integration.GMAIL)) {
                jobs.enqueue(JobType.GMAIL_SYNC, c.getOrganizationId(), null, Map.of("connectionId", c.getId()),
                        "gmail-push:" + c.getId() + ":" + historyId, Duration.ZERO);
            }
        }
    }

    static boolean supported(String filename, String mimeType) {
        String n = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String t = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        return n.endsWith(".pdf") || n.endsWith(".pptx") || n.endsWith(".txt") || n.endsWith(".md")
                || t.equals(FileValidator.Kind.PDF.contentType) || t.equals(FileValidator.Kind.PPTX.contentType);
    }

}
