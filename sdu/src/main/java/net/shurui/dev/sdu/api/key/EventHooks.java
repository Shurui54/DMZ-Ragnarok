package net.shurui.dev.sdu.api.key;

import java.util.Collections;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE timed-event engine (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core
 * keeps the data model ({@code net.shurui.shuruisutilities.events.EventDef}), the packets (128-130), the
 * {@code event_token} item and the editor screens; this hook is how the key's scheduler, runtime, loot overlays
 * and metric counters run, and only when the key is installed.
 *
 * <p>This half of the surface lives in {@code sdu} so the module trees (Raids, Tournaments, Dungeons) can call it
 * without importing SU (they already import sdu). It carries the seams those modules touch: raid/rift runnability,
 * raid/tournament/dungeon bonus loot, the TP multiplier and the metric sink. The SU-only seams (regions, crates,
 * airdrops, the editor dispatch) live in the sibling {@code net.shurui.shuruisutilities.api.key.EventWorldHooks}.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, every runnable check is
 * false (so an eventOnly def never runs without the key), every bonus list is empty, the TP multiplier is 1.0 and
 * the metric sink is a no-op. The key swaps the whole impl in via {@link #install(Impl)}, which also marks
 * {@link KeyFeatures} so the client login sync reports the feature.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class EventHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "events";

    private EventHooks() {}

    /** The behaviour the key installs. Every method has an inert keyless default. */
    public interface Impl
    {
        /** Whether the event engine is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** The ids of the events active right now. Keyless: none. */
        default List<String> activeIds()
        {
            return Collections.emptyList();
        }

        /** Whether an eventOnly raid def is currently runnable (an active event lists it). Keyless: false. */
        default boolean raidRunnable(String defId)
        {
            return false;
        }

        /** Whether an eventOnly rift def is currently spawnable (an active event lists it). Keyless: false. */
        default boolean riftRunnable(String riftId)
        {
            return false;
        }

        /** Extra reward-grammar tokens an active event adds to a raid victory at the given rank. Keyless: none. */
        default List<String> raidBonusTokens(String defId, String raidType, int rank)
        {
            return Collections.emptyList();
        }

        /** Extra reward-grammar tokens an active event adds to a tournament placement. Keyless: none. */
        default List<String> tournamentBonusTokens(String defId, int placement)
        {
            return Collections.emptyList();
        }

        /** Extra dungeon-crate drops an active event adds for this theme/tier/metal. Keyless: none. */
        default List<ItemStack> dungeonCrateBonus(ServerPlayer player, String theme, String tier, String metal,
                                                  RandomSource random)
        {
            return Collections.emptyList();
        }

        /** Extra drops an active event awards the top-damage player of a dungeon boss floor. Keyless: none. */
        default List<ItemStack> dungeonBossBonus(ServerPlayer player, int floorNo)
        {
            return Collections.emptyList();
        }

        /** The multiplicative TP factor an active event applies for this player. Keyless: 1.0 (no change). */
        default double tpMultiplier(ServerPlayer player)
        {
            return 1.0;
        }

        /** Report progress toward an event counter (kills, crate opens, floor clears, ...). Keyless: no-op. */
        default void onMetric(ServerPlayer player, String metric, String target, int amount)
        {
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
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
