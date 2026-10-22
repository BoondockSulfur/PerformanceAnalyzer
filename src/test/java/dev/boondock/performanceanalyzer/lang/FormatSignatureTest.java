package dev.boondock.performanceanalyzer.lang;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class FormatSignatureTest {

    @Test
    void oldHotChunkTitleDiffersFromCurrent() {
        assertNotEquals(LanguageManager.formatSignature("%s in %s bei %d, %d"),
                LanguageManager.formatSignature("Hochaktiver Chunk in %s bei %d, %d"));
    }

    @Test
    void wordingAloneDoesNotMatter() {
        assertEquals(LanguageManager.formatSignature("Ladevorgaenge: %d fuer %s"),
                LanguageManager.formatSignature("Ladevorgänge: %d für %s"));
    }

    @Test
    void precisionAndColorCodesAreIgnored() {
        assertEquals("f,f,s", LanguageManager.formatSignature("&7Gemessen: &f%.1f ms/s&7 (%.1f%% eines Threads): &f%s"));
    }

    @Test
    void namedPlaceholdersAreIgnored() {
        assertEquals("", LanguageManager.formatSignature("&7Entladevorgänge (Sitzung): &f%count%"));
        assertEquals(LanguageManager.formatSignature("%count% players online"),
                LanguageManager.formatSignature("%count% Spieler online"));
    }

    @Test
    void literalPercentWithSpaceIsNotAConversion() {
        assertEquals("", LanguageManager.formatSignature("50% der Ticks"));
    }
}
