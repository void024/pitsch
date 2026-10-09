package com.pitsch.backend.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

class IngestionUnitTests {

    static EmailIngestionService.IngestCommand cmd(String gmailId, String messageId) {
        return new EmailIngestionService.IngestCommand(Email.Source.MANUAL, "a@b.example", null, "s", "b", null, messageId,
                null, gmailId, null, Instant.now(), List.of(), List.of(), false);
    }

    @Test
    void dedupeKeyPrefersProviderIdsThenMessageId() {
        assertEquals("gmail:abc", EmailIngestionService.dedupeKey(cmd("abc", "<m@x>")));
        assertEquals(EmailIngestionService.dedupeKey(cmd(null, "<M@X>")), EmailIngestionService.dedupeKey(cmd(null, " <m@x> ")),
                "Message-IDs compare case-insensitively and trimmed");
        assertNull(EmailIngestionService.dedupeKey(cmd(null, null)));
    }
}
