package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.DungeonEventFloorHook;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

/**
 * The Dungeons module's implementation of the core {@link DungeonEventFloorHook}, so the private event engine can
 * stand up a temporary dungeon floor without either half naming the other. Registered once at module load; the key
 * calls the static delegators.
 *
 * <p>An event floor is a normal procedural floor with a VIRTUAL number (from {@link DungeonFloorLayout#EVENT_FLOOR_BASE})
 * whose per-floor state lives in the LOCAL {@link DungeonEventFloorState} (never synced, never in the shared floor
 * list). Because {@link DungeonFloors} routes every virtual number to that store, the whole existing build path
 * ({@link DungeonFloorManager#teleportToFloor}, the key's floor build)
 * works unchanged: the floor is rolled, built and landed exactly like an ordinary floor, only at the negative-z lane.
 */
public final class DungeonEventFloorHookImpl implements DungeonEventFloorHook.Provider {

    private DungeonEventFloorHookImpl() {
    }

    public static void register() {
        DungeonEventFloorHook.register(new DungeonEventFloorHookImpl());
    }

    @Override
    public List<DungeonEventFloorHook.FloorChoice> floorChoices(MinecraftServer server) {
        List<DungeonEventFloorHook.FloorChoice> out = new ArrayList<>();
        DungeonFloors floors = DungeonFloors.get(server);
        if (floors == null) {
            return out;
        }
        for (int n = 1; n <= floors.count(); n++) {
            DungeonFloorConfig c = floors.get(n);
            String theme = c == null || c.theme == null ? "?" : c.theme;
            String type = c != null && c.isBoss() ? " boss" : "";
            out.add(new DungeonEventFloorHook.FloorChoice(n, "Floor " + n + " (" + theme + type + ")"));
        }
        return out;
    }

    @Override
    public CompoundTag copyFloorConfig(MinecraftServer server, int floorNumber) {
        DungeonFloors floors = DungeonFloors.get(server);
        DungeonFloorConfig c = floors == null ? null : floors.get(floorNumber);
        return c == null ? new CompoundTag() : c.save(new CompoundTag());
    }

    @Override
    public boolean enter(ServerPlayer player, String slotKey, CompoundTag cfg) {
        if (player == null || slotKey == null || slotKey.isEmpty() || cfg == null || cfg.isEmpty()) {
            return false;
        }
        MinecraftServer server = player.getServer();
        DungeonEventFloorState state = DungeonEventFloorState.get(server);
        if (state == null) {
            return false;
        }
        // Allocate (or reuse) this slot's virtual floor number and (re)store its config every entry, so a reaped slot
        // rebuilds cleanly from the event's own config. The build path reads the config back through DungeonFloors.
        int number = state.allocate(slotKey);
        state.setConfig(number, DungeonFloorConfig.load(cfg));

        // Reuse the ordinary teleport/build path verbatim: DungeonFloors routes this virtual number to the event store,
        // and DungeonRoomGen resolves its origin through DungeonFloorLayout.cellCentre (the negative-z lane). This also
        // means an event floor is gated by the dungeon key exactly like every other floor.
        DungeonFloorManager.Result result = DungeonFloorManager.teleportToFloor(player, number);
        if (result == DungeonFloorManager.Result.SUCCESS) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] Event floor '{}' entered as virtual floor {} for {}.",
                    Shuruis_dmz_dungeons.MODID, slotKey, number, player.getGameProfile().getName());
            return true;
        }
        Shuruis_dmz_dungeons.LOGGER.info("[{}] Event floor '{}' (virtual floor {}) not entered: {}.",
                Shuruis_dmz_dungeons.MODID, slotKey, number, result);
        return false;
    }

    @Override
    public void reap(MinecraftServer server, Set<String> liveSlotKeys) {
        DungeonEventFloorState state = DungeonEventFloorState.get(server);
        if (state == null) {
            return;
        }
        List<Integer> removed = state.reap(liveSlotKeys);
        if (!removed.isEmpty()) {
            Shuruis_dmz_dungeons.LOGGER.info("[{}] Reaped {} event floor(s): virtual numbers {} (abandoned terrain "
                    + "left on the event lane).", Shuruis_dmz_dungeons.MODID, removed.size(), removed);
        }
    }
}
