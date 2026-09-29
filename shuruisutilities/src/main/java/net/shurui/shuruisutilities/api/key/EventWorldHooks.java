package net.shurui.shuruisutilities.api.key;

import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.dev.sdu.api.key.EventHooks;
import net.shurui.shuruisutilities.npcregion.NpcRegion;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

/**
 * Core-side hook for the SU-only seams of the PRIVATE timed-event engine (logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). Its sibling {@link EventHooks} (in {@code sdu}) carries the seams the module trees
 * touch; this one carries the seams that live in {@code shuruisutilities}: NPC regions, the SU crate and airdrop
 * systems, region-kill drops, join-time cleanup of stray event mobs, and the editor/hub dispatch that the packets
 * (128-130) route to. It marks the SAME {@link KeyFeatures} id as {@link EventHooks} ({@link EventHooks#FEATURE_ID}),
 * because both are installed together when the key brings the engine up.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, a region is live (a normal
 * region behaves as it always has), every bonus list is empty, the spawn multiplier is 1.0, no entity is discarded
 * on join, and every editor/hub/state entry is a no-op. The key swaps the whole impl in via {@link #install(Impl)}.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class EventWorldHooks
{
    private EventWorldHooks() {}

    /** The behaviour the key installs. Every method has an inert keyless default. */
    public interface Impl
    {
        /** Whether the event engine is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * Whether this NPC region should run right now. Keyless (this default): a normal region runs as before,
         * an eventOnly region stays dark, because event content is inert without the key. The key overrides this
         * to also run an eventOnly region while an active event lists it.
         */
        default boolean regionLive(NpcRegion region)
        {
            return region == null || !region.isEventOnly();
        }

        /** Extra spawn configs an active event appends to a region's roster (from a template region). Keyless: none. */
        default List<NpcSpawnConfig> extraNpcs(NpcRegion region)
        {
            return Collections.emptyList();
        }

        /** The spawn-rate multiplier an active event applies to a region's caps. Keyless: 1.0 (no change). */
        default double spawnMultiplier(NpcRegion region)
        {
            return 1.0;
        }

        /** Extra drops an active event adds when an SU named crate is opened. Keyless: none. */
        default List<ItemStack> suCrateBonus(ServerPlayer player, String crateName)
        {
            return Collections.emptyList();
        }

        /** Extra loot an active event rolls into an airdrop chest for a region. Keyless: none. */
        default List<ItemStack> airdropBonus(NpcRegion region, RandomSource random)
        {
            return Collections.emptyList();
        }

        /** Extra drops an active event awards for an event region-mob kill. Keyless: none. */
        default List<ItemStack> regionKillBonus(Entity entity, ServerPlayer killer)
        {
            return Collections.emptyList();
        }

        /** Whether a stray event mob should be discarded when a player joins (event over). Keyless: false. */
        default boolean discardOnJoin(Entity entity)
        {
            return false;
        }

        // ---- editor / hub dispatch (packets 128-130 route here) ------------------------------------------

        /** Open the event editor for this player: the list when {@code eventId} is blank, else that event. Keyless: no-op. */
        default void openEditor(ServerPlayer player, String eventId)
        {
        }

        /** An editor list action (new / delete / start / stop / auto ...). Keyless: no-op. */
        default void editorAction(ServerPlayer player, String action, String arg)
        {
        }

        /** Persist an edited event (packet 129 body). Keyless: no-op. */
        default void saveDef(ServerPlayer player, CompoundTag def)
        {
        }

        /** Open the player-facing event hub. Keyless: no-op. */
        default void openHub(ServerPlayer player)
        {
        }

        /** A hub action (claim / shop buy / quest track ...). Keyless: no-op. */
        default void hubAction(ServerPlayer player, String action, String arg)
        {
        }

        /** Push the active-event state (packet 130) to this player, e.g. on login or on change. Keyless: no-op. */
        default void sendState(ServerPlayer player)
        {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature (the same id {@link EventHooks} marks). */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(EventHooks.FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Whether the event engine is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
