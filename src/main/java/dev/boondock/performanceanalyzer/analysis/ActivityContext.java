package dev.boondock.performanceanalyzer.analysis;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Remembers what players did in the last few minutes, so a stall can be put
 * next to the actions that may have caused it.
 *
 * <p>The sampler sees the code that blocked the tick, but not who asked for
 * it: a structure search looks the same whether an ender eye, a cartographer
 * trade or a shipwreck chest started it. These events fill that gap. Each is
 * recorded on the thread that fires it, with the player's position at that
 * moment, so nothing here reads world state from a foreign thread.
 */
public final class ActivityContext implements Listener {

    /** What kind of action an event was. */
    public enum Kind { COMMAND, CONSOLE_COMMAND, TELEPORT, WORLD_CHANGE, JOIN, QUIT, TRADE, LOOT, ENDER_EYE, PING_JUMP }

    /** One remembered action. {@code detail} is language-neutral raw text. */
    public record Event(long atMs, Kind kind, String player, String detail, String world, int x, int z) {
    }

    /** Where one player was at a point in time. */
    public record PlayerPosition(String name, String world, int x, int z) {
    }

    private record PlayerSnapshot(long atMs, List<PlayerPosition> players) {
    }

    private static final long KEEP_MS = 5 * 60_000L;
    private static final int MAX_EVENTS = 500;
    private static final int SNAPSHOT_PERIOD_TICKS = 40;
    private static final int MAX_SNAPSHOTS = (int) (KEEP_MS / (SNAPSHOT_PERIOD_TICKS * 50L)) + 1;
    /** Teleports shorter than this stay within loaded chunks and are not worth listing. */
    private static final double TELEPORT_MIN_DISTANCE = 64.0;
    private static final int MAX_ARGUMENT_LENGTH = 30;
    /** Commands whose arguments are secrets or private messages: only the label is kept. */
    private static final Set<String> REDACTED_COMMANDS = Set.of(
            "login", "l", "log", "register", "reg", "changepassword", "changepw", "cp", "unregister",
            "premium", "email", "2fa", "totp", "msg", "tell", "w", "whisper", "m", "r", "reply", "mail");

    private final Plugin plugin;
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final ArrayDeque<PlayerSnapshot> snapshots = new ArrayDeque<>();
    private ScheduledTask snapshotTask;

