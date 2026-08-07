package dev.boondock.performanceanalyzer.alerts;

import dev.boondock.performanceanalyzer.analysis.Finding;
import dev.boondock.performanceanalyzer.analysis.Incident;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.analysis.Severity;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Notifies admins about incidents — with severity, escalation and a
 * "resolved" message including the incident duration.
 *
 * <p>Dispatch is thread-safe by construction: the monitor loop runs async,
 * so all Bukkit interaction is funneled through the global scheduler and
 * sounds are played on each player's own scheduler (Folia-legal). Incident
 * open/escalate/resolve messages always go out (state changes are the whole
 * point); only repeated same-state alerts are cooldown-guarded.</p>
 */
public class AlertManager implements IncidentAnalyzer.Listener {

    public enum AlertType {
        PERFORMANCE,
        PACKET_FLOOD
    }

    private static final Pattern COLOR_CODES = Pattern.compile("§x(§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]");

    private final Plugin plugin;
    private final PluginConfig config;
    private final LanguageManager lang;
    private final DiscordWebhook discordWebhook;
    private final ConcurrentHashMap<String, AtomicLong> lastAlertTimes = new ConcurrentHashMap<>();
    private AlertPreferenceManager preferenceManager;

    public AlertManager(Plugin plugin, PluginConfig config, LanguageManager lang) {
        this.plugin = plugin;
        this.config = config;
        this.lang = lang;
        this.discordWebhook = new DiscordWebhook(plugin, config);
    }

    public void setPreferenceManager(AlertPreferenceManager preferenceManager) {
        this.preferenceManager = preferenceManager;
    }

    /* ------------------------------------------------------------------ */
    /* Incident lifecycle (called from the monitor thread)                 */
    /* ------------------------------------------------------------------ */

    @Override
    public void incidentOpened(Incident incident) {
        String message = lang.format("alert.incident_opened",
                incident.peakSeverity().color() + incident.peakSeverity().name(),
                String.format("%.1f", incident.worstAvgMspt()),
                String.format("%.1f", incident.lowestTps()),
                topFindingLine(incident));
        broadcast(incident.peakSeverity(), message, true);
        toDiscord("incident_opened", incident.peakSeverity(), message, incident.peakScore());
    }

    @Override
    public void incidentEscalated(Incident incident) {
        String message = lang.format("alert.incident_escalated",
                incident.currentSeverity().color() + incident.currentSeverity().name(),
                topFindingLine(incident));
        broadcast(incident.currentSeverity(), message, true);
        toDiscord("incident_escalated", incident.currentSeverity(), message, incident.peakScore());
    }

    @Override
    public void incidentResolved(Incident incident) {
        String message = lang.format("alert.incident_resolved",
                incident.formattedDuration(),
                String.format("%.0f", incident.worstTickMs()),
                String.format("%.1f", incident.lowestTps()));
        broadcast(Severity.OK, message, false);
        toDiscord("incident_resolved", Severity.OK, message, incident.peakScore());
    }

    private String topFindingLine(Incident incident) {
        for (Finding finding : incident.findings()) {
            if (finding.type() != Finding.Type.UNKNOWN) {
                return finding.title();
            }
        }
        return lang.format("alert.no_clear_cause");
    }

    /* ------------------------------------------------------------------ */
    /* Simple threshold alerts (e.g. packet flood)                         */
    /* ------------------------------------------------------------------ */

    /**
     * Cooldown-guarded standalone alert. The cooldown shrinks with severity:
     * EMERGENCY 2 min, CRITICAL 5 min, otherwise 10 min.
     */
    public void sendAlert(AlertType type, Severity severity, String message, double value) {
        long cooldownMs = switch (severity) {
            case EMERGENCY -> 2 * 60_000L;
            case CRITICAL -> 5 * 60_000L;
            default -> 10 * 60_000L;
        };
        long now = System.currentTimeMillis();
        AtomicLong lastTime = lastAlertTimes.computeIfAbsent(type.name(), k -> new AtomicLong(0));
        while (true) {
            long last = lastTime.get();
            if (now - last < cooldownMs) {
                return;
            }
            if (lastTime.compareAndSet(last, now)) {
                break;
            }
        }
        String formatted = severity.color() + "[Performance] §f" + message;
        broadcast(severity, formatted, severity.atLeast(Severity.WARNING));
        toDiscord(type == AlertType.PACKET_FLOOD ? "packet_flood" : "performance", severity, message, value);
    }

    /* ------------------------------------------------------------------ */
    /* Dispatch                                                            */
    /* ------------------------------------------------------------------ */

    private void broadcast(Severity severity, String message, boolean withSound) {
        // Incidents are rare, state-changing events — always visible in the
        // console, not only to online players with the alert permission.
        if (severity.atLeast(Severity.CRITICAL)) {
            plugin.getLogger().warning(stripColors(message));
        } else {
            plugin.getLogger().info(stripColors(message));
        }
        Scheduling.runGlobal(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (!player.hasPermission("performance.alerts") && !player.hasPermission("performance.admin")) {
                    continue;
                }
                if (preferenceManager != null
                        && !preferenceManager.shouldReceive(player, AlertPreferenceManager.AlertCategory.PERFORMANCE)) {
                    continue;
                }
                player.sendMessage(message);
                if (withSound) {
                    Scheduling.runAtEntity(plugin, player, () ->
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f,
                                    severity.atLeast(Severity.CRITICAL) ? 0.5f : 0.8f));
                }
            }
        });
    }

    private void toDiscord(String discordType, Severity severity, String message, double value) {
        if (!config.discordEnabled() || !config.discordAlertType(discordType)) {
            return;
        }
        discordWebhook.sendAlert(severity, stripColors(message), value);
    }

    private static String stripColors(String input) {
        return COLOR_CODES.matcher(input).replaceAll("");
    }
}
