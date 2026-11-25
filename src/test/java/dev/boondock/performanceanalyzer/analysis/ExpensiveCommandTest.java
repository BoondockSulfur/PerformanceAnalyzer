package dev.boondock.performanceanalyzer.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpensiveCommandTest {

    @Test
    void slashEntryMatchesOnlyWorldEditCommands() {
        assertTrue(IncidentAnalyzer.matchesExpensivePrefix("/paste", "/"));
        assertFalse(IncidentAnalyzer.matchesExpensivePrefix("home", "/"));
        assertFalse(IncidentAnalyzer.matchesExpensivePrefix("list", "/"));
    }

    @Test
    void leadingSlashOfOtherEntriesIsOptional() {
        assertTrue(IncidentAnalyzer.matchesExpensivePrefix("mv create test normal", "mv create"));
        assertTrue(IncidentAnalyzer.matchesExpensivePrefix("mv create test normal", "/mv create"));
        assertTrue(IncidentAnalyzer.matchesExpensivePrefix("/paste", "//paste"));
    }

    @Test
    void emptyEntryMatchesNothing() {
        assertFalse(IncidentAnalyzer.matchesExpensivePrefix("home", ""));
    }
}
