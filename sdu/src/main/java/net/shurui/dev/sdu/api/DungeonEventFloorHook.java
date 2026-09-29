package net.shurui.dev.sdu.api;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Core hook for ADDITIVE event dungeon floors, so the private event engine (in the Ragnarok Key) can stand up a
 * temporary dungeon floor while an event runs without either half naming the other. The Dungeons module registers
 * the {@link Provider}; the key calls the static delegators. This mirrors {@link DungeonArenaHook}: the surface
 * lives in core (sdu), the implementation in Dungeons, and the caller (the key) never classloads a module class.
 *
 * <p>An event floor is a normal procedural floor with a VIRTUAL floor number from 1000, built on a dedicated
 * negative-z lane so it can never collide with the ordinary floor grid (z &gt;= 0) or the rift arena lane
 * (z = -1,000,000). Its per-floor state lives in the Dungeons module's own LOCAL store, never in the shared floor
 * list and never in the {@code dungeons:floors} cross-shard sync, so a world with no event running is byte-identical.
 *
 * <p>When Dungeons is absent every delegator is inert: {@link #available()} is false, {@link #floorChoices} and
 * {@link #copyFloorConfig} return empty, {@link #enter} returns false (so {@code /event dungeon} declines cleanly),
 * and {@link #reap} is a no-op. So the event engine keeps working with dungeon floors simply unavailable.
 */
public final class DungeonEventFloorHook
{
    /** One floor an admin may clone into an event: its number and a short human label. */
    public record FloorChoice(int floor, String label) {}

    /** Implemented by the Dungeons module. */
    public interface Provider
    {
        /** The ordinary floors an admin may copy into an event (number + label). */
        List<FloorChoice> floorChoices(MinecraftServer server);

        /** Snapshot floor {@code floorNumber}'s config as an opaque tag the event stores verbatim (empty if none). */
        CompoundTag copyFloorConfig(MinecraftServer server, int floorNumber);

        /**
         * Materialise (build or resume) the event floor keyed by {@code slotKey} from the opaque config {@code cfg}
         * and teleport the player onto it. Returns false when the floor could not be built (the dungeon key is
         * absent, or the config was empty). {@code slotKey} is stable for one event floor across restarts.
         */
        boolean enter(ServerPlayer player, String slotKey, CompoundTag cfg);

        /** Drop every event floor whose slot key is NOT in {@code liveSlotKeys} (event ended, or a boot cleanup). */
        void reap(MinecraftServer server, Set<String> liveSlotKeys);
    }

    private static volatile Provider impl;

    private DungeonEventFloorHook() {}

    /** Called once by the Dungeons module at load. */
    public static void register(Provider p)
    {
        impl = p;
    }

    /** True when the Dungeons module is present and has registered its provider. */
    public static boolean available()
    {
        return impl != null;
    }

    public static List<FloorChoice> floorChoices(MinecraftServer server)
    {
        Provider p = impl;
        return p == null ? Collections.emptyList() : p.floorChoices(server);
    }

    public static CompoundTag copyFloorConfig(MinecraftServer server, int floorNumber)
    {
        Provider p = impl;
        return p == null ? new CompoundTag() : p.copyFloorConfig(server, floorNumber);
    }

    public static boolean enter(ServerPlayer player, String slotKey, CompoundTag cfg)
    {
        Provider p = impl;
        return p != null && p.enter(player, slotKey, cfg);
    }

    public static void reap(MinecraftServer server, Set<String> liveSlotKeys)
    {
        Provider p = impl;
        if (p != null)
        {
            p.reap(server, liveSlotKeys);
        }
    }
}
