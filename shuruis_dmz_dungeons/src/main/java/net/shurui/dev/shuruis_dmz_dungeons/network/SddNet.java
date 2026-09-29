package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;

import java.util.List;

// our SimpleChannel, one channel with a running packetId++. Naming: Open.../...Request opens a screen,
// Save... for edits, Sync... for S2C pushes. register() runs from common setup.
public final class SddNet {

    private static final String PROTOCOL = "1";
    private static SimpleChannel channel;
    private static int packetId = 0;

    private SddNet() {
    }

    public static void register() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Shuruis_dmz_dungeons.MODID, "dungeons_main"),
                () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

        channel.registerMessage(packetId++, OpenSpawnerEditorPacket.class,
                OpenSpawnerEditorPacket::encode, OpenSpawnerEditorPacket::decode, OpenSpawnerEditorPacket::handle);
        channel.registerMessage(packetId++, SaveSpawnerPacket.class,
                SaveSpawnerPacket::encode, SaveSpawnerPacket::decode, SaveSpawnerPacket::handle);
        channel.registerMessage(packetId++, RequestNpcDefaultsPacket.class,
                RequestNpcDefaultsPacket::encode, RequestNpcDefaultsPacket::decode, RequestNpcDefaultsPacket::handle);
        channel.registerMessage(packetId++, SyncNpcDefaultsPacket.class,
                SyncNpcDefaultsPacket::encode, SyncNpcDefaultsPacket::decode, SyncNpcDefaultsPacket::handle);

        channel.registerMessage(packetId++, HighlightSpawnersPacket.class,
                HighlightSpawnersPacket::encode, HighlightSpawnersPacket::decode, HighlightSpawnersPacket::handle);

        // Appended (ids 5, 6), so they never renumber the spawner/highlight packets. OpenDungeonConfig is S2C,
        // SaveDungeonConfig is C2S.
        channel.registerMessage(packetId++, OpenDungeonConfigPacket.class,
                OpenDungeonConfigPacket::encode, OpenDungeonConfigPacket::decode, OpenDungeonConfigPacket::handle);
        channel.registerMessage(packetId++, SaveDungeonConfigPacket.class,
                SaveDungeonConfigPacket::encode, SaveDungeonConfigPacket::decode, SaveDungeonConfigPacket::handle);
        // Crate rarity is rolled per player per refresh window (position + epoch + viewer UUID + weights); this
        // carries the inputs the client cannot derive: each floor's tier weights, refresh window, theme.
        // Appended (id 7), so it never renumbers the packets above.
        channel.registerMessage(packetId++, SyncCrateTiersPacket.class,
                SyncCrateTiersPacket::encode, SyncCrateTiersPacket::decode, SyncCrateTiersPacket::handle);
        channel.registerMessage(packetId++, CrateRollPacket.class,
                CrateRollPacket::encode, CrateRollPacket::decode, CrateRollPacket::handle);

        // Chunked C2S transport: the floor list can exceed the 32767-byte serverbound payload ceiling (a
        // disconnect on decode), so the client sends ordered byte frames and the server reassembles them.
        //
        // REGISTERED LAST ON PURPOSE, and it has to stay last. Both acceptors are PROTOCOL::equals on the literal
        // "1", so client and server on DIFFERENT jars still accept each other's channel: the version string does
        // not change when a packet is added. Ids come from the running packetId++ counter, so inserting anywhere
        // but the end shifts every later id, and the two sides would decode with the WRONG handler instead of
        // refusing to talk. At the end every existing id stays put; the only disagreement is a trailing id an
        // older client never sends or receives.
        channel.registerMessage(packetId++, SaveDungeonConfigChunkPacket.class,
                SaveDungeonConfigChunkPacket::encode, SaveDungeonConfigChunkPacket::decode,
                SaveDungeonConfigChunkPacket::handle);
    }

    public static void sendToServer(Object packet) {
        channel.sendToServer(packet);
    }

    public static void sendToPlayer(Object packet, ServerPlayer player) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    public static void openSpawnerEditor(ServerPlayer player, BlockPos pos, SpawnerConfig config) {
        sendToPlayer(new OpenSpawnerEditorPacket(pos, config), player);
    }

    public static void highlightSpawners(ServerPlayer player, List<BlockPos> positions, int durationTicks) {
        sendToPlayer(new HighlightSpawnersPacket(positions, durationTicks), player);
    }

    // push per-floor crate tier inputs to one player, on dungeon entry or re-login inside one.
    public static void sendCrateTiers(ServerPlayer player,
                                      net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors floors) {
        sendToPlayer(SyncCrateTiersPacket.forAllFloors(floors), player);
    }

    // unused, kept only to mirror DmzNet's level helpers
    public static ServerLevel dungeon(MinecraftServer server) {
        return DungeonDimensions.level(server);
    }
}
