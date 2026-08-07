package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.analysis.Finding;
import dev.boondock.performanceanalyzer.analysis.Incident;
import dev.boondock.performanceanalyzer.analysis.IncidentAnalyzer;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /perfincidents [number|clear] — the incident engine's chat frontend.
 *
 * <p>No args lists recent incidents (#1 = newest); a number shows the detail
 * view including trigger reasons, findings with their confidence tag and —
 * for located findings — a clickable teleport. Replaces /perfdrops.</p>
 */
public class PerfIncidentsCommand implements CommandExecutor, TabCompleter {

    private final PerformanceAnalyzer plugin;
    private final IncidentAnalyzer incidentAnalyzer;

    public PerfIncidentsCommand(PerformanceAnalyzer plugin, IncidentAnalyzer incidentAnalyzer) {
        this.plugin = plugin;
        this.incidentAnalyzer = incidentAnalyzer;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            sender.sendMessage(lang().get("general.no_permission"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            incidentAnalyzer.clearHistory();
            sender.sendMessage(lang().get("incident_cmd.cleared"));
            return true;
        }

        List<Incident> incidents = incidentAnalyzer.recentIncidents();

        if (args.length > 0) {
            int number;
            try {
                number = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                sender.sendMessage(lang().get("incident_cmd.invalid_number",
                        "%max%", String.valueOf(Math.max(1, incidents.size()))));
                return true;
            }
            if (number < 1 || number > incidents.size()) {
                sender.sendMessage(lang().get("incident_cmd.invalid_number",
                        "%max%", String.valueOf(Math.max(1, incidents.size()))));
                return true;
            }
            sendDetail(sender, lang(), incidents.get(number - 1), number);
            return true;
        }

        sendList(sender, lang(), incidents);
        return true;
    }

    /* ------------------------------------------------------------------ */
    /* Shared rendering (also used by the Incidents GUI)                   */
    /* ------------------------------------------------------------------ */

    /** Sends the incident list, newest first (#1 = newest). */
    public static void sendList(CommandSender sender, LanguageManager lang, List<Incident> incidents) {
        if (incidents.isEmpty()) {
            sender.sendMessage(lang.get("incident_cmd.none"));
            return;
        }
        sender.sendMessage(lang.get("incident_cmd.header", "%count%", String.valueOf(incidents.size())));
        int number = 1;
        for (Incident incident : incidents) {
            String activeTag = incident.isActive() ? lang.get("incident_cmd.active_tag") + " " : "";
            sender.sendMessage(lang.get("incident_cmd.entry",
                    "%number%", String.valueOf(number),
                    "%active%", activeTag,
                    "%color%", incident.peakSeverity().color(),
                    "%severity%", incident.peakSeverity().name(),
                    "%start%", incident.formattedStart(),
                    "%duration%", incident.formattedDuration(),
                    "%worst%", String.format("%.0f", incident.worstTickMs()),
                    "%tps%", String.format("%.1f", incident.lowestTps()),
                    "%cause%", topFindingTitle(lang, incident)));
            number++;
        }
        sender.sendMessage(lang.get("incident_cmd.hint_detail"));
        sender.sendMessage(lang.get("incident_cmd.hint_clear"));
    }

    /** Sends the full detail view of one incident. */
    public static void sendDetail(CommandSender sender, LanguageManager lang, Incident incident, int number) {
        sender.sendMessage(lang.get("incident_cmd.detail_header", "%number%", String.valueOf(number)));
        sender.sendMessage(lang.get("incident_cmd.detail_status",
                "%status%", lang.get(incident.isActive()
                        ? "incident_cmd.status_active" : "incident_cmd.status_resolved")));
        sender.sendMessage(lang.get("incident_cmd.detail_time",
                "%start%", incident.formattedStart(),
                "%duration%", incident.formattedDuration()));
        sender.sendMessage(lang.get("incident_cmd.detail_peak",
                "%color%", incident.peakSeverity().color(),
                "%severity%", incident.peakSeverity().name(),
                "%score%", String.valueOf(incident.peakScore())));
        sender.sendMessage(lang.get("incident_cmd.detail_metrics",
                "%worst%", String.format("%.0f", incident.worstTickMs()),
                "%avgworst%", String.format("%.1f", incident.worstAvgMspt()),
                "%tps%", String.format("%.1f", incident.lowestTps())));

        if (!incident.triggerReasons().isEmpty()) {
            sender.sendMessage(lang.get("incident_cmd.detail_triggers"));
            for (String reason : incident.triggerReasons()) {
                sender.sendMessage(lang.get("incident_cmd.trigger_entry", "%reason%", reason));
            }
        }

        List<Finding> findings = incident.findings();
        if (findings.isEmpty()) {
            sender.sendMessage(lang.get("incident_cmd.no_findings"));
            return;
        }
        sender.sendMessage(lang.get("incident_cmd.findings_header"));
        for (Finding finding : findings) {
            String confidenceKey = finding.confidence() == Finding.Confidence.MEASURED
                    ? "incident_cmd.confidence_measured" : "incident_cmd.confidence_heuristic";
            sender.sendMessage(lang.get("incident_cmd.finding_entry",
                    "%color%", finding.severity().color(),
                    "%title%", finding.title(),
                    "%confidence%", lang.get(confidenceKey)));
            sender.sendMessage(lang.get("incident_cmd.finding_detail", "%detail%", finding.detail()));
            sender.sendMessage(lang.get("incident_cmd.finding_recommendation",
                    "%recommendation%", finding.recommendation()));
            if (finding.hasLocation()) {
                sendTeleportLine(sender, lang, finding);
            }
        }
    }

    private static void sendTeleportLine(CommandSender sender, LanguageManager lang, Finding finding) {
        int x = finding.blockX();
        int z = finding.blockZ();
        if (sender instanceof Player player) {
            String text = lang.get("incident_cmd.finding_teleport",
                    "%world%", finding.worldName(),
                    "%x%", String.valueOf(x),
                    "%z%", String.valueOf(z));
            Component line = LegacyComponentSerializer.legacySection().deserialize(text)
                    .clickEvent(ClickEvent.runCommand(
                            String.format(Locale.ROOT, "/tp %d 100 %d", x, z)));
            player.sendMessage(line);
        } else {
            sender.sendMessage(lang.get("incident_cmd.finding_location",
                    "%world%", finding.worldName(),
                    "%x%", String.valueOf(x),
                    "%z%", String.valueOf(z)));
        }
    }

    private static String topFindingTitle(LanguageManager lang, Incident incident) {
        for (Finding finding : incident.findings()) {
            if (finding.type() != Finding.Type.UNKNOWN) {
                return finding.title();
            }
        }
        return lang.get("alert.no_clear_cause");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1 && sender.hasPermission("performance.admin")) {
            if ("clear".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                completions.add("clear");
            }
            int count = incidentAnalyzer.recentIncidents().size();
            for (int i = 1; i <= count; i++) {
                String num = String.valueOf(i);
                if (num.startsWith(args[0])) {
                    completions.add(num);
                }
            }
        }
        return completions;
    }
}
