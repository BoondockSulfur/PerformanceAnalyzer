package dev.boondock.performanceanalyzer.metrics;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import dev.boondock.performanceanalyzer.platform.Scheduling;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sampling profiler for the main thread.
 *
 * <p>Listener timings only see event handlers, the activity counters only see
 * events, and neither sees what the tick actually spends its time on: a
 * plugin's repeating task, villager AI, a hopper block entity, a chunk load
 * the main thread has to wait for. Reading the main thread's stack a few
 * dozen times per second answers that directly - the share of samples that
 * land in a piece of code is the share of tick time it costs.
 *
 * <p>Samples are only taken while a tick is running (between
 * {@link ServerTickStartEvent} and {@link ServerTickEndEvent}); the idle time
 * between ticks is neither sampled nor paid for. Each sample is one
 * {@link Thread#getStackTrace()} on the main thread, a thread-local handshake
 * in current JDKs, so at the default 20 ms interval the overhead stays well
 * below a percent of one core.
 *
 * <p>Paper only: Folia has no single main thread and fires no tick events.
 */
public final class MainThreadSampler implements Listener {

    /** What a sample was attributed to. */
    public enum Category {
        /** Plugin code; {@link Cost#detail()} says task, listener, command or other. */
        PLUGIN,
        /** Ticking one entity type (villager AI, item entities, ...). */
        ENTITY,
        /** Ticking one block entity type (hoppers, furnaces, spawners, ...). */
        BLOCK_ENTITY,
        /** Loading, saving or generating chunks on (or for) the main thread. */
        CHUNK_IO,
        /** Redstone and neighbor updates. */
        REDSTONE,
        /** Anything else in the server itself. */
        SERVER
    }

    /** Plugin code by how it was entered. */
    public enum PluginKind { TASK, LISTENER, COMMAND, OTHER }

    /**
     * What the main thread was doing when it was not computing. A tick that
     * waits - on a database socket, a file, a lock - is a hang, and the cure
     * is different from code that is merely slow: move the work off the main
     * thread instead of making it faster.
     */
    public enum WaitKind { NONE, NETWORK, FILE_IO, CHUNK_LOAD, LOCK, SLEEP }

    /** One code path inside a target, with its share of that target's samples. */
    public record Hotspot(String path, double percent) {
    }

    /** Estimated main-thread cost of one attribution target. */
    public record Cost(Category category, String label, String detail, double msPerSec, double percent,
                       List<Hotspot> hotspots, WaitKind waitKind, double waitPercent) {
    }

    /** How the work behind a slow tick was entered. */
    public enum TriggerKind { NONE, PLAYER_ACTION, COMMAND, PLUGIN, ENTITY, BLOCK_ENTITY, CHUNK_TICK, WORLD_SAVE }

    /**
     * The entry point of a slow tick: the outermost frame that says who asked
     * for the work (a player's packet, a command, a plugin, a ticking entity),
     * plus {@code via}, the game code in between that names the action itself
     * (an ender eye, a cartographer trade, a loot table).
     */
    public record Trigger(TriggerKind kind, String what, String detail, String via) {
        public static final Trigger NONE = new Trigger(TriggerKind.NONE, "", "", "");
    }

    /** One tick that ran longer than the slow-tick threshold. */
    public record SlowTick(long atMs, double durationMs, Category category, String label, String detail,
                           List<String> frames, WaitKind waitKind, boolean betweenTicks, Trigger trigger) {
    }

    record Attribution(Category category, String label, String detail) {
    }

    private static final long WINDOW_MS = 10_000L;
    private static final int WINDOWS_KEPT = 6;
    private static final int SLOW_TICKS_KEPT = 20;
    /** Deep enough to reach the packet handler, command or plugin task that started a stall. */
    private static final int FRAMES_KEPT = 60;
    /** Frames per hotspot path: enough to see the method and who called it. */
    private static final int PATH_FRAMES = 3;
    private static final int MAX_PATHS_PER_TARGET = 64;
    private static final int HOTSPOTS_REPORTED = 3;

    /** Entity base classes whose name says nothing about the entity type. */
    private static final Set<String> GENERIC_ENTITY_CLASSES = Set.of(
            "Entity", "LivingEntity", "Mob", "PathfinderMob", "AgeableMob", "Animal", "Monster",
            "TamableAnimal", "AbstractGolem", "WaterAnimal", "FlyingMob", "AmbientCreature");

    private final Plugin plugin;
    private volatile Thread mainThread;
    private volatile Thread samplerThread;
    private volatile boolean running;
    private volatile long intervalMs = 20;
    private volatile double slowTickMs = 100.0;

    /** Package prefix to plugin name, longest prefix first. */
    private volatile List<Map.Entry<String, String>> pluginPrefixes = List.of();

    /**
     * The main thread is expected to idle this long after a tick before it
     * counts as busy between ticks. Paper runs console/RCON commands and
     * queued tasks there, outside the tick events, so a hang in one of them
     * is invisible to tick timings and only shows up here.
     */
    private static final long BETWEEN_TICKS_AFTER_NANOS = 50_000_000L;

    /** nanoTime at the start of the running tick; 0 between ticks. */
    private volatile long tickStartNanos;
    /** nanoTime at the end of the last tick; 0 before the first. */
    private volatile long tickEndNanos;
    private volatile long tickNumber;

    // Written by the sampler thread only.
    private Map<Attribution, Integer> currentWindow = new HashMap<>();
    private Map<Attribution, Map<String, Integer>> currentPaths = new HashMap<>();
    private Map<Attribution, int[]> currentWaits = new HashMap<>();
    private long windowStartedAtMs;
    private long slowTickNumber = -1;
    private boolean slowTickBetween;
    private double slowTickDurationMs;
    private Map<Attribution, Integer> slowTickCounts = new HashMap<>();
    private final Map<Attribution, StackTraceElement[]> slowTickStacks = new HashMap<>();

    private record Window(Map<Attribution, Integer> counts, Map<Attribution, Map<String, Integer>> paths,
                          Map<Attribution, int[]> waits, long durationMs) {
    }

    private final ArrayDeque<Window> windows = new ArrayDeque<>();
    private final ArrayDeque<SlowTick> slowTicks = new ArrayDeque<>();
    private volatile java.util.function.Consumer<SlowTick> slowTickListener;

    public MainThreadSampler(Plugin plugin) {
        this.plugin = plugin;
    }

    public void configure(long intervalMs, double slowTickMs) {
        this.intervalMs = Math.max(5, intervalMs);
        this.slowTickMs = slowTickMs;
    }

    public boolean isRunning() {
        return running;
    }

    /**
     * Called on the sampler thread for every finished slow tick. Kept slow
     * ticks live only in memory; this is how they reach the database.
     */
    public void setSlowTickListener(java.util.function.Consumer<SlowTick> listener) {
        this.slowTickListener = listener;
    }

    public synchronized void start() {
        if (running || Scheduling.FOLIA) {
            return;
        }
        running = true;
        pluginPrefixes = buildPluginPrefixes(plugin.getServer().getPluginManager().getPlugins());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // The global region scheduler runs on the main thread on Paper.
        Scheduling.runGlobal(plugin, () -> {
            mainThread = Thread.currentThread();
            Thread t = new Thread(this::loop, "PerformanceAnalyzer-MainThreadSampler");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            samplerThread = t;
            t.start();
        });
    }

    public synchronized void stop() {
        running = false;
        HandlerList.unregisterAll(this);
        Thread t = samplerThread;
        if (t != null) {
            t.interrupt();
        }
        samplerThread = null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTickStart(ServerTickStartEvent event) {
        tickNumber++;
        tickStartNanos = System.nanoTime();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        tickEndNanos = System.nanoTime();
        tickStartNanos = 0;
    }

    /* ------------------------------------------------------------------ */
    /* Sampling loop                                                        */
    /* ------------------------------------------------------------------ */

    private void loop() {
        windowStartedAtMs = System.currentTimeMillis();
        while (running) {
            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            try {
                sampleOnce();
                long now = System.currentTimeMillis();
                if (now - windowStartedAtMs >= WINDOW_MS) {
                    rotate(now);
                }
            } catch (Throwable ignored) {
                // A failed sample is a lost sample, never a reason to stop.
            }
        }
    }

    private void sampleOnce() {
        Thread main = mainThread;
        if (main == null) {
            return;
        }
        long start = tickStartNanos;
        boolean between = start == 0;
        if (between) {
            // Between ticks the thread normally sleeps until the next one is
            // due; only a gap that has run past that is worth a look.
            long end = tickEndNanos;
            if (end == 0 || System.nanoTime() - end < BETWEEN_TICKS_AFTER_NANOS) {
                finishSlowTick();
                return;
            }
            start = end;
        }
        // In-tick and between-tick stretches get distinct ids, so a stall
        // after tick n is not merged into tick n itself.
        long tick = tickNumber * 2 + (between ? 1 : 0);
        StackTraceElement[] stack = main.getStackTrace();
        // The tick may have started or ended while the stack was being read.
        if ((between ? tickStartNanos != 0 || tickEndNanos != start : tickStartNanos != start)
                || stack.length == 0) {
            return;
        }
        if (between && isIdle(stack)) {
            finishSlowTick();
            return;
        }
        List<Map.Entry<String, String>> prefixes = pluginPrefixes;
        Attribution a = attribute(stack, prefixes);
        currentWindow.merge(a, 1, Integer::sum);
        Map<String, Integer> paths = currentPaths.computeIfAbsent(a, k -> new HashMap<>());
        String path = codePath(stack, prefixes);
        if (paths.containsKey(path) || paths.size() < MAX_PATHS_PER_TARGET) {
            paths.merge(path, 1, Integer::sum);
        }
        currentWaits.computeIfAbsent(a, k -> new int[WaitKind.values().length])[waitKind(stack).ordinal()]++;

        double elapsedMs = (System.nanoTime() - start) / 1_000_000.0;
        if (tick != slowTickNumber) {
            finishSlowTick();
        }
        if (elapsedMs >= slowTickMs) {
            if (slowTickNumber != tick) {
                slowTickNumber = tick;
                slowTickCounts = new HashMap<>();
                slowTickStacks.clear();
            }
            slowTickStacks.putIfAbsent(a, stack);
            slowTickDurationMs = elapsedMs;
            slowTickBetween = between;
            slowTickCounts.merge(a, 1, Integer::sum);
        }
    }

    private void finishSlowTick() {
        if (slowTickNumber < 0 || slowTickCounts.isEmpty()) {
            slowTickNumber = -1;
            return;
        }
        Attribution top = slowTickCounts.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        List<String> frames = new ArrayList<>();
        StackTraceElement[] stack = slowTickStacks.get(top);
        for (int i = 0; stack != null && i < stack.length && frames.size() < FRAMES_KEPT; i++) {
            frames.add(stack[i].getClassName() + "." + stack[i].getMethodName()
                    + (stack[i].getLineNumber() > 0 ? ":" + stack[i].getLineNumber() : ""));
        }
        WaitKind wait = stack == null ? WaitKind.NONE : waitKind(stack);
        Trigger trigger = stack == null ? Trigger.NONE : trigger(stack, pluginPrefixes);
        SlowTick tick = new SlowTick(System.currentTimeMillis(), slowTickDurationMs,
                top.category(), top.label(), top.detail(), List.copyOf(frames), wait, slowTickBetween, trigger);
        synchronized (slowTicks) {
            slowTicks.addFirst(tick);
            while (slowTicks.size() > SLOW_TICKS_KEPT) {
                slowTicks.removeLast();
            }
        }
        slowTickNumber = -1;
        slowTickCounts = new HashMap<>();
        slowTickStacks.clear();
        var listener = slowTickListener;
        if (listener != null) {
            try {
                listener.accept(tick);
            } catch (RuntimeException ignored) {
                // A failing consumer must not stop the sampler.
            }
        }
    }

    private void rotate(long now) {
        Window w = new Window(Map.copyOf(currentWindow), currentPaths, currentWaits, now - windowStartedAtMs);
        synchronized (windows) {
            windows.addFirst(w);
            while (windows.size() > WINDOWS_KEPT) {
                windows.removeLast();
            }
        }
        currentWindow = new HashMap<>();
        currentPaths = new HashMap<>();
        currentWaits = new HashMap<>();
        windowStartedAtMs = now;
    }

    /* ------------------------------------------------------------------ */
    /* Attribution                                                          */
    /* ------------------------------------------------------------------ */

    static List<Map.Entry<String, String>> buildPluginPrefixes(Plugin[] plugins) {
        Map<String, String> byPrefix = new HashMap<>();
        Map<String, String> fullPackage = new HashMap<>();
        for (Plugin p : plugins) {
            String pkg = p.getClass().getPackageName();
            fullPackage.put(p.getName(), pkg);
            // com.sk89q.worldguard.bukkit -> com.sk89q.worldguard: the main
            // class often sits in a platform sub-package while the work is
            // done one level up. Short packages are kept whole so that
            // me.author.a and me.author.b do not collapse into me.author.
            String[] parts = pkg.split("\\.");
            String prefix = parts.length >= 4 ? pkg.substring(0, pkg.lastIndexOf('.')) : pkg;
            String clash = byPrefix.putIfAbsent(prefix, p.getName());
            if (clash != null && !clash.equals(p.getName())) {
                byPrefix.remove(prefix);
                byPrefix.put(fullPackage.get(clash), clash);
                byPrefix.put(pkg, p.getName());
            }
        }
        List<Map.Entry<String, String>> list = new ArrayList<>();
        for (var e : byPrefix.entrySet()) {
            if (!e.getKey().isEmpty()) {
                list.add(Map.entry(e.getKey() + ".", e.getValue()));
            }
        }
        list.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        return List.copyOf(list);
    }

    /**
     * Decides what a stack sample was busy with. Index 0 is the innermost
     * frame. Plugin code wins over everything: a plugin listener reached from
     * an entity tick is the plugin's cost, not the entity's.
     */
    static Attribution attribute(StackTraceElement[] stack, List<Map.Entry<String, String>> pluginPrefixes) {
        for (int i = 0; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("dev.boondock.performanceanalyzer.timing.")) {
                continue; // the listener-timing wrapper, not real work
            }
            String pluginName = pluginOf(cls, pluginPrefixes);
            if (pluginName != null) {
                return new Attribution(Category.PLUGIN, pluginName, pluginKind(stack, i).name());
            }
        }
        for (int i = 0; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            String method = stack[i].getMethodName();
            if (cls.equals("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity") && method.equals("tick")) {
                return new Attribution(Category.BLOCK_ENTITY, blockEntityType(stack, i), "");
            }
            if (cls.equals("net.minecraft.server.level.ServerLevel")
                    && (method.equals("tickNonPassenger") || method.equals("tickPassenger"))) {
                return new Attribution(Category.ENTITY, entityType(stack, i), "");
            }
        }
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            // Checked before generation: StructureCheck lives in levelgen, but
            // a structure search (ender eye, /locate, explorer maps) reads
            // saved chunks and happens just as well in a pregenerated world.
            if (isStructureSearch(frame)) {
                return new Attribution(Category.CHUNK_IO, "structure_search", "");
            }
            if (cls.startsWith("net.minecraft.world.level.levelgen.")) {
                return new Attribution(Category.CHUNK_IO, "generation", "");
            }
            if (cls.startsWith("ca.spottedleaf.moonrise.patches.chunk_system.")
                    || cls.startsWith("net.minecraft.world.level.chunk.storage.")
                    || (cls.equals("net.minecraft.server.level.ServerChunkCache") && frame.getMethodName().startsWith("getChunk"))) {
                return new Attribution(Category.CHUNK_IO, "load_save", "");
            }
            if (cls.startsWith("net.minecraft.world.level.redstone.")
                    || frame.getMethodName().equals("neighborChanged")
                    || frame.getMethodName().equals("updateNeighborsAt")) {
                return new Attribution(Category.REDSTONE, "redstone", "");
            }
        }
        return new Attribution(Category.SERVER, "other", "");
    }

    static boolean isStructureSearch(StackTraceElement frame) {
        String cls = frame.getClassName();
        String method = frame.getMethodName();
        return cls.equals("net.minecraft.world.level.levelgen.structure.StructureCheck")
                || (cls.equals("net.minecraft.world.level.StructureManager") && method.equals("checkStructurePresence"))
                || method.equals("findNearestMapStructure")
                || method.equals("getNearestGeneratedStructure");
    }

    /** Game code whose frame names the action behind a slow tick. */
    private static final String[] VIA_PREFIXES = {
            "net.minecraft.world.item.", "net.minecraft.world.entity.", "net.minecraft.world.level.block.",
            "net.minecraft.world.level.storage.loot.", "net.minecraft.server.commands.",
            "net.minecraft.world.level.NaturalSpawner"};

    /**
     * Walks the stack outwards from the stall to the first frame that says
     * who started the work. Plugin code wins, as in {@link #attribute}.
     */
    static Trigger trigger(StackTraceElement[] stack, List<Map.Entry<String, String>> pluginPrefixes) {
        String via = "";
        for (int i = 0; i < stack.length; i++) {
            StackTraceElement frame = stack[i];
            String cls = frame.getClassName();
            String method = frame.getMethodName();
            if (cls.startsWith("dev.boondock.performanceanalyzer.timing.")) {
                continue;
            }
            String pluginName = pluginOf(cls, pluginPrefixes);
            if (pluginName != null) {
                return new Trigger(TriggerKind.PLUGIN, pluginName, pluginKind(stack, i).name(), via);
            }
            if (cls.startsWith("net.minecraft.server.network.") && cls.endsWith("PacketListenerImpl")
                    && method.startsWith("handle")) {
                return new Trigger(TriggerKind.PLAYER_ACTION, method, "", via);
            }
            if (cls.equals("net.minecraft.commands.Commands")
                    && (method.equals("performCommand") || method.equals("performPrefixedCommand"))) {
                return new Trigger(TriggerKind.COMMAND, "", "", via);
            }
            if (cls.equals("net.minecraft.server.level.ServerLevel")
                    && (method.equals("tickNonPassenger") || method.equals("tickPassenger"))) {
                return new Trigger(TriggerKind.ENTITY, entityType(stack, i), "", via);
            }
            if (cls.equals("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity") && method.equals("tick")) {
                return new Trigger(TriggerKind.BLOCK_ENTITY, blockEntityType(stack, i), "", via);
            }
            if ((cls.equals("net.minecraft.server.level.ServerChunkCache") && method.equals("tickChunks"))
                    || (cls.equals("net.minecraft.server.level.ServerLevel") && method.equals("tickChunk"))) {
                return new Trigger(TriggerKind.CHUNK_TICK, "", "", via);
            }
            if ((cls.equals("net.minecraft.server.MinecraftServer")
                    && (method.equals("saveAllChunks") || method.equals("saveEverything")))
                    || (cls.equals("net.minecraft.server.level.ServerLevel") && method.equals("save"))) {
                return new Trigger(TriggerKind.WORLD_SAVE, "", "", via);
            }
            if (via.isEmpty() && !isJdk(cls)) {
                for (String prefix : VIA_PREFIXES) {
                    if (cls.startsWith(prefix)) {
                        via = shortFrame(frame);
                        break;
                    }
                }
            }
        }
        return via.isEmpty() ? Trigger.NONE : new Trigger(TriggerKind.NONE, "", "", via);
    }

    private static boolean isJdk(String cls) {
        return cls.startsWith("java.") || cls.startsWith("jdk.") || cls.startsWith("sun.") || cls.startsWith("com.sun.");
    }

    /** The main thread parked in waitUntilNextTick: the normal sleep before the next tick. */
    static boolean isIdle(StackTraceElement[] stack) {
        if (stack.length == 0 || !isJdk(stack[0].getClassName())) {
            return false;
        }
        for (StackTraceElement frame : stack) {
            if (frame.getMethodName().equals("waitUntilNextTick")) {
                return true;
            }
            if (!isJdk(frame.getClassName()) && !frame.getClassName().startsWith("net.minecraft.util.thread.")
                    && !frame.getMethodName().equals("managedBlock")) {
                return false;
            }
        }
        return false;
    }

    /**
     * Classifies the innermost JDK frames: if the thread sits in a socket
     * read, a file write, a park or a sleep, it is waiting, not computing.
     */
    static WaitKind waitKind(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String cls = frame.getClassName();
            if (!isJdk(cls)) {
                return WaitKind.NONE;
            }
            String method = frame.getMethodName();
            if (cls.equals("java.lang.Thread") && method.startsWith("sleep")) {
                return WaitKind.SLEEP;
            }
            if (cls.startsWith("java.net.") || cls.startsWith("sun.nio.ch.Net") || cls.contains("Socket")) {
                return WaitKind.NETWORK;
            }
            if (cls.startsWith("sun.nio.ch.FileDispatcher") || cls.startsWith("sun.nio.ch.FileChannel")
                    || cls.startsWith("java.io.File") || cls.startsWith("java.io.RandomAccessFile")
                    || cls.startsWith("sun.nio.fs.")) {
                return WaitKind.FILE_IO;
            }
            if (method.startsWith("park") || (cls.equals("java.lang.Object") && method.startsWith("wait"))) {
                return chunkLoadBelow(stack) ? WaitKind.CHUNK_LOAD : WaitKind.LOCK;
            }
        }
        return WaitKind.NONE;
    }

    /** A park under a synchronous chunk (data) load: the main thread waits for the disk. */
    private static boolean chunkLoadBelow(StackTraceElement[] stack) {
        for (int i = 0; i < Math.min(stack.length, 16); i++) {
            String cls = stack[i].getClassName();
            String method = stack[i].getMethodName();
            if ((cls.equals("net.minecraft.server.level.ServerChunkCache")
                    && (method.equals("syncLoad") || method.startsWith("getChunk")))
                    || cls.startsWith("ca.spottedleaf.moonrise.patches.chunk_system.")
                    || cls.startsWith("net.minecraft.world.level.chunk.storage.")
                    // Structure searches block on saved chunk data being read from disk.
                    || cls.equals("net.minecraft.world.level.levelgen.structure.StructureCheck")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The code a sample was in: the innermost frames outside the JDK, with
     * line numbers, and - for plugin samples - the plugin's own frame when it
     * sits further out (a plugin calling into a database driver).
     */
    static String codePath(StackTraceElement[] stack, List<Map.Entry<String, String>> pluginPrefixes) {
        int start = 0;
        while (start < stack.length && isJdk(stack[start].getClassName())) {
            start++;
        }
        if (start >= stack.length) {
            start = 0;
        }
        StringBuilder sb = new StringBuilder();
        int end = Math.min(stack.length, start + PATH_FRAMES);
        for (int i = start; i < end; i++) {
            if (i > start) {
                sb.append(" < ");
            }
            sb.append(shortFrame(stack[i]));
        }
        for (int i = end; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (!cls.startsWith("dev.boondock.performanceanalyzer.timing.") && pluginOf(cls, pluginPrefixes) != null) {
                sb.append(" < … < ").append(shortFrame(stack[i]));
                break;
            }
        }
        return sb.toString();
    }

    private static String shortFrame(StackTraceElement frame) {
        String cls = frame.getClassName();
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        return simple + "." + frame.getMethodName() + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
    }

    private static String pluginOf(String cls, List<Map.Entry<String, String>> pluginPrefixes) {
        for (var e : pluginPrefixes) {
            if (cls.startsWith(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private static PluginKind pluginKind(StackTraceElement[] stack, int pluginFrame) {
        for (int i = pluginFrame + 1; i < stack.length; i++) {
            String cls = stack[i].getClassName();
            if (cls.contains("CraftScheduler") || cls.contains("CraftTask")) {
                return PluginKind.TASK;
            }
            if (cls.contains("RegisteredListener") || cls.contains("EventExecutor")
                    || stack[i].getMethodName().equals("callEvent")) {
                return PluginKind.LISTENER;
            }
            if (cls.contains("CommandMap") || cls.contains("PluginCommand") || cls.contains("BukkitCommandWrapper")) {
                return PluginKind.COMMAND;
            }
        }
        return PluginKind.OTHER;
    }

    /**
     * The entity's tick() is the frame just inside tickNonPassenger. Stack
     * frames name the declaring class, so a cow shows up as Mob.tick; the
     * first frame further in that names a concrete entity class is the best
     * available type.
     */
    private static String entityType(StackTraceElement[] stack, int tickFrame) {
        for (int i = tickFrame - 1; i >= 0; i--) {
            String cls = stack[i].getClassName();
            if (!cls.startsWith("net.minecraft.world.entity.") || cls.contains(".ai.")) {
                continue;
            }
            String simple = simpleName(cls);
            if (!GENERIC_ENTITY_CLASSES.contains(simple)) {
                return simple;
            }
        }
        return "Mob";
    }

    private static String blockEntityType(StackTraceElement[] stack, int tickFrame) {
        for (int i = tickFrame - 1; i >= 0; i--) {
            String cls = stack[i].getClassName();
            if (cls.startsWith("net.minecraft.world.level.block.entity.")) {
                return simpleName(cls);
            }
        }
        return "BlockEntity";
    }

    private static String simpleName(String cls) {
        String simple = cls.substring(cls.lastIndexOf('.') + 1);
        int inner = simple.indexOf('$');
        return inner > 0 ? simple.substring(0, inner) : simple;
    }

    /* ------------------------------------------------------------------ */
    /* Read API                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Estimated cost per attribution target over the last {@code windowCount}
     * completed 10-second windows, highest first.
     */
    public List<Cost> costs(int windowCount) {
        List<Window> picked = new ArrayList<>();
        synchronized (windows) {
            for (Window w : windows) {
                if (picked.size() >= windowCount) {
                    break;
                }
                picked.add(w);
            }
        }
        if (picked.isEmpty()) {
            return List.of();
        }
        Map<Attribution, Integer> sum = new HashMap<>();
        Map<Attribution, Map<String, Integer>> paths = new HashMap<>();
        Map<Attribution, int[]> waits = new HashMap<>();
        long durationMs = 0;
        int samples = 0;
        for (Window w : picked) {
            durationMs += w.durationMs();
            for (var e : w.counts().entrySet()) {
                sum.merge(e.getKey(), e.getValue(), Integer::sum);
                samples += e.getValue();
            }
            for (var e : w.paths().entrySet()) {
                Map<String, Integer> target = paths.computeIfAbsent(e.getKey(), k -> new HashMap<>());
                e.getValue().forEach((path, n) -> target.merge(path, n, Integer::sum));
            }
            for (var e : w.waits().entrySet()) {
                int[] target = waits.computeIfAbsent(e.getKey(), k -> new int[WaitKind.values().length]);
                for (int i = 0; i < target.length; i++) {
                    target[i] += e.getValue()[i];
                }
            }
        }
        if (samples == 0 || durationMs <= 0) {
            return List.of();
        }
        double seconds = durationMs / 1000.0;
        long interval = intervalMs;
        List<Cost> result = new ArrayList<>(sum.size());
        for (var e : sum.entrySet()) {
            Attribution a = e.getKey();
            int count = e.getValue();
            List<Hotspot> hotspots = paths.getOrDefault(a, Map.of()).entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(HOTSPOTS_REPORTED)
                    .map(p -> new Hotspot(p.getKey(), p.getValue() * 100.0 / count))
                    .toList();
            int[] w = waits.getOrDefault(a, new int[WaitKind.values().length]);
            WaitKind dominantWait = WaitKind.NONE;
            for (WaitKind kind : WaitKind.values()) {
                if (kind != WaitKind.NONE && w[kind.ordinal()] > (dominantWait == WaitKind.NONE ? 0 : w[dominantWait.ordinal()])) {
                    dominantWait = kind;
                }
            }
            double waitPercent = dominantWait == WaitKind.NONE ? 0 : w[dominantWait.ordinal()] * 100.0 / count;
            result.add(new Cost(a.category(), a.label(), a.detail(),
                    count * interval / seconds, count * 100.0 / samples, hotspots, dominantWait, waitPercent));
        }
        result.sort(Comparator.comparingDouble(Cost::msPerSec).reversed());
        return result;
    }

    /** Slow ticks seen within the last {@code windowMs}, newest first. */
    public List<SlowTick> slowTicks(long windowMs) {
        long since = System.currentTimeMillis() - windowMs;
        List<SlowTick> result = new ArrayList<>();
        synchronized (slowTicks) {
            for (SlowTick t : slowTicks) {
                if (t.atMs() >= since) {
                    result.add(t);
                }
            }
        }
        return result;
    }

    /** All kept slow ticks, newest first. */
    public Collection<SlowTick> allSlowTicks() {
        synchronized (slowTicks) {
            return List.copyOf(slowTicks);
        }
    }
}
