package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.worldborder.WorldBorder;

/**
 * Core-side hook for the PRIVATE world border enforcement (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core
 * keeps the persisted border: {@code worldborder.WorldBorder} (the DataManager folder {@code WorldBorder}), the
 * effect records and their {@code type}-tagged adapter (the effect classes are named in the saved JSON, so they stay
 * where they are), the default namekow / kaiow border seeding ({@code DimensionBorderSeeder}) and the dragon ball
 * scatter clamp ({@code BorderClamp}, used by the public ball scatter mixin). The {@code WorldBorder} module (the
 * per-move enforcement, the effects' activation, {@code /wb} and the border hub row) lives in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour:
 * <ul>
 *   <li>{@link Impl#border} reads the SAVED border record for the level (read only; null when there is none). Before
 *       S11 the module was built keyless and read every startup level's record before the teardown, and the scatter
 *       clamp went on reading them; this keeps dragon balls inside an enabled border on a keyless server exactly as
 *       they were.</li>
 *   <li>{@link Impl#enforceInsideNow} does nothing (nothing enforces a border keyless, as before).</li>
 * </ul>
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class WorldBorderHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "worldborder";

    private WorldBorderHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the WorldBorder module is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /**
         * The suite border of this level, or null. Keyless: the saved record for the level's dimension, read from
         * disk and never written (null when absent or unreadable).
         */
        default WorldBorder border(Level level)
        {
            if (level == null)
                return null;
            try
            {
                return WorldBorder.load(level);
            }
            catch (Throwable t)
            {
                return null;
            }
        }

        /** Pull this player back inside their dimension's border now. Keyless: nothing. */
        default void enforceInsideNow(ServerPlayer player)
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

    /** Whether the WorldBorder module is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
