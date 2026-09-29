package net.shurui.dev.shuruis_dmz_dungeons.event;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.shuruis_dmz_dungeons.command.DungeonCommand;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;

// forge-bus events: the public /rg dungeon command tree, state sync registration, crate tier sync and pending reward
// delivery. The floor build layer (S22a) and the crate reward drain (S22b) are private and ticked by the Ragnarok Key.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_dungeons")
public final class ForgeEvents {

    private ForgeEvents() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        DungeonCommand.register(event.getDispatcher());
    }

    // register the dungeon's admin-editable SavedData with the cross-server state engine once the level exists, so a
    // floor/rule/warp edit (and per-player crate loot + ticket unlocks) propagates between servers without a restart.
    // Self-gated per module and guarded, so a disabled or absent piece cannot throw here.
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonStateSync.register();
    }

    // sync the per-floor crate tier inputs to a player entering a themed dungeon dim, so the client paints each
    // crate's per-viewer rarity locally. The rarity itself is never sent per block.
    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            syncCrateTiersIfDungeon(sp, event.getTo());
            // A reward banked while the floor was full (or carried over a hop) is delivered the moment the player is
            // somewhere their inventory can safely take it, without waiting for a relog. Only ever adds to the
            // inventory, so it is safe on arrival in any dimension.
            net.shurui.dev.shuruis_dmz_dungeons.dungeon.PendingRewards.deliver(sp);
        }
    }

    // also cover a re-login while already standing in a themed dungeon dim (no dimension-change fires then). Default
    // (NORMAL) priority is deliberately AFTER Shurui's Utilities' HIGHEST vault restore, so any pending reward carried
    // in the restored persistent data is present before we try to hand it over.
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            syncCrateTiersIfDungeon(sp, sp.level().dimension());
            net.shurui.dev.shuruis_dmz_dungeons.dungeon.PendingRewards.deliver(sp);
        }
    }

    // drop any half-received chunked config save for a player who left mid-transfer, so a dropped stream never
    // lingers in server memory (see SaveDungeonConfigChunkPacket).
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            net.shurui.dev.shuruis_dmz_dungeons.network.SaveDungeonConfigChunkPacket.forget(sp.getUUID());
        }
    }

    private static void syncCrateTiersIfDungeon(ServerPlayer player, ResourceKey<Level> dim) {
        // themed floor dims only: the legacy superflat dungeon dim has no procedural crates.
        if (dim == null || DungeonDimensions.DUNGEON.equals(dim) || !DungeonDimensions.isAnyDungeon(dim)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        DungeonFloors floors = DungeonFloors.get(server);
        if (floors != null) {
            SddNet.sendCrateTiers(player, floors);
        }
    }
}
