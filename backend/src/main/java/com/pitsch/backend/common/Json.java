package com.pitsch.backend.common;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

/** Small null-safe helpers around Jackson for agent payloads (stored as JSON text in the database). */
@Component
public class Json {

    private final ObjectMapper mapper;

    public Json(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public ObjectNode obj() {
        return mapper.createObjectNode();
    }

    public ArrayNode arr() {
        return mapper.createArrayNode();
    }

    public JsonNode read(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored JSON is corrupt", e);
        }
    }

    /** Like {@link #read} but returns null instead of failing on malformed input (for third-party responses). */
    public JsonNode readSafely(String text) {
        try {
            return read(text);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise JSON", e);
        }
    }

    public JsonNode toNode(Object value) {
        return value == null ? null : mapper.valueToTree(value);
    }

    /** Text value of node.field, or null if missing/null/blank. */
    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        String s = v.asText();
        return s == null || s.isBlank() ? null : s;
    }

    public static boolean bool(JsonNode node, String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    public static List<String> strings(JsonNode node, String field) {
        List<String> out = new ArrayList<>();
        if (node == null) {
            return out;
        }
        JsonNode arr = node.get(field);
        if (arr != null && arr.isArray()) {
            Iterator<JsonNode> it = arr.elements();
            while (it.hasNext()) {
                JsonNode v = it.next();
                if (v != null && !v.isNull() && !v.asText().isBlank()) {
                    out.add(v.asText());
                }
            }
        }
        return out;
    }

    public static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    public static String joinCsv(List<String> values) {
        return values == null ? "" : String.join(",", values);
    }

    public static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    /** "Ananya@KrishiAI.in" / "https://www.acme.ai/x" -> "krishiai.in" / "acme.ai". */
    public static String domainOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.trim().toLowerCase();
        if (s.contains("@") && !s.contains("://")) {
            return s.substring(s.lastIndexOf('@') + 1);
        }
        s = s.replaceFirst("^[a-z]+://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        if (s.startsWith("www.")) {
            s = s.substring(4);
        }
        return s.isBlank() ? null : s;
    }

    private static final List<String> FREE_MAIL = List.of(
            "gmail.com", "googlemail.com", "yahoo.com", "yahoo.co.in", "outlook.com", "hotmail.com", "live.com",
            "icloud.com", "proton.me", "protonmail.com", "rediffmail.com", "aol.com", "zoho.com", "pitsch.local");

    public static boolean isFreeMail(String domain) {
        return domain == null || FREE_MAIL.contains(domain);
    }
}
