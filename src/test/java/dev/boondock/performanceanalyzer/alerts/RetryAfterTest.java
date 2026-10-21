package dev.boondock.performanceanalyzer.alerts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Discord answers HTTP 429 with a Retry-After header in seconds, sometimes
 * fractional. A wrong conversion either hammers the webhook again at once
 * or stalls the alert queue far longer than asked.
 */
class RetryAfterTest {

    @Test
    void wholeSecondsBecomeMilliseconds() {
        assertEquals(3_000L, DiscordWebhook.retryAfterMs("3"));
    }

    @Test
    void fractionalSecondsRoundUp() {
        assertEquals(1_235L, DiscordWebhook.retryAfterMs("1.2341"));
    }

    @Test
    void missingOrGarbledHeaderFallsBackToTwoSeconds() {
        assertEquals(2_000L, DiscordWebhook.retryAfterMs(null));
        assertEquals(2_000L, DiscordWebhook.retryAfterMs("soon"));
    }

    @Test
    void zeroStillWaitsAMoment() {
        assertEquals(1L, DiscordWebhook.retryAfterMs("0"));
    }
}