    public ActivityContext(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Starts the position snapshots. Paper only: on Folia the global thread
     * may not read player positions from other regions, so the report shows
     * the event positions alone.
     */
    public void start() {
        if (Scheduling.FOLIA || snapshotTask != null) {
            return;
        }
        snapshotTask = Scheduling.runGlobalRepeating(plugin, this::snapshotPlayers,
                SNAPSHOT_PERIOD_TICKS, SNAPSHOT_PERIOD_TICKS);
    }

    public void stop() {
        Scheduling.cancel(snapshotTask);
        snapshotTask = null;
    }

    private void snapshotPlayers() {
        List<PlayerPosition> players = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            players.add(new PlayerPosition(player.getName(), worldName(loc.getWorld()),
                    loc.getBlockX(), loc.getBlockZ()));
        }
        long now = System.currentTimeMillis();
        synchronized (snapshots) {
            snapshots.addFirst(new PlayerSnapshot(now, List.copyOf(players)));
            while (snapshots.size() > MAX_SNAPSHOTS) {
                snapshots.removeLast();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Events                                                               */
    /* ------------------------------------------------------------------ */

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        record(Kind.COMMAND, event.getPlayer(), redactCommand(event.getMessage()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsoleCommand(ServerCommandEvent event) {
        add(new Event(System.currentTimeMillis(), Kind.CONSOLE_COMMAND, event.getSender().getName(),
                redactCommand(event.getCommand()), "", 0, 0));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        boolean crossesWorld = from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld());
        if (!crossesWorld && from.distanceSquared(to) < TELEPORT_MIN_DISTANCE * TELEPORT_MIN_DISTANCE) {
            return;
        }
        add(new Event(System.currentTimeMillis(), Kind.TELEPORT, event.getPlayer().getName(),
                event.getCause().name(), worldName(to.getWorld()), to.getBlockX(), to.getBlockZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        record(Kind.WORLD_CHANGE, event.getPlayer(), worldName(event.getFrom()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        record(Kind.JOIN, event.getPlayer(), "");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        record(Kind.QUIT, event.getPlayer(), event.getReason().name());
    }

    /** Opening a villager's trades can generate explorer maps, which search for structures. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrade(InventoryOpenEvent event) {
        if (event.getInventory().getType() != InventoryType.MERCHANT || !(event.getPlayer() instanceof Player player)) {
            return;
        }
        Object holder = event.getInventory().getHolder();
        String merchant = holder instanceof org.bukkit.entity.Villager villager
                ? villager.getProfession().getKey().getKey()
                : holder instanceof org.bukkit.entity.Entity entity ? entity.getType().name().toLowerCase(Locale.ROOT) : "";
        record(Kind.TRADE, player, merchant);
    }

    /** Loot with treasure or explorer maps searches for structures when it is rolled. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent event) {
        Location loc = event.getLootContext().getLocation();
        String who = event.getEntity() instanceof Player player ? player.getName() : "";
        add(new Event(System.currentTimeMillis(), Kind.LOOT, who, event.getLootTable().getKey().toString(),
                worldName(loc.getWorld()), loc.getBlockX(), loc.getBlockZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getItem() == null || event.getItem().getType() != Material.ENDER_EYE
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        record(Kind.ENDER_EYE, event.getPlayer(), "");
    }

    /** A single player's ping jumped far above their usual value; called from the network sampler. */
    public void recordPingJump(String player, int pingMs, double usualMs) {
        add(new Event(System.currentTimeMillis(), Kind.PING_JUMP, player,
                pingMs + "/" + Math.round(usualMs), "", 0, 0));
    }

    private void record(Kind kind, Player player, String detail) {
        Location loc = player.getLocation();
        add(new Event(System.currentTimeMillis(), kind, player.getName(), detail,
                worldName(loc.getWorld()), loc.getBlockX(), loc.getBlockZ()));
    }

    private void add(Event event) {
        synchronized (events) {
            events.addLast(event);
            long cutoff = event.atMs() - KEEP_MS;
            while (!events.isEmpty() && (events.size() > MAX_EVENTS || events.peekFirst().atMs() < cutoff)) {
                events.removeFirst();
            }
        }
    }

    /**
     * Command label plus its first argument, at most 30 characters. Enough to
     * tell /locate from /rtp; secrets and private messages keep the label only.
     */
    static String redactCommand(String raw) {
        if (raw == null) {
            return "";
        }
        String command = raw.strip();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        String[] parts = command.split("\\s+", 3);
        String label = parts[0];
        String bare = label.contains(":") ? label.substring(label.indexOf(':') + 1) : label;
        if (parts.length == 1 || REDACTED_COMMANDS.contains(bare.toLowerCase(Locale.ROOT))) {
            return "/" + label;
        }
        String argument = parts[1].length() > MAX_ARGUMENT_LENGTH
                ? parts[1].substring(0, MAX_ARGUMENT_LENGTH) + "…" : parts[1];
        return "/" + label + " " + argument + (parts.length > 2 ? " …" : "");
    }

    private static String worldName(World world) {
        return world == null ? "?" : world.getName();
    }

    /* ------------------------------------------------------------------ */
    /* Read API                                                             */
    /* ------------------------------------------------------------------ */

    /** Events between {@code fromMs} and {@code toMs}, oldest first. */
    public List<Event> events(long fromMs, long toMs) {
        List<Event> result = new ArrayList<>();
        synchronized (events) {
            for (Event e : events) {
                if (e.atMs() >= fromMs && e.atMs() <= toMs) {
                    result.add(e);
                }
            }
        }
        return result;
    }

    /** The position snapshot closest to {@code atMs}, empty when none is near (Folia, or older than the buffer). */
    public List<PlayerPosition> playersAt(long atMs) {
        PlayerSnapshot best = null;
        synchronized (snapshots) {
            for (PlayerSnapshot s : snapshots) {
                if (best == null || Math.abs(s.atMs() - atMs) < Math.abs(best.atMs() - atMs)) {
                    best = s;
                }
            }
        }
        if (best == null || Math.abs(best.atMs() - atMs) > SNAPSHOT_PERIOD_TICKS * 50L * 2) {
            return List.of();
        }
        return best.players();
    }
}
