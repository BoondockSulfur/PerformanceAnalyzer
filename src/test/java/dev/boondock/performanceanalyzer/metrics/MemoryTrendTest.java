package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryTrendTest {

    /** Buckets 10 min apart; the last one is still open and ignored. */
    private static List<long[]> buckets(long... floorsMb) {
        List<long[]> list = new ArrayList<>();
        for (int i = 0; i < floorsMb.length; i++) {
            list.add(new long[]{1000 + i, floorsMb[i]});
        }
        return list;
    }

    @Test
    void steadyGrowthIsALeak() {
        // +50 MB per 10 min = 300 MB/h over 2.5 h, 8 GB heap.
        long[] floors = new long[17];
        for (int i = 0; i < floors.length; i++) {
            floors[i] = 2000 + 50L * i;
        }
        var trend = GcSampler.computeTrend(buckets(floors), 8192);
        assertEquals(300.0, trend.slopeMbPerHour(), 0.01);
        assertEquals(1.0, trend.r2(), 1e-9);
        assertTrue(trend.leakSuspected());
        assertTrue(trend.hoursUntilFull() > 0);
    }

    @Test
    void flatFloorIsNoLeak() {
        var trend = GcSampler.computeTrend(buckets(3000, 3100, 2950, 3050, 3000, 2980, 3020, 3010,
                3000, 2990, 3005, 3015, 3000, 3000), 8192);
        assertFalse(trend.leakSuspected());
    }

    @Test
    void tooLittleDataGivesNoTrend() {
        assertFalse(GcSampler.computeTrend(buckets(1, 2, 3, 4, 5), 8192).hasData());
    }
}
