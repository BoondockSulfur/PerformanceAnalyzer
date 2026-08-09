package dev.boondock.performanceanalyzer.commands;

import dev.boondock.performanceanalyzer.PerformanceAnalyzer;
import dev.boondock.performanceanalyzer.calibrate.CalibrationEngine;
import dev.boondock.performanceanalyzer.calibrate.CalibrationProposal;
import dev.boondock.performanceanalyzer.calibrate.CalibrationProposal.ProposedValue;
import dev.boondock.performanceanalyzer.config.PluginConfig;
import dev.boondock.performanceanalyzer.lang.LanguageManager;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Proposes threshold values derived from this server's own measurements.
 *
 * <ul>
 *   <li>{@code /perfcalibrate} - analyse and show, writes nothing</li>
 *   <li>{@code /perfcalibrate apply} - back up config.yml, write, reload</li>
 *   <li>{@code /perfcalibrate revert} - restore the pre-calibration backup</li>
 * </ul>
 *
 * <p>Analysis runs off the main thread because it queries the database;
 * everything user-visible hops back before touching the server.
 */
public class PerfCalibrateCommand implements CommandExecutor, TabCompleter {

    private final PerformanceAnalyzer plugin;
    private final CalibrationEngine engine;
    private final PluginConfig config;

    public PerfCalibrateCommand(PerformanceAnalyzer plugin, CalibrationEngine engine, PluginConfig config) {
        this.plugin = plugin;
        this.engine = engine;
        this.config = config;
    }

    private LanguageManager lang() {
        return plugin.lang();
    }

    /**
     * Async replies never reach RCON senders (the RCON response is closed
     * before the analysis finishes), so non-player senders additionally get
     * the result written to the server log.
     */
    private void reply(CommandSender sender, String message) {
        sender.sendMessage(message);
        if (!(sender instanceof org.bukkit.entity.Player)) {
            plugin.getLogger().info("[Calibrate] "
                    + message.replaceAll("§x(§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]", ""));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("performance.admin")) {
            reply(sender, lang().get("general.no_permission"));
            return true;
        }

        String mode = args.length > 0 ? args[0].toLowerCase() : "show";
        switch (mode) {
            case "show" -> runAnalysis(sender, false);
            case "apply" -> runAnalysis(sender, true);
            case "revert" -> revert(sender);
            default -> reply(sender, lang().get("calibrate.usage"));
        }
        return true;
    }

    private void runAnalysis(CommandSender sender, boolean apply) {
        reply(sender, lang().get("calibrate.running"));
        Scheduling.runAsync(plugin, () -> {
            CalibrationProposal proposal;
            try {
                proposal = engine.analyze();
            } catch (Exception e) {
                plugin.getLogger().warning("[Calibrate] analysis failed: " + e.getMessage());
                Scheduling.runGlobal(plugin, () -> reply(sender, lang().get("calibrate.failed")));
                return;
            }
            Scheduling.runGlobal(plugin, () -> {
                print(sender, proposal);
                if (apply) {
                    apply(sender, proposal);
                }
            });
        });
    }

    private void print(CommandSender sender, CalibrationProposal proposal) {
        LanguageManager lang = lang();
        reply(sender, lang.get("calibrate.header"));
        reply(sender, lang.get("calibrate.window",
                "%minutes%", String.valueOf(proposal.observedMinutes())));

        List<ProposedValue> changed = proposal.changedValues();
        if (changed.isEmpty()) {
            // "Nothing to change" only means the thresholds already fit when
            // there was actually something to compare against. With blockers
            // present nothing could be derived at all, and claiming a match
            // would be a lie.
            reply(sender, lang.get(proposal.blockers().isEmpty()
                    ? "calibrate.no_changes"
                    : "calibrate.no_values_yet"));
        } else {
            for (ProposedValue value : changed) {
                reply(sender, lang.get("calibrate.value_line",
                        "%path%", value.path(),
                        "%old%", String.valueOf(value.oldValue()),
                        "%new%", String.valueOf(value.newValue())));
                reply(sender, lang.get("calibrate.value_reason", "%reason%", value.reason()));
            }
        }

        printBlock(sender, "calibrate.blocked_header", proposal.blockers());
        printBlock(sender, "calibrate.warning_header", proposal.warnings());
        printBlock(sender, "calibrate.notes_header", proposal.notes());

        if (proposal.canApply()) {
            reply(sender, lang.get("calibrate.hint_apply"));
        }
        reply(sender, lang.get("calibrate.hint_recalibrate"));
    }

    private void printBlock(CommandSender sender, String headerKey, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        reply(sender, lang().get(headerKey));
        for (String line : lines) {
            reply(sender, lang().get("calibrate.list_entry", "%text%", line));
        }
    }

    private void apply(CommandSender sender, CalibrationProposal proposal) {
        if (!proposal.blockers().isEmpty()) {
            reply(sender, lang().get("calibrate.apply_blocked"));
            return;
        }
        if (proposal.changedValues().isEmpty()) {
            reply(sender, lang().get("calibrate.apply_nothing"));
            return;
        }

        if (!config.backupForCalibration()) {
            reply(sender, lang().get("calibrate.backup_failed"));
            return;
        }

        int count = proposal.changedValues().size();
        config.applyCalibration(proposal.asConfigMap())
                .whenComplete((ignored, error) -> Scheduling.runGlobal(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().warning("[Calibrate] could not save config: " + error.getMessage());
                        reply(sender, lang().get("calibrate.backup_failed"));
                        return;
                    }
                    // Reload only after the file is on disk - reloadPlugin()
                    // re-reads config.yml, so an unfinished save would drop
                    // exactly the values just written.
                    plugin.reloadPlugin();
                    reply(sender, lang().get("calibrate.applied",
                            "%count%", String.valueOf(count),
                            "%backup%", PluginConfig.CALIBRATION_BACKUP));
                }));
    }

    private void revert(CommandSender sender) {
        if (!config.hasCalibrationBackup()) {
            reply(sender, lang().get("calibrate.revert_missing"));
            return;
        }
        if (!config.restoreCalibrationBackup()) {
            reply(sender, lang().get("calibrate.revert_failed"));
            return;
        }
        plugin.reloadPlugin();
        reply(sender, lang().get("calibrate.reverted"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("performance.admin") || args.length != 1) {
            return Collections.emptyList();
        }
        return List.of("apply", "revert").stream()
                .filter(s -> s.startsWith(args[0].toLowerCase()))
                .collect(Collectors.toList());
    }
}
