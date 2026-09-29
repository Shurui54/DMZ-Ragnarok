package net.shurui.shuruisutilities.api.key;

import java.util.Map;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.teleport.portal.Portal;

/**
 * Core-side hook for the PRIVATE teleports (logic in the Ragnarok Key, {@code dmz_ragnarok_key}). Core keeps the
 * persisted records ({@code teleport.Warp}, {@code teleport.PersonalWarp}, {@code teleport.portal.Portal}: DataManager
 * folders named by their simple class names), the portal target SPI ({@code PortalTargetResolver} plus the
 * {@code PortalTargets} registration the dungeons module fills at mod construction), the spawn answers
 * ({@code teleport.SpawnPoints}, which public placement code uses), the smp nether-portal teleporter and both mixins
 * ({@code MixinTeleportCommand}, {@code MixinEntityNetherPortal}). The {@code Teleport} module, every teleport command
 * (warp, pwarp, home, spawn, setspawn, tp, tppos, tpa, back, bed, top, jump, rtp, portal), the portal manager and the
 * teleport hub rows live in the key.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour, chosen so the keyless surface is what it was before the move:
 * <ul>
 *   <li>{@link Impl#safeLandVanillaTp()} is TRUE: the vanilla {@code /tp} safe-landing pass (the Xaero "buried in the
 *       ground" fix) stays on keyless, as its config default always had it.</li>
 *   <li>{@link Impl#bypassesVanillaTpSafeLanding} is FALSE: the bypass node is registered with level NONE, which a
 *       keyless server (node defaults only, no user overrides) always answers "denied".</li>
 *   <li>No warps are listed and no portal is found at any point, so the dungeons module's portal gates read "no SU
 *       portal here" and stay open, exactly as on a server with no portals.</li>
 * </ul>
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class TeleportHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "teleport";

    /**
     * Gate for {@code /tp <name>} to a player on another shard ({@code ShardTp}). Kept here so the core shard code can
     * name it without reaching into the key; the key's Teleport module registers it (default OP).
     */
    public static final String PERM_TP_CROSSSERVER = "su.teleport.tp.crossserver";

    private TeleportHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the teleport module is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether the vanilla {@code /tp} gets the safe-landing pass (the module's config switch). Keyless: true. */
        default boolean safeLandVanillaTp()
        {
            return true;
        }

        /** Whether this {@code /tp} sender skips the safe-landing pass (the bypass permission). Keyless: false. */
        default boolean bypassesVanillaTpSafeLanding(ServerPlayer sender)
        {
            return false;
        }

        /** Every global warp by name (the shard-wide set on a network). Keyless: empty. */
        default Map<String, WarpPoint> warps()
        {
            return Map.of();
        }

        /** The SU portal whose area contains this point, or null. Keyless: null (no portals are loaded). */
        default Portal portalAt(WorldPoint point)
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

    /** Whether teleports are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
