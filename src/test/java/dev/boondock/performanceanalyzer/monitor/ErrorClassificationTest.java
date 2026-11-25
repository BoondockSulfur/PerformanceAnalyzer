package dev.boondock.performanceanalyzer.monitor;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ErrorClassificationTest {

    private static final Set<String> PLUGINS = Set.of("WorldGuard", "Quests");

    private static ServerErrorMonitor.Classification classify(String logger, boolean error, String message,
                                                              Throwable thrown) {
        return ServerErrorMonitor.classify(logger, error, message, thrown, PLUGINS);
    }

    @Test
    void neighborChainCarriesThePosition() {
        var c = classify("net.minecraft.world.level.redstone.CollectingNeighborUpdater", true,
                "Too many chained neighbor updates. Skipping the rest. First skipped position: 120, 64, -33", null);
        assertEquals(ServerErrorMonitor.Kind.NEIGHBOR_CHAIN, c.kind());
        assertEquals("120, 64, -33", c.source());
    }

    @Test
    void stackOverflowIsFoundInTheCauseChain() {
        var c = classify("net.minecraft.server.MinecraftServer", true, "Exception ticking world",
                new RuntimeException(new StackOverflowError()));
        assertEquals(ServerErrorMonitor.Kind.STACK_OVERFLOW, c.kind());
    }

    @Test
    void pluginErrorsAreAttributedFromTheMessage() {
        var c = classify("", true, "Could not pass event BlockBreakEvent to WorldGuard v7.0.17",
                new IllegalStateException());
        assertEquals(ServerErrorMonitor.Kind.PLUGIN_EXCEPTION, c.kind());
        assertEquals("WorldGuard", c.source());

        c = classify("", false, "Plugin Quests v5.3.2 generated an exception while executing task 42",
                new NullPointerException());
        assertEquals("Quests", c.source());
    }

    @Test
    void pluginErrorsAreAttributedFromTheLoggerName() {
        var c = classify("Quests", true, "Failed to save quest data", null);
        assertEquals(ServerErrorMonitor.Kind.PLUGIN_EXCEPTION, c.kind());
        assertEquals("Quests", c.source());
    }

    @Test
    void serverExceptionsAreNamedByType() {
        var c = classify("net.minecraft.server.MinecraftServer", true, "Entity threw exception at world:1,2,3",
                new IllegalArgumentException("bad"));
        assertEquals(ServerErrorMonitor.Kind.SERVER_EXCEPTION, c.kind());
        assertEquals("IllegalArgumentException", c.source());
    }

    @Test
    void plainWarningsAreIgnored() {
        assertNull(classify("net.minecraft.server.MinecraftServer", false,
                "Can't keep up! Is the server overloaded?", null));
        assertNull(classify("net.minecraft.server.MinecraftServer", true, "Some error without trace", null));
    }
}
