package dev.boondock.performanceanalyzer.db;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The batch drain must not lose measurements.
 *
 * <p>The original loop polled before it checked the batch size
 * ({@code (e = queue.poll()) != null && batch.size() < max}), so every flush
 * that reached the cap removed one entry from the queue and then dropped it on
 * the floor — silently, because nothing counts what never arrives.
 */
class DrainBatchTest {

    private static Queue<Integer> queueOf(int count) {
        Queue<Integer> queue = new ArrayDeque<>();
        for (int i = 0; i < count; i++) {
            queue.add(i);
        }
        return queue;
    }

    @Test
    void aFullBatchLeavesTheRestInTheQueue() {
        Queue<Integer> queue = queueOf(10);

        List<Integer> batch = DatabaseManager.drain(queue, 4);

        assertEquals(List.of(0, 1, 2, 3), batch);
        assertEquals(6, queue.size(), "nothing may vanish between batch and queue");
        assertEquals(4, queue.peek(), "the next batch must continue where this one stopped");
    }

    @Test
    void repeatedDrainsDeliverEveryEntryExactlyOnce() {
        Queue<Integer> queue = queueOf(1000);

        int seen = 0;
        List<Integer> batch;
        while (!(batch = DatabaseManager.drain(queue, 7)).isEmpty()) {
            seen += batch.size();
        }

        assertEquals(1000, seen, "142 capped batches, not one entry short");
        assertTrue(queue.isEmpty());
    }

    @Test
    void anEmptyQueueYieldsAnEmptyBatch() {
        assertTrue(DatabaseManager.drain(new ArrayDeque<Integer>(), 100).isEmpty());
    }

    @Test
    void fewerEntriesThanTheCapDrainCompletely() {
        Queue<Integer> queue = queueOf(3);

        assertEquals(List.of(0, 1, 2), DatabaseManager.drain(queue, 100));
        assertTrue(queue.isEmpty());
    }
}
