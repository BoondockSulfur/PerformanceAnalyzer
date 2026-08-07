package dev.boondock.performanceanalyzer.timing;

import dev.boondock.performanceanalyzer.platform.Scheduling;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Measures the real time spent in other plugins' event handlers.
 *
 * <p>Every {@link RegisteredListener} of every other plugin is replaced by a
 * timing wrapper that delegates to the original and accumulates nanoseconds
 * per plugin (and per event type). This replaces the old
 * {@code listeners * 2 + tasks * 3} "risk score", which counted registrations
 * instead of cost and therefore reliably blamed the wrong plugin.</p>
 *
 * <p>Cost: two {@code System.nanoTime()} calls per handler invocation — the
 * same overhead Bukkit's former timings system had. Can be disabled in the
 * config; wrappers unregister together with their owning plugin because they
 * report the original plugin as owner.</p>
 */
public final class ListenerTimings implements Listener {

    private static final int MAX_EVENT_TYPES_PER_PLUGIN = 50;
    private static final long ROTATE_MS = 10_000L;

    /** Measured load of one plugin over the last completed window. */
    public record PluginLoad(
            String pluginName,
            double msPerSec,
            double percentOfThreadBudget,
            long callsPerSec,
            String topEvent,
            double topEventMsPerSec) {
    }

    private static final class Counters {
        final LongAdder nanos = new LongAdder();
        final LongAdder calls = new LongAdder();
        final Map<String, LongAdder> nanosPerEvent = new ConcurrentHashMap<>();

        void add(String eventName, long deltaNanos) {
            nanos.add(deltaNanos);
            calls.increment();
            LongAdder perEvent = nanosPerEvent.get(eventName);
            if (perEvent == null && nanosPerEvent.size() < MAX_EVENT_TYPES_PER_PLUGIN) {
                perEvent = nanosPerEvent.computeIfAbsent(eventName, k -> new LongAdder());
            }
            if (perEvent != null) {
                perEvent.add(deltaNanos);
            }
        }
    }

    private final class TimedListener extends RegisteredListener {
        private final RegisteredListener delegate;
        private final Counters counters;

        TimedListener(RegisteredListener delegate, Counters counters) {
            super(delegate.getListener(), (listener, event) -> {
            }, delegate.getPriority(), delegate.getPlugin(), delegate.isIgnoringCancelled());
            this.delegate = delegate;
            this.counters = counters;
        }

        @Override
        public void callEvent(Event event) throws EventException {
            long start = System.nanoTime();
            try {
                delegate.callEvent(event);
            } finally {
                counters.add(event.getEventName(), System.nanoTime() - start);
            }
        }
    }

    private final Plugin plugin;
    private final Map<String, Counters> perPlugin = new ConcurrentHashMap<>();
    private volatile List<PluginLoad> loads = List.of();
    private volatile boolean active;
    private long lastRotateAtMs = System.currentTimeMillis();
    private ScheduledTask rotateTask;
    private ScheduledTask rescanTask;

    public ListenerTimings(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        active = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Injection touches HandlerLists, do it from the global thread.
        Scheduling.runGlobal(plugin, this::injectAll);
        // Late registrations (plugins registering listeners lazily) are
        // caught by the enable hook plus a slow periodic rescan.
        rescanTask = Scheduling.runGlobalRepeating(plugin, this::injectAll, 20L * 60, 20L * 60);
        lastRotateAtMs = System.currentTimeMillis();
        rotateTask = Scheduling.runAsyncRepeating(plugin, this::rotate, ROTATE_MS, ROTATE_MS);
    }

    public void stop() {
        active = false;
        Scheduling.cancel(rotateTask);
        Scheduling.cancel(rescanTask);
        rotateTask = null;
        rescanTask = null;
        HandlerList.unregisterAll(this);
        uninjectAll();
    }

    /** Per-plugin measured load from the last completed window, heaviest first. */
    public List<PluginLoad> loads() {
        return loads;
    }

    public boolean isActive() {
        return active;
    }

    @org.bukkit.event.EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (active) {
            // Listeners are usually registered during onEnable; wrap right after.
            Scheduling.runGlobal(plugin, this::injectAll);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Injection                                                           */
    /* ------------------------------------------------------------------ */

    private void injectAll() {
        if (!active) {
            return;
        }
        for (HandlerList handlerList : HandlerList.getHandlerLists()) {
            RegisteredListener[] listeners = handlerList.getRegisteredListeners();
            List<RegisteredListener> toWrap = new ArrayList<>(0);
            for (RegisteredListener listener : listeners) {
                if (listener instanceof TimedListener || listener.getPlugin() == plugin) {
                    continue;
                }
                toWrap.add(listener);
            }
            for (RegisteredListener original : toWrap) {
                Counters counters = perPlugin.computeIfAbsent(
                        original.getPlugin().getName(), k -> new Counters());
                handlerList.unregister(original);
                handlerList.register(new TimedListener(original, counters));
            }
        }
    }

    private void uninjectAll() {
        for (HandlerList handlerList : HandlerList.getHandlerLists()) {
            for (RegisteredListener listener : handlerList.getRegisteredListeners()) {
                if (listener instanceof TimedListener timed) {
                    handlerList.unregister(timed);
                    handlerList.register(timed.delegate);
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Window rotation                                                     */
    /* ------------------------------------------------------------------ */

    private void rotate() {
        long now = System.currentTimeMillis();
        double seconds = Math.max(1.0, (now - lastRotateAtMs) / 1000.0);
        lastRotateAtMs = now;

        List<PluginLoad> result = new ArrayList<>(perPlugin.size());
        for (var entry : perPlugin.entrySet()) {
            Counters counters = entry.getValue();
            long nanos = counters.nanos.sumThenReset();
            long calls = counters.calls.sumThenReset();

            String topEvent = null;
            long topNanos = 0;
            for (var eventEntry : counters.nanosPerEvent.entrySet()) {
                long eventNanos = eventEntry.getValue().sumThenReset();
                if (eventNanos > topNanos) {
                    topNanos = eventNanos;
                    topEvent = eventEntry.getKey();
                }
            }

            if (nanos <= 0) {
                continue;
            }
            double msPerSec = nanos / 1_000_000.0 / seconds;
            // One server thread has a budget of 1000 ms/s, so ms/s is
            // directly the percentage of one thread consumed (/10).
            result.add(new PluginLoad(
                    entry.getKey(),
                    msPerSec,
                    msPerSec / 10.0,
                    (long) (calls / seconds),
                    topEvent == null ? "-" : topEvent,
                    topNanos / 1_000_000.0 / seconds));
        }
        result.sort(Comparator.comparingDouble(PluginLoad::msPerSec).reversed());
        this.loads = List.copyOf(result);
    }
}
