package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.shurui.dev.sdu.api.DungeonArenaHook;

/**
 * Dungeons-side implementation of the core {@link DungeonArenaHook}, delegating to this module's own
 * {@link DungeonArenaGuests} and {@link DungeonDimensions}. Registered once at module load so the Raid
 * Bosses rift system can host a tear arena in a dungeon theme dimension without a direct dependency on
 * this module.
 */
public final class DungeonArenaHookImpl implements DungeonArenaHook.Provider {

    private DungeonArenaHookImpl() {
    }

    /** Install this module's provider into the core hook. */
    public static void register() {
        DungeonArenaHook.register(new DungeonArenaHookImpl());
    }

    @Override
    public void guestAdd(UUID id) {
        DungeonArenaGuests.add(id);
    }

    @Override
    public void guestRemove(UUID id) {
        DungeonArenaGuests.remove(id);
    }

    @Override
    public ServerLevel arenaLevel(MinecraftServer server, String theme) {
        if (server == null) {
            return null;
        }
        return DungeonDimensions.getOrCreateLevel(server, DungeonDimensions.levelForTheme(theme));
    }
}
