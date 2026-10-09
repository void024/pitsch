package com.pitsch.backend.integration.google;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pitsch.backend.common.Hashing;
import com.pitsch.backend.common.Json;
import com.pitsch.backend.integration.IntegrationConnection;
import com.pitsch.backend.integration.IntegrationException;
import com.pitsch.backend.integration.provider.EmailProvider;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/** Gmail REST API v1 implementation of {@link EmailProvider}. */
@Component
public class GmailProvider implements EmailProvider {

    static final String API = "https://gmail.googleapis.com/gmail/v1/users/me";
    private static final Base64.Decoder B64URL = Base64.getUrlDecoder();

    private final GoogleApiClient google;
    private final Json json;
    /** connectionId -> (label name -> label id); labels rarely change. */
    private final Map<Long, Map<String, String>> labelCache = new ConcurrentHashMap<>();

    public GmailProvider(GoogleApiClient google, Json json) {
        this.google = google;
        this.json = json;
    }

    @Override
    public String name() {
        return "gmail";
    }

    @Override
    public boolean isDemo() {
        return false;
    }

    @Override
    public SentEmail send(IntegrationConnection c, OutgoingEmail email, String idempotencyKey) {
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(mime(c.getAccountEmail(), email, messageId(idempotencyKey)));
        ObjectNode body = json.obj();
        body.put("raw", raw);
        if (email.threadId() != null && !email.threadId().isBlank()) {
            body.put("threadId", email.threadId());
        }
        JsonNode sent = google.post(c, "gmail.send", API + "/messages/send", body);
        return new SentEmail(Json.text(sent, "id"), Json.text(sent, "threadId"));
    }

    @Override
    public SentEmail findSentByIdempotencyKey(IntegrationConnection c, String idempotencyKey) {
        JsonNode list = google.get(c, "gmail.search", API + "/messages?q={q}&maxResults=1",
                "rfc822msgid:" + messageId(idempotencyKey));
        JsonNode first = list == null ? null : list.path("messages").path(0);
        return first == null || first.isMissingNode() ? null : new SentEmail(Json.text(first, "id"), Json.text(first, "threadId"));
    }

    @Override
    public void modifyLabels(IntegrationConnection c, String messageId, List<String> add, List<String> remove) {
        ObjectNode body = json.obj();
        var addIds = body.putArray("addLabelIds");
        for (String name : add) {
            addIds.add(labelId(c, name, true));
        }
        var removeIds = body.putArray("removeLabelIds");
        for (String name : remove) {
            String id = labelId(c, name, false);
            if (id != null) {
                removeIds.add(id);
            }
        }
        google.post(c, "gmail.modify", API + "/messages/{id}/modify", body, messageId);
    }

    @Override
    public FetchedMessage fetch(IntegrationConnection c, String id) {
        JsonNode m = google.get(c, "gmail.get", API + "/messages/{id}?format=full", id);
        JsonNode payload = m.path("payload");
        Map<String, String> headers = headers(payload);
        InternetAddress from = firstAddress(headers.get("from"));
        List<String> to = new ArrayList<>();
        for (InternetAddress a : addresses(headers.get("to"))) {
            to.add(a.getAddress().toLowerCase());
        }
        List<Attachment> attachments = new ArrayList<>();
        collectAttachments(payload, attachments);
        String text = textBody(payload);
        List<String> labels = new ArrayList<>();
        m.path("labelIds").forEach(l -> labels.add(l.asText()));
        long internalDate = m.path("internalDate").asLong(System.currentTimeMillis());
        return new FetchedMessage(Json.text(m, "id"), Json.text(m, "threadId"), headers.get("message-id"),
                headers.get("in-reply-to"), from == null ? null : from.getAddress().toLowerCase(),
                from == null ? null : from.getPersonal(), to, headers.getOrDefault("subject", ""), text,
                Instant.ofEpochMilli(internalDate), labels, attachments);
    }

    @Override
    public byte[] fetchAttachment(IntegrationConnection c, String messageId, String attachmentId) {
        JsonNode a = google.get(c, "gmail.attachment", API + "/messages/{m}/attachments/{a}", messageId, attachmentId);
        String data = Json.text(a, "data");
        return data == null ? new byte[0] : B64URL.decode(data);
    }

    @Override
    public ChangeBatch changesSince(IntegrationConnection c, String cursor, int maxMessages) {
        List<String> ids = new ArrayList<>();
        String pageToken = null;
        String next = cursor;
        try {
            do {
                JsonNode page = pageToken == null
                        ? google.get(c, "gmail.history", API + "/history?startHistoryId={h}&historyTypes=messageAdded&labelId=INBOX&maxResults=500", cursor)
                        : google.get(c, "gmail.history", API + "/history?startHistoryId={h}&historyTypes=messageAdded&labelId=INBOX&maxResults=500&pageToken={p}", cursor, pageToken);
                for (JsonNode h : page.path("history")) {
                    for (JsonNode added : h.path("messagesAdded")) {
                        String id = Json.text(added.path("message"), "id");
                        if (id != null && !ids.contains(id) && ids.size() < maxMessages) {
                            ids.add(id);
                        }
                    }
                }
                if (Json.text(page, "historyId") != null) {
                    next = Json.text(page, "historyId");
                }
                pageToken = Json.text(page, "nextPageToken");
            } while (pageToken != null && ids.size() < maxMessages);
        } catch (IntegrationException e) {
            if (e.getProviderStatus() == 404) {
                return new ChangeBatch(List.of(), null, true);   // history expired: caller performs a bounded resync
            }
            throw e;
        }
        return new ChangeBatch(ids, next, false);
    }

