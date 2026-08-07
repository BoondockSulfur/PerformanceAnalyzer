package dev.boondock.performanceanalyzer.platform;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.concurrent.TimeUnit;

/**
 * Thin scheduling facade over Paper's region-aware schedulers
 * (GlobalRegionScheduler / RegionScheduler / AsyncScheduler / EntityScheduler).
 *
 * These schedulers are implemented on plain Paper since 1.20.1 and are the
 * only legal schedulers on Folia, so every task in this plugin goes through
 * this class instead of {@code Bukkit.getScheduler()}.
 */
public final class Scheduling {

    /** True when running on Folia (regionized ticking). */
    public static final boolean FOLIA = detectFolia();

    private Scheduling() {
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Global region (main thread on Paper)                                */
    /* ------------------------------------------------------------------ */

    public static ScheduledTask runGlobal(Plugin plugin, Runnable task) {
        return Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
    }

    public static ScheduledTask runGlobalDelayed(Plugin plugin, Runnable task, long delayTicks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayTicks));
    }

    public static ScheduledTask runGlobalRepeating(Plugin plugin, Runnable task, long initialDelayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler()
                .runAtFixedRate(plugin, t -> task.run(), Math.max(1L, initialDelayTicks), Math.max(1L, periodTicks));
    }

    /* ------------------------------------------------------------------ */
    /* Async                                                               */
    /* ------------------------------------------------------------------ */

    public static ScheduledTask runAsync(Plugin plugin, Runnable task) {
        return Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
    }

    public static ScheduledTask runAsyncDelayed(Plugin plugin, Runnable task, long delayMs) {
        return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), Math.max(1L, delayMs), TimeUnit.MILLISECONDS);
    }

    public static ScheduledTask runAsyncRepeating(Plugin plugin, Runnable task, long initialDelayMs, long periodMs) {
        return Bukkit.getAsyncScheduler()
                .runAtFixedRate(plugin, t -> task.run(), Math.max(1L, initialDelayMs), Math.max(1L, periodMs), TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------------ */
    /* Region / entity bound                                               */
    /* ------------------------------------------------------------------ */

    public static ScheduledTask runAtChunk(Plugin plugin, World world, int chunkX, int chunkZ, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, t -> task.run());
    }

    public static ScheduledTask runAtLocation(Plugin plugin, Location location, Runnable task) {
        return Bukkit.getRegionScheduler().run(plugin, location, t -> task.run());
    }

    /**
     * Runs on the thread that owns the entity. Falls back silently when the
     * entity is no longer valid (retired region / removed entity).
     */
    public static void runAtEntity(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, t -> task.run(), null);
    }

    /**
     * True when the current thread may touch the given location's region
     * (always the main thread on plain Paper).
     */
    public static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
        return Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ);
    }

    public static boolean isGlobalTickThread() {
        return Bukkit.isGlobalTickThread() || (!FOLIA && Bukkit.isPrimaryThread());
    }

    /** Cancels every global and async task this plugin scheduled. */
    public static void cancelAll(Plugin plugin) {
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
    }

    /** Null-safe cancel helper for stored task handles. */
    public static void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
