package dev.boondock.performanceanalyzer.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContextFormatTest {

    @Test
    void commandKeepsLabelAndFirstArgument() {
        assertEquals("/locate structure …", ActivityContext.redactCommand("/locate structure minecraft:stronghold"));
        assertEquals("/rtp", ActivityContext.redactCommand("/rtp"));
        assertEquals("/mv tp", ActivityContext.redactCommand("mv tp"));
    }

    @Test
    void secretsAndPrivateMessagesKeepOnlyTheLabel() {
        assertEquals("/login", ActivityContext.redactCommand("/login hunter2"));
        assertEquals("/msg", ActivityContext.redactCommand("/msg Alice hello there"));
        assertEquals("/essentials:msg", ActivityContext.redactCommand("/essentials:msg Alice hi"));
    }

    @Test
    void longArgumentsAreCut() {
        assertEquals("/say " + "a".repeat(30) + "…", ActivityContext.redactCommand("/say " + "a".repeat(40)));
    }

    @Test
    void incidentNumberIsReadFromBothLanguages() {
        assertEquals(12, IncidentReport.headerId("=== Incident #12 — 2026-11-24 23:19:47 bis 2026-11-24 23:19:58 (11s) ==="));
        assertEquals(3, IncidentReport.headerId("=== Incident #3 — 2026-11-24 23:19:47 CET to 2026-11-24 23:19:58 CET (11s) ==="));
        assertEquals(0, IncidentReport.headerId("  > [WARNING/MEASURED] #5 not a header"));
    }
}