    @Override
    public ChangeBatch recent(IntegrationConnection c, String query, int maxMessages) {
        JsonNode profile = google.get(c, "gmail.profile", API + "/profile");
        JsonNode list = google.get(c, "gmail.list", API + "/messages?q={q}&maxResults={n}", query, Math.min(maxMessages, 100));
        List<String> ids = new ArrayList<>();
        list.path("messages").forEach(m -> ids.add(m.path("id").asText()));
        return new ChangeBatch(ids, Json.text(profile, "historyId"), false);
    }

    /** Registers Gmail push notifications to the configured Pub/Sub topic; returns the watch expiry. */
    public Instant watch(IntegrationConnection c, String topic) {
        ObjectNode body = json.obj();
        body.put("topicName", topic);
        body.putArray("labelIds").add("INBOX");
        body.put("labelFilterBehavior", "INCLUDE");
        JsonNode res = google.post(c, "gmail.watch", API + "/watch", body);
        return Instant.ofEpochMilli(res.path("expiration").asLong(System.currentTimeMillis()));
    }

    public void stopWatch(IntegrationConnection c) {
        google.post(c, "gmail.stop", API + "/stop", json.obj());
    }

    // ------------------------------------------------------------------ helpers

    /** Deterministic RFC 5322 Message-ID for an idempotency key (lets a retried send be detected). */
    static String messageId(String idempotencyKey) {
        return "<pitsch." + Hashing.sha256Hex(idempotencyKey).substring(0, 40) + "@pitsch.app>";
    }

    static byte[] mime(String from, OutgoingEmail email, String messageId) {
        try {
            MimeMessage msg = new MimeMessage(Session.getInstance(new Properties())) {
                @Override
                protected void updateMessageID() throws MessagingException {
                    setHeader("Message-ID", messageId);
                }
            };
            if (from != null) {
                msg.setFrom(new InternetAddress(from));
            }
            msg.setRecipients(jakarta.mail.Message.RecipientType.TO, InternetAddress.parse(email.to(), true));
            msg.setSubject(singleLine(email.subject()), "UTF-8");
            if (email.inReplyToMessageId() != null && !email.inReplyToMessageId().isBlank()) {
                msg.setHeader("In-Reply-To", singleLine(email.inReplyToMessageId()));
                msg.setHeader("References", singleLine(email.inReplyToMessageId()));
            }
            msg.setText(email.body(), "UTF-8");
            msg.saveChanges();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            msg.writeTo(out);
            return out.toByteArray();
        } catch (MessagingException | IOException e) {
            throw new IntegrationException("Could not build the email message", false, false, 0);
        }
    }

    private static String singleLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    }

    private String labelId(IntegrationConnection c, String name, boolean create) {
        Map<String, String> labels = labelCache.computeIfAbsent(c.getId(), k -> loadLabels(c));
        String id = labels.get(name);
        if (id == null) {
            labels.putAll(loadLabels(c));
            id = labels.get(name);
        }
        if (id == null && create) {
            ObjectNode body = json.obj();
            body.put("name", name);
            body.put("labelListVisibility", "labelShow");
            body.put("messageListVisibility", "show");
            id = Json.text(google.post(c, "gmail.label.create", API + "/labels", body), "id");
            labels.put(name, id);
        }
        return id;
    }

    private Map<String, String> loadLabels(IntegrationConnection c) {
        Map<String, String> out = new ConcurrentHashMap<>();
        google.get(c, "gmail.labels", API + "/labels").path("labels")
                .forEach(l -> out.put(l.path("name").asText(), l.path("id").asText()));
        return out;
    }

    private static Map<String, String> headers(JsonNode payload) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode h : payload.path("headers")) {
            out.putIfAbsent(h.path("name").asText().toLowerCase(), h.path("value").asText());
        }
        return out;
    }

    private static InternetAddress firstAddress(String header) {
        List<InternetAddress> list = addresses(header);
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<InternetAddress> addresses(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        try {
            return List.of(InternetAddress.parse(header, false));
        } catch (AddressException e) {
            return List.of();
        }
    }

    private static void collectAttachments(JsonNode part, List<Attachment> out) {
        String filename = part.path("filename").asText("");
        String attachmentId = Json.text(part.path("body"), "attachmentId");
        if (!filename.isBlank() && attachmentId != null) {
            out.add(new Attachment(filename, part.path("mimeType").asText("application/octet-stream"),
                    part.path("body").path("size").asLong(0), attachmentId));
        }
        for (JsonNode child : part.path("parts")) {
            collectAttachments(child, out);
        }
    }

    /** Prefers text/plain; falls back to tag-stripped text/html. Never renders HTML. */
    static String textBody(JsonNode payload) {
        String plain = findPart(payload, "text/plain");
        if (plain != null) {
            return plain;
        }
        String html = findPart(payload, "text/html");
        if (html == null) {
            return "";
        }
        String noScripts = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ");
        String text = noScripts.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", " ");
        return HtmlUtils.htmlUnescape(text).replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

    private static String findPart(JsonNode part, String mimeType) {
        if (mimeType.equalsIgnoreCase(part.path("mimeType").asText()) && part.path("filename").asText("").isBlank()) {
            String data = Json.text(part.path("body"), "data");
            if (data != null) {
                return new String(B64URL.decode(data), StandardCharsets.UTF_8);
            }
        }
        for (JsonNode child : part.path("parts")) {
            String found = findPart(child, mimeType);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
