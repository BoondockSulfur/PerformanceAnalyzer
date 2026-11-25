package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SampleAttributionTest {

    private static final List<Map.Entry<String, String>> PLUGINS = List.of(
            Map.entry("com.sk89q.worldguard.", "WorldGuard"),
            Map.entry("dev.boondock.performanceanalyzer.", "PerformanceAnalyzer"));

    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }

    private static MainThreadSampler.Attribution attribute(StackTraceElement... stack) {
        return MainThreadSampler.attribute(stack, PLUGINS);
    }

    @Test
    void pluginTaskIsRecognised() {
        var a = attribute(
                f("com.sk89q.worldguard.session.SessionManager", "tick"),
                f("org.bukkit.craftbukkit.scheduler.CraftTask", "run"),
                f("org.bukkit.craftbukkit.scheduler.CraftScheduler", "mainThreadHeartbeat"),
                f("net.minecraft.server.MinecraftServer", "tickServer"));
        assertEquals(MainThreadSampler.Category.PLUGIN, a.category());
        assertEquals("WorldGuard", a.label());
        assertEquals("TASK", a.detail());
    }

    @Test
    void timingWrapperIsSkippedAndListenerRecognised() {
        var a = attribute(
                f("com.sk89q.worldguard.bukkit.listener.EventAbstractionListener", "onBlockBreak"),
                f("dev.boondock.performanceanalyzer.timing.ListenerTimings$TimedListener", "callEvent"),
                f("org.bukkit.plugin.RegisteredListener", "callEvent"));
        assertEquals("WorldGuard", a.label());
        assertEquals("LISTENER", a.detail());
    }

    @Test
    void entityTypeIsTheFirstConcreteClass() {
        var a = attribute(
                f("net.minecraft.world.entity.ai.behavior.AcquirePoi", "start"),
                f("net.minecraft.world.entity.npc.Villager", "customServerAiStep"),
                f("net.minecraft.world.entity.Mob", "serverAiStep"),
                f("net.minecraft.world.entity.LivingEntity", "tick"),
                f("net.minecraft.server.level.ServerLevel", "tickNonPassenger"));
        assertEquals(MainThreadSampler.Category.ENTITY, a.category());
        assertEquals("Villager", a.label());
    }

    @Test
    void blockEntityTypeIsNamed() {
        var a = attribute(
                f("net.minecraft.world.level.block.entity.HopperBlockEntity", "pushItemsTick"),
                f("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity", "tick"),
                f("net.minecraft.world.level.Level", "tickBlockEntities"));
        assertEquals(MainThreadSampler.Category.BLOCK_ENTITY, a.category());
        assertEquals("HopperBlockEntity", a.label());
    }

    @Test
    void chunkWaitIsChunkIo() {
        var a = attribute(
                f("java.util.concurrent.locks.LockSupport", "park"),
                f("net.minecraft.server.MinecraftServer", "managedBlock"),
                f("ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler", "scheduleChunkLoad"),
                f("net.minecraft.server.level.ServerChunkCache", "getChunk"));
        assertEquals(MainThreadSampler.Category.CHUNK_IO, a.category());
        assertEquals("load_save", a.label());
    }

    @Test
    void anythingElseIsServer() {
        var a = attribute(f("net.minecraft.server.MinecraftServer", "tickServer"));
        assertEquals(MainThreadSampler.Category.SERVER, a.category());
    }

    @Test
    void blockingSocketReadIsANetworkHang() {
        StackTraceElement[] stack = {
                new StackTraceElement("sun.nio.ch.Net", "poll", null, -2),
                new StackTraceElement("sun.nio.ch.NioSocketImpl", "read", null, 300),
                new StackTraceElement("com.mysql.cj.protocol.ReadAheadInputStream", "fill", null, 107),
                new StackTraceElement("com.sk89q.worldguard.storage.SqlStore", "load", null, 88),
                new StackTraceElement("org.bukkit.craftbukkit.scheduler.CraftTask", "run", null, 82)};
        assertEquals(MainThreadSampler.WaitKind.NETWORK, MainThreadSampler.waitKind(stack));
        assertEquals("ReadAheadInputStream.fill:107 < SqlStore.load:88 < CraftTask.run:82",
                MainThreadSampler.codePath(stack, PLUGINS));
    }

    @Test
    void pluginFrameIsAppendedWhenFurtherOut() {
        StackTraceElement[] stack = {
                f("org.sqlite.core.NativeDB", "step"),
                f("org.sqlite.jdbc3.JDBC3PreparedStatement", "execute"),
                f("com.zaxxer.hikari.pool.ProxyPreparedStatement", "execute"),
                f("com.zaxxer.hikari.pool.HikariProxyPreparedStatement", "execute"),
                f("com.sk89q.worldguard.storage.SqlStore", "save")};
        assertEquals("NativeDB.step < JDBC3PreparedStatement.execute < ProxyPreparedStatement.execute"
                        + " < … < SqlStore.save",
                MainThreadSampler.codePath(stack, PLUGINS));
        assertEquals(MainThreadSampler.WaitKind.NONE, MainThreadSampler.waitKind(stack));
    }

    @Test
    void parkIsALockWait() {
        StackTraceElement[] stack = {
                f("jdk.internal.misc.Unsafe", "park"),
                f("java.util.concurrent.locks.LockSupport", "park"),
                f("net.minecraft.server.MinecraftServer", "managedBlock")};
        assertEquals(MainThreadSampler.WaitKind.LOCK, MainThreadSampler.waitKind(stack));
    }

    @Test
    void syncChunkLoadFromAPluginIsNamed() {
        StackTraceElement[] stack = {
                f("jdk.internal.misc.Unsafe", "park"),
                f("java.util.concurrent.locks.LockSupport", "parkNanos"),
                f("net.minecraft.util.thread.BlockableEventLoop", "waitForTasks"),
                f("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
                f("net.minecraft.server.level.ServerChunkCache", "syncLoad"),
                f("com.sk89q.worldguard.Teleporter", "find")};
        assertEquals(MainThreadSampler.WaitKind.CHUNK_LOAD, MainThreadSampler.waitKind(stack));
    }

    @Test
    void sleepingBeforeTheNextTickIsIdle() {
        StackTraceElement[] idle = {
                f("jdk.internal.misc.Unsafe", "park"),
                f("java.util.concurrent.locks.LockSupport", "parkNanos"),
                f("net.minecraft.util.thread.BlockableEventLoop", "waitForTasks"),
                f("net.minecraft.server.MinecraftServer", "managedBlock"),
                f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")};
        StackTraceElement[] busy = {
                f("net.minecraft.world.entity.Entity", "kill"),
                f("net.minecraft.server.commands.KillCommand", "kill"),
                f("net.minecraft.server.MinecraftServer", "waitUntilNextTick")};
        org.junit.jupiter.api.Assertions.assertTrue(MainThreadSampler.isIdle(idle));
        org.junit.jupiter.api.Assertions.assertFalse(MainThreadSampler.isIdle(busy));
    }
}
