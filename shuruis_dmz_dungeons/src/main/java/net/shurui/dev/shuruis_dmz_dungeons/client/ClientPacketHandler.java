package net.shurui.dev.shuruis_dmz_dungeons.client;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.AdvancedSpawnerScreen;
import net.shurui.dev.shuruis_dmz_dungeons.client.gui.DungeonConfigScreen;
import net.shurui.dev.shuruis_dmz_dungeons.client.render.SpawnerHighlightRenderer;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;

import java.util.ArrayList;
import java.util.List;

// client-side handlers for our S2C packets. kept separate so the dedicated server never classloads
// GUI/renderer types (only reached via DistExecutor).
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void openSpawnerEditor(BlockPos pos, SpawnerConfig config) {
        AdvancedSpawnerScreen.open(pos, config);
    }

    public static void highlightSpawners(List<BlockPos> positions, int durationTicks) {
        SpawnerHighlightRenderer.highlight(positions, durationTicks);
    }

    // store the per-floor crate tier colour inputs so the crate block colour handlers can paint each crate locally.
    public static void applyCrateTiers(
            List<net.shurui.dev.shuruis_dmz_dungeons.network.SyncCrateTiersPacket.CrateTierInfo> entries) {
        net.shurui.dev.shuruis_dmz_dungeons.client.ClientCrateTiers.apply(entries);
    }

    // open the /rg dungeon config editor, rebuilding the floor configs from the NBT the server sent.
    public static void openDungeonConfig(boolean keyUnlocked, boolean pvp, boolean kiBlockDestruction,
                                         boolean blockEditing, int timeLimitSeconds, int cooldownSeconds,
                                         List<CompoundTag> floorNbts) {
        List<DungeonFloorConfig> floors = new ArrayList<>();
        for (CompoundTag t : floorNbts) {
            floors.add(DungeonFloorConfig.load(t));
        }
        DungeonConfigScreen.open(keyUnlocked, pvp, kiBlockDestruction, blockEditing,
                timeLimitSeconds, cooldownSeconds, floors);
    }

    /** Start the loot roll above a crate the local player just opened. */
    public static void startCrateRoll(net.minecraft.core.BlockPos pos,
                                      java.util.List<net.minecraft.world.item.ItemStack> candidates,
                                      net.minecraft.world.item.ItemStack result, int durationTicks) {
        ClientCrateRolls.start(pos, candidates, result, durationTicks);
    }
}
