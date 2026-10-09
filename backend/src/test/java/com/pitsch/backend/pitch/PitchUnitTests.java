package com.pitsch.backend.pitch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PitchUnitTests {

    @Test
    void riskLevelDescribesEvidenceOnly() {
        assertEquals("HIGH", PitchService.riskLevel(10, 1, 0));
        assertEquals("MEDIUM", PitchService.riskLevel(1, 0, 3));
        assertEquals("LOW", PitchService.riskLevel(5, 0, 2));
    }

    @Test
    void companyMentionsMatchWholeWords() {
        assertTrue(PitchService.mentions("update from krishi ai: we closed our pilot", "Krishi AI"));
        assertFalse(PitchService.mentions("the krishiaid project", "Krishi"));
        assertFalse(PitchService.mentions("anything", "AI"), "names shorter than 3 characters never match");
    }

    @Test
    void threadListIsBounded() {
        Pitch p = new Pitch();
        for (int i = 0; i < 500; i++) {
            PitchService.addThread(p, "thread-" + i + "-xxxxxxxxxx");
        }
        assertTrue(p.getThreadIds().length() <= 2000);
        assertTrue(p.getThreadIds().contains("thread-499-"));
        PitchService.addThread(p, "thread-499-xxxxxxxxxx");
        assertEquals(p.getThreadIds().indexOf("thread-499-"), p.getThreadIds().lastIndexOf("thread-499-"));
    }

    @Test
    void likeEscapingNeutralisesWildcards() {
        assertEquals("100\\%\\_off\\\\", PitchController.escapeLike("100%_off\\"));
    }
}
