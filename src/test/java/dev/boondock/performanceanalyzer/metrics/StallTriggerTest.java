package dev.boondock.performanceanalyzer.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StallTriggerTest {

    private static final List<Map.Entry<String, String>> PLUGINS = List.of(
            Map.entry("me.example.rtp.", "BetterRTP"));

    private static StackTraceElement f(String cls, String method) {
        return new StackTraceElement(cls, method, null, -1);
    }

    /** The innermost frames of the 24.11. production stall. */
    private static final StackTraceElement[] STRUCTURE_WAIT = {
            f("jdk.internal.misc.Unsafe", "park"),
            f("java.util.concurrent.locks.LockSupport", "park"),
            f("java.util.concurrent.CompletableFuture$Signaller", "block"),
            f("java.util.concurrent.ForkJoinPool", "unmanagedBlock"),
            f("java.util.concurrent.ForkJoinPool", "managedBlock"),
            f("java.util.concurrent.CompletableFuture", "waitingGet"),
            f("java.util.concurrent.CompletableFuture", "join"),
            f("net.minecraft.world.level.levelgen.structure.StructureCheck", "tryLoadFromStorage"),
            f("net.minecraft.world.level.levelgen.structure.StructureCheck", "checkStart"),
            f("net.minecraft.world.level.StructureManager", "checkStructurePresence"),
            f("net.minecraft.world.level.chunk.ChunkGenerator", "getNearestGeneratedStructure"),
            f("net.minecraft.world.level.chunk.ChunkGenerator", "findNearestMapStructure"),
            f("net.minecraft.server.level.ServerLevel", "findNearestMapStructure")};

    private static StackTraceElement[] concat(StackTraceElement[] a, StackTraceElement... b) {
        StackTraceElement[] r = new StackTraceElement[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    void structureSearchIsNotCalledGeneration() {
        var a = MainThreadSampler.attribute(STRUCTURE_WAIT, PLUGINS);
        assertEquals(MainThreadSampler.Category.CHUNK_IO, a.category());
        assertEquals("structure_search", a.label());
    }

    @Test
    void structureSearchWaitsForChunkDataNotALock() {
        assertEquals(MainThreadSampler.WaitKind.CHUNK_LOAD, MainThreadSampler.waitKind(STRUCTURE_WAIT));
    }

    @Test
    void enderEyeIsNamedAsPlayerAction() {
        var t = MainThreadSampler.trigger(concat(STRUCTURE_WAIT,
                f("net.minecraft.world.item.EnderEyeItem", "use"),
                f("net.minecraft.world.item.ItemStack", "use"),
                f("net.minecraft.server.level.ServerPlayerGameMode", "useItem"),
                f("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleUseItem"),
                f("net.minecraft.network.protocol.PacketUtils", "lambda$ensureRunningOnSameThread$0")), PLUGINS);
        assertEquals(MainThreadSampler.TriggerKind.PLAYER_ACTION, t.kind());
        assertEquals("handleUseItem", t.what());
        assertEquals("EnderEyeItem.use", t.via());
    }

    @Test
    void cartographerTradeIsNamedViaTheTrade() {
        var t = MainThreadSampler.trigger(concat(STRUCTURE_WAIT,
                f("net.minecraft.world.entity.npc.VillagerTrades$TreasureMapForEmeralds", "getOffer"),
                f("net.minecraft.world.entity.npc.Villager", "updateTrades"),
                f("net.minecraft.world.entity.npc.Villager", "mobInteract"),
                f("net.minecraft.server.network.ServerGamePacketListenerImpl", "handleInteract")), PLUGINS);
        assertEquals(MainThreadSampler.TriggerKind.PLAYER_ACTION, t.kind());
        assertEquals("VillagerTrades$TreasureMapForEmeralds.getOffer", t.via());
    }

    @Test
    void pluginBeatsTheServerEntryPoint() {
        var t = MainThreadSampler.trigger(concat(STRUCTURE_WAIT,
                f("me.example.rtp.Teleporter", "findSafeSpot"),
                f("org.bukkit.craftbukkit.scheduler.CraftTask", "run"),
                f("org.bukkit.craftbukkit.scheduler.CraftScheduler", "mainThreadHeartbeat")), PLUGINS);
        assertEquals(MainThreadSampler.TriggerKind.PLUGIN, t.kind());
        assertEquals("BetterRTP", t.what());
        assertEquals("TASK", t.detail());
    }

    @Test
    void mobSpawningIsAChunkTick() {
        var t = MainThreadSampler.trigger(new StackTraceElement[]{
                f("net.minecraft.world.level.StructureManager", "structureHasPieceAt"),
                f("net.minecraft.world.level.NaturalSpawner", "spawnCategoryForPosition"),
                f("net.minecraft.server.level.ServerChunkCache", "tickChunks")}, PLUGINS);
        assertEquals(MainThreadSampler.TriggerKind.CHUNK_TICK, t.kind());
        assertEquals("NaturalSpawner.spawnCategoryForPosition", t.via());
    }

    @Test
    void unknownEntryYieldsNone() {
        var t = MainThreadSampler.trigger(new StackTraceElement[]{
                f("jdk.internal.misc.Unsafe", "park"),
                f("net.minecraft.server.MinecraftServer", "runServer")}, PLUGINS);
        assertEquals(MainThreadSampler.Trigger.NONE, t);
    }
}
