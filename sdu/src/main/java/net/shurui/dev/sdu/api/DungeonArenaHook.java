package net.shurui.dev.sdu.api;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Core hook for the Dungeons module's shared arena dimensions, so the Raid Bosses rift system can host a
 * tear arena as a cell in a dungeon theme dimension without naming the Dungeons module directly.
 *
 * <p>This lives in core (sdu), which every module can read from. The Dungeons module registers its
 * implementation at load; Raid Bosses reads it. When Dungeons is not installed the hook is unset:
 * {@link #arenaLevel} returns null and the rift refuses to open with a clear log line, while the guest
 * add/remove calls are no-ops. So no module classloads another module, and rifts (which genuinely need a
 * dungeon dimension to fight in) simply cannot start when Dungeons is absent; everything else in Raid
 * Bosses works.
 */
public final class DungeonArenaHook {

    /** Implemented by the Dungeons module. */
    public interface Provider {
        /** Mark a player as a dungeon arena guest, exempt from the dungeon timer and cooldown eject. */
        void guestAdd(UUID id);

        /** Drop a player's guest status once their run's arena is released. */
        void guestRemove(UUID id);

        /** Resolve (creating if needed) the dungeon theme dimension for {@code theme}; null when unavailable. */
        ServerLevel arenaLevel(MinecraftServer server, String theme);
    }

    private static volatile Provider impl;

    private DungeonArenaHook() {
    }

    /** Called once by the Dungeons module at load. */
    public static void register(Provider p) {
        impl = p;
    }

    /** True when the Dungeons module is present and has registered its provider. */
    public static boolean available() {
        return impl != null;
    }

    public static void guestAdd(UUID id) {
        Provider p = impl;
        if (p != null) {
            p.guestAdd(id);
        }
    }

    public static void guestRemove(UUID id) {
        Provider p = impl;
        if (p != null) {
            p.guestRemove(id);
        }
    }

    /** The dungeon theme dimension for {@code theme}, or null when the Dungeons module is absent. */
    public static ServerLevel arenaLevel(MinecraftServer server, String theme) {
        Provider p = impl;
        return p == null ? null : p.arenaLevel(server, theme);
    }
}
