package net.shurui.shuruisutilities.api.key;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.hoverbike.HoverbikeEntity;
import net.shurui.shuruisutilities.racing.block.RaceItemSpawnerBlockEntity;
import net.shurui.shuruisutilities.racing.entity.RaceItemBoxEntity;
import net.shurui.shuruisutilities.racing.entity.RaceKiOrbEntity;
import net.shurui.shuruisutilities.racing.entity.RaceSaibamanEntity;
import net.shurui.shuruisutilities.racing.tuning.RaceTuningDto;

/**
 * Core-side hook for the PRIVATE racing feature (Mario-Kart-style races on hoverbikes; logic in the Ragnarok Key,
 * {@code dmz_ragnarok_key}). Core keeps the registries (the item box, ki orb and Saibaman entities, the boost pad,
 * item spawner and finish line blocks, the track wand), the packets (108-122), the physics math, the renderers,
 * the HUD and the screens; this hook is how the key's track store, race engine, powerups and bots run, and only
 * when the key is installed.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: {@link #available()} is false, and every entity tick
 * DISCARDS its entity, so a keyless {@code /summon} (or a stray entity from a mixed-version shard) never survives.
 * Everything else is inert (a spawner does nothing, a use/drift/editor/lobby/tuning packet is ignored, no bot
 * input is produced, no player is racing). The key swaps the whole impl in via {@link #install(Impl)}, which also
 * marks {@link KeyFeatures} so the client login sync reports the feature.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class RaceHooks
{
    /** The {@link KeyFeatures} id this hook marks on install, and the id the client gates its UI on. */
    public static final String FEATURE_ID = "racing";

    private RaceHooks() {}

    /** A bot bike's input for one tick (mirrors {@link HoverbikeEntity#setInput}). Null means "no bot drives this". */
    public record BotInput(boolean up, boolean down, boolean left, boolean right, boolean sprint, boolean jump) {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the racing feature is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether this player is currently in a race. Keyless: false (guards a racer's personal-bike toggle). */
        default boolean isRacing(ServerPlayer player)
        {
            return false;
        }

        /** Server tick for a live item box. Keyless: discard it, so no box ever survives a tick. */
        default void itemBoxTick(RaceItemBoxEntity e)
        {
            e.discard();
        }

        /** Server tick for a live ki orb (shell / mine projectile). Keyless: discard it. */
        default void orbTick(RaceKiOrbEntity e)
        {
            e.discard();
        }

        /** Server tick for a live race Saibaman. Keyless: discard it. */
        default void saibamanTick(RaceSaibamanEntity e)
        {
            e.discard();
        }

        /** Server tick for a placed item spawner block entity. Keyless: no-op (the block is inert decoration). */
        default void spawnerTick(ServerLevel level, BlockPos pos, RaceItemSpawnerBlockEntity be)
        {
        }

        /**
         * A racer used their held powerup. {@code back} = drop behind (sneak or S + right click), an alias kept for
         * dropping a mine behind; {@code aimX} / {@code aimZ} are the crosshair look direction on the horizontal plane
         * (the mouse aims attacks). The server clamps the aim to a sane unit direction before using it. Keyless: no-op.
         */
        default void useItem(ServerPlayer player, boolean back, double aimX, double aimZ)
        {
        }

        /** A racer's drift input changed. {@code state} = pressed/released, {@code charge} = client tier hint. Keyless: no-op. */
        default void drift(ServerPlayer player, int state, int charge)
        {
        }

        /**
         * A track-editor action from the GUI ({@code TrackEditorScreen}, packet 119) or the in-world wand.
         * {@code arg} is a numeric payload, {@code pos} a position or a packed node id, {@code text} a block id
         * for the block actions (see {@code net.shurui.shuruisutilities.racing.EditorAction}). Keyless: no-op.
         */
        default void editorAction(ServerPlayer player, String trackId, int action, BlockPos pos, int arg, String text)
        {
        }

        /** A lobby button (join / leave / pick bike variant ...). Keyless: no-op. */
        default void lobbyAction(ServerPlayer player, int action, int arg)
        {
        }

        /**
         * A tuning-screen save. An empty {@code trackId} saves the server-wide tuning; a non-empty one writes that
         * track's per-track physics / odds override. Keyless: no-op (a keyless server never persists tuning).
         */
        default void saveTuning(ServerPlayer player, String trackId, RaceTuningDto tuning)
        {
        }

        /** Compute a riderless bot bike's input for this tick, or null when no bot drives it. Keyless: null. */
        default BotInput botInput(HoverbikeEntity bike)
        {
            return null;
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

    /** Whether the racing feature is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
