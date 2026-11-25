package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSpikeTest {

    @Test
    void jumpIsRelativeToThePlayersOwnPing() {
        assertTrue(NetworkSampler.isJump(250, 40));
        assertFalse(NetworkSampler.isJump(150, 40));   // +110 ms: not enough
        assertFalse(NetworkSampler.isJump(380, 250));  // far player, normal for them
        assertTrue(NetworkSampler.isJump(520, 250));
    }

    @Test
    void sharedSpikeNeedsTwoAndHalf() {
        assertFalse(NetworkSampler.isSharedSpike(1, 1));
        assertTrue(NetworkSampler.isSharedSpike(2, 4));
        assertFalse(NetworkSampler.isSharedSpike(2, 5));
    }
}
