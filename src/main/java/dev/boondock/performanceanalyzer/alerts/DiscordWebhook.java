package dev.boondock.performanceanalyzer.alerts;

import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.util.Constants;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple Discord Webhook integration for sending alerts to Discord channels.
 *
 * RATE-LIMITING:
 * - Discord limits webhooks to ~30 requests per minute
 * - This implementation enforces 1 request per 2 seconds (max 30/min)
 * - Alerts are queued and sent sequentially with delays
 */
public class DiscordWebhook {

    private final Plugin plugin;
    private final PluginConfig config;

    private final AtomicLong lastRequestTime = new AtomicLong(0);
    private final ConcurrentLinkedQueue<WebhookRequest> requestQueue = new ConcurrentLinkedQueue<>();
    private volatile boolean processorRunning = false;

    public DiscordWebhook(Plugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Validates a Discord webhook URL.
     * Ensures the URL is properly formed and points to Discord's domain.
     *
     * @param webhookUrl The URL to validate
     * @return true if valid, false otherwise
     */
    private boolean isValidDiscordWebhookUrl(String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            return false;
        }

        try {
            URL url = new URL(webhookUrl);

            // Must use HTTPS
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                plugin.getLogger().warning("[Discord] Webhook URL must use HTTPS protocol");
                return false;
            }

            // Must be Discord domain
            String host = url.getHost().toLowerCase();
            if (!host.equals("discord.com") && !host.equals("discordapp.com") && !host.endsWith(".discord.com") && !host.endsWith(".discordapp.com")) {
                plugin.getLogger().warning("[Discord] Webhook URL must be a Discord domain (discord.com or discordapp.com)");
                return false;
            }

            // Must contain /api/webhooks/ path
            String path = url.getPath();
            if (!path.startsWith("/api/webhooks/")) {
                plugin.getLogger().warning("[Discord] Invalid webhook path. Must start with /api/webhooks/");
                return false;
            }

            return true;
        } catch (MalformedURLException e) {
            plugin.getLogger().warning("[Discord] Malformed webhook URL: " + e.getMessage());
            return false;
        }
    }

    /**
     * Send an alert to Discord webhook.
     * Alerts are queued and sent with rate-limiting to prevent Discord API abuse.
     *
     * @param type Alert type
     * @param title Alert title
     * @param description Alert description
     * @param value Numeric value associated with alert
     */
    public void sendAlert(AlertManager.AlertType type, String title, String description, double value) {
        if (!config.discordEnabled()) {
            plugin.getLogger().fine("[Discord] Webhook deaktiviert (discord.enabled: false)");
            return;
        }

        String webhookUrl = config.discordWebhookUrl();
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            plugin.getLogger().warning("[Discord] Webhook URL is empty! Please configure in config.yml.");
            return;
        }

        // Validate webhook URL to prevent SSRF attacks
        if (!isValidDiscordWebhookUrl(webhookUrl)) {
            plugin.getLogger().severe("[Discord] Invalid webhook URL detected! Alerts disabled for security.");
            return;
        }

        // Check if this alert type should be sent to Discord
        String typeKey = type.name().toLowerCase();
        if (!config.discordAlertType(typeKey)) {
            plugin.getLogger().fine("[Discord] Alert type " + typeKey + " is disabled");
            return;
        }

        // Build Discord embed JSON
        String json = buildEmbedJson(type, title, description, value);

        // Queue the request for rate-limited sending
        if (requestQueue.size() >= Constants.DISCORD_MAX_QUEUE_SIZE) {
            plugin.getLogger().warning("[Discord] Alert queue full (" + Constants.DISCORD_MAX_QUEUE_SIZE + "), dropping alert: " + title);
            return;
        }

        requestQueue.offer(new WebhookRequest(webhookUrl, json, title));

        if (config.debugMode()) {
            plugin.getLogger().info("[Discord] Alert queued: " + title + " (Queue size: " + requestQueue.size() + ")");
        }

        // Start processor if not running
        startQueueProcessor();
    }

    /**
     * Start the queue processor if not already running.
     * Processes requests sequentially with rate-limiting.
     */
    private synchronized void startQueueProcessor() {
        if (processorRunning) {
            return;
        }

        processorRunning = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::processQueue);
    }

    /**
     * Process the webhook request queue with rate-limiting.
     */
    private void processQueue() {
        while (!requestQueue.isEmpty()) {
            WebhookRequest request = requestQueue.poll();
            if (request == null) {
                break;
            }

            // Enforce rate limit
            long now = System.currentTimeMillis();
            long lastRequest = lastRequestTime.get();
            long timeSinceLastRequest = now - lastRequest;

            if (timeSinceLastRequest < Constants.DISCORD_MIN_REQUEST_DELAY_MS) {
                long sleepTime = Constants.DISCORD_MIN_REQUEST_DELAY_MS - timeSinceLastRequest;
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    plugin.getLogger().warning("[Discord] Queue processor interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // Send the request
            try {
                sendWebhookSync(request.webhookUrl, request.json);
                lastRequestTime.set(System.currentTimeMillis());

                if (config.debugMode()) {
                    plugin.getLogger().info("[Discord] Alert sent: " + request.title);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[Discord] Webhook failed: " + e.getMessage() + " (Alert: " + request.title + ")");
            }
        }

        processorRunning = false;

        // Check if new items were added while we were finishing
        if (!requestQueue.isEmpty()) {
            startQueueProcessor();
        }
    }

    private String buildEmbedJson(AlertManager.AlertType type, String title, String description, double value) {
        // Color based on severity
        int color = getColorForType(type);

        // Build JSON manually (avoiding external JSON libraries for simplicity)
        String timestamp = Instant.now().toString();

        return String.format("""
            {
              "embeds": [{
                "title": "%s",
                "description": "%s\\n\\n**Value:** %.2f",
                "color": %d,
                "footer": {
                  "text": "PerformanceAnalyzer"
                },
                "timestamp": "%s"
              }]
            }
            """, escapeJson(title), escapeJson(description), value, color, timestamp);
    }

    private int getColorForType(AlertManager.AlertType type) {
        return switch (type) {
            case HIGH_MSPT, TPS_DROP -> 0xFF5555; // Red
            case HIGH_HEAP -> 0xFFAA00; // Orange
            case PACKET_FLOOD -> 0xAA00AA; // Purple
            case ANTICHEAT -> 0xFFFF55; // Yellow
        };
    }

    private void sendWebhookSync(String webhookUrl, String json) throws IOException {
        URL url = new URL(webhookUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        try {
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", "PerformanceAnalyzer/" + plugin.getDescription().getVersion());
            connection.setDoOutput(true);

            // Write JSON payload
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = json.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Check response
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("Discord API returned status " + responseCode);
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Escape JSON special characters to prevent malformed JSON.
     * Handles common escape sequences and control characters.
     */
    private String escapeJson(String str) {
        if (str == null) return "";
        // Replace in specific order to avoid double-escaping
        return str
            .replace("\\", "\\\\")  // Must be first!
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f")
            .replaceAll("[\u0000-\u001F]", ""); // Remove other control characters
    }

    /**
     * Internal data class for queued webhook requests.
     */
    private static class WebhookRequest {
        final String webhookUrl;
        final String json;
        final String title;

        WebhookRequest(String webhookUrl, String json, String title) {
            this.webhookUrl = webhookUrl;
            this.json = json;
            this.title = title;
        }
    }
}
