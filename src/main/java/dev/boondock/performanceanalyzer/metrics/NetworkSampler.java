package dev.boondock.performanceanalyzer.metrics;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Player latency, sampled every few seconds.
 *
 * <p>Lag that players feel but the server does not have - 20 TPS, short
 * ticks, yet everyone rubber-bands - is a network problem, and nothing in the
 * tick measurement can see it. The ping the server already tracks per
 * connection can. Two patterns matter:
 * <ul>
 *   <li>one player far above their own usual ping: their connection;</li>
 *   <li>most players jumping at the same moment: the server's uplink or the
 *       hoster's network, which is worth an alert of its own.</li>
 * </ul>
 */
public final class NetworkSampler {

    /** Current latency picture. */
    public record Snapshot(int players, double avgPingMs, int worstPingMs, String worstPlayer, int highPingPlayers) {
        public static final Snapshot EMPTY = new Snapshot(0, 0, 0, "", 0);
    }

    /** Most players' ping jumped at the same sample. */
    public record NetworkSpike(long atMs, int affected, int players, double avgBeforeMs, double avgNowMs) {
    }

    /** One player's ping jumped far above their own usual value while the server was healthy. */
    public record PlayerJump(long atMs, String player, int pingMs, double usualMs) {
    }

    static final long SAMPLE_INTERVAL_MS = 5_000L;
    private static final int HISTORY = 24; // two minutes
    private static final int MIN_HISTORY = 6;
    private static final int HIGH_PING_MS = 250;
    private static final int SPIKES_KEPT = 20;

    private final Plugin plugin;
    private final Map<UUID, ArrayDeque<Integer>> history = new ConcurrentHashMap<>();
    private final ArrayDeque<NetworkSpike> spikes = new ArrayDeque<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile Consumer<NetworkSpike> spikeListener;
    private volatile Consumer<PlayerJump> playerJumpListener;
    /** Players currently above their usual ping; reported once per jump, not every sample. */
    private final java.util.Set<UUID> jumping = ConcurrentHashMap.newKeySet();
    private volatile java.util.function.BooleanSupplier serverHealthy = () -> true;
    private ScheduledTask task;

    public NetworkSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void setSpikeListener(Consumer<NetworkSpike> listener) {
        this.spikeListener = listener;
    }

    /**
     * Single-player jumps. A shared spike needs two players, which a small
     * server rarely has online; a dropping home connection affects one.
     */
    public void setPlayerJumpListener(Consumer<PlayerJump> listener) {
        this.playerJumpListener = listener;
    }

    /**
     * A lagging server answers keep-alives late, so every player's ping rises
     * with it. Shared spikes only count while the server itself is healthy.
     */
    public void setServerHealthy(java.util.function.BooleanSupplier serverHealthy) {
        this.serverHealthy = serverHealthy;
    }

    public void start() {
        long ticks = SAMPLE_INTERVAL_MS / 50;
        task = Scheduling.runGlobalRepeating(plugin, this::sample, ticks, ticks);
    }

    public void stop() {
        Scheduling.cancel(task);
        task = null;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public List<NetworkSpike> spikes(long windowMs) {
        long since = System.currentTimeMillis() - windowMs;
        List<NetworkSpike> result = new ArrayList<>();
        synchronized (spikes) {
            for (NetworkSpike s : spikes) {
                if (s.atMs() >= since) {
                    result.add(s);
                }
            }
        }
        return result;
    }

    private void sample() {
        int players = 0;
        long sum = 0;
        int worst = 0;
        String worstName = "";
        int high = 0;
        int eligible = 0;
        int jumped = 0;
        double medianSum = 0;
        double jumpedNowSum = 0;
        java.util.Set<UUID> online = new java.util.HashSet<>();
        List<PlayerJump> playerJumps = new ArrayList<>();
        long now = System.currentTimeMillis();

        for (Player player : Bukkit.getOnlinePlayers()) {
            int ping = player.getPing();
            if (ping < 0) {
                continue;
            }
            online.add(player.getUniqueId());
            players++;
            sum += ping;
            if (ping > worst) {
                worst = ping;
                worstName = player.getName();
            }
            if (ping >= HIGH_PING_MS) {
                high++;
            }
            ArrayDeque<Integer> h = history.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
            if (h.size() >= MIN_HISTORY) {
                eligible++;
                double median = median(h);
                medianSum += median;
                if (isJump(ping, median)) {
                    jumped++;
                    jumpedNowSum += ping;
                    if (jumping.add(player.getUniqueId())) {
                        playerJumps.add(new PlayerJump(now, player.getName(), ping, median));
                    }
                } else if (ping < Math.max(median * 1.5, median + 50.0)) {
                    jumping.remove(player.getUniqueId());
                }
            }
            h.addLast(ping);
            while (h.size() > HISTORY) {
                h.removeFirst();
            }
        }
        history.keySet().retainAll(online);
        jumping.retainAll(online);

        snapshot = players == 0 ? Snapshot.EMPTY
                : new Snapshot(players, (double) sum / players, worst, worstName, high);

        if (isSharedSpike(jumped, eligible) && serverHealthy.getAsBoolean()) {
            NetworkSpike spike = new NetworkSpike(System.currentTimeMillis(), jumped, players,
                    medianSum / eligible, jumpedNowSum / jumped);
            synchronized (spikes) {
                spikes.addFirst(spike);
                while (spikes.size() > SPIKES_KEPT) {
                    spikes.removeLast();
                }
            }
            Consumer<NetworkSpike> l = spikeListener;
            if (l != null) {
                l.accept(spike);
            }
        }

        // A lagging server answers every keep-alive late; that is the server's
        // problem, not the player's connection.
        Consumer<PlayerJump> jl = playerJumpListener;
        if (jl != null && !playerJumps.isEmpty() && serverHealthy.getAsBoolean()) {
            playerJumps.forEach(jl);
        }
    }

    /** Far above this player's own usual ping, not merely above everyone else's. */
    static boolean isJump(int ping, double median) {
        return ping >= Math.max(median * 2.0, median + 150.0);
    }

    /** At least two players and at least half of those with enough history. */
    static boolean isSharedSpike(int jumped, int eligible) {
        return jumped >= 2 && jumped * 2 >= eligible;
    }

    private static double median(ArrayDeque<Integer> values) {
        int[] sorted = values.stream().mapToInt(Integer::intValue).toArray();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
    }
}
