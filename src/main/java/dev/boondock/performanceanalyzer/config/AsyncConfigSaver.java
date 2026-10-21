package dev.boondock.performanceanalyzer.config;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;

/**
 * Writes config.yml off the tick threads.
 *
 * <p>The YAML text is produced on the caller's thread, at the moment the save
 * is requested; only the file write happens asynchronously. Serializing on
 * the writer thread instead would read the configuration while the tick
 * thread may still be changing it.
 *
 * <p>Requests arriving while a write is running are coalesced: the newest
 * snapshot is written next, and every future handed out completes only once
 * a snapshot taken no earlier than its own request is on disk. A future that
 * completed early - the old behaviour when a save was already running - let
 * callers re-read config.yml before their values had reached it.
 *
 * @since 3.1.0
 */
public class AsyncConfigSaver {

    private final JavaPlugin plugin;

    // All guarded by "this".
    private String pendingYaml;
    private CompletableFuture<Void> pendingFuture;
    private boolean writing;

    public AsyncConfigSaver(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Snapshots the current configuration and writes it asynchronously.
     *
     * @return future completing once this snapshot (or a newer one) is on disk
     */
    public CompletableFuture<Void> saveAsync() {
        String yaml = plugin.getConfig().saveToString();
        CompletableFuture<Void> future;
        synchronized (this) {
            pendingYaml = yaml;
            if (pendingFuture == null) {
                pendingFuture = new CompletableFuture<>();
            }
            future = pendingFuture;
            if (writing) {
                return future;
            }
            writing = true;
        }
        Scheduling.runAsync(plugin, this::drain);
        return future;
    }

    private void drain() {
        while (true) {
            String yaml;
            CompletableFuture<Void> future;
            synchronized (this) {
                if (pendingYaml == null) {
                    writing = false;
                    return;
                }
                yaml = pendingYaml;
                future = pendingFuture;
                pendingYaml = null;
                pendingFuture = null;
            }
            try {
                write(yaml);
                plugin.getLogger().fine("[Config] Configuration saved successfully");
                future.complete(null);
            } catch (Exception e) {
                plugin.getLogger().severe("[Config] Failed to save config: " + e.getMessage());
                future.completeExceptionally(e);
            }
        }
    }

    /** Temp file plus move, so a crash mid-write cannot leave a truncated config.yml. */
    private void write(String yaml) throws IOException {
        Path target = new File(plugin.getDataFolder(), "config.yml").toPath();
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling("config.yml.tmp");
        Files.writeString(temp, yaml, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Save config synchronously (for shutdown).
     * Should only be used during plugin disable.
     */
    public void saveSyncOnShutdown() {
        try {
            plugin.saveConfig();
            plugin.getLogger().info("[Config] Configuration saved synchronously on shutdown");
        } catch (Exception e) {
            plugin.getLogger().severe("[Config] Failed to save config on shutdown: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Check if a save operation is currently in progress.
     * @return true if saving
     */
    public synchronized boolean isSaving() {
        return writing;
    }
}
