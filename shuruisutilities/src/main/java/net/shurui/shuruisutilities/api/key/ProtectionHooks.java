package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE permission-zone protection (logic in the Ragnarok Key, {@code dmz_ragnarok_key}):
 * the {@code Protection} module, its enforcement handler ({@code ProtectionEnforcement}: the {@code su.protection.*}
 * nodes for blocks, items, mobs, damage, zones and the PvP toggle), {@code /itemperm}, {@code /protectdebug} and the
 * protection and PvP toggle hub rows. Core keeps the node names ({@code protection.ProtectionPerms}), the shared PvP
 * answers ({@code protection.ProtectionEventHandler}: {@code pvpAllowedBetween}, {@code grantPvpEnableDeathGrace},
 * {@code resolvePvpAttacker}), the direct block-destroy guard used by the mixins ({@code BlockBreakGuard}) and the
 * hand-built dimension guard ({@code BuildDimensionProtection}), which core registers on every server.
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: {@link Impl#available()} is false. No mixin reads this hook:
 * the mixins ask {@code BlockBreakGuard} and the permission helper, whose keyless answers are unchanged by the move.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class ProtectionHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "protection";

    private ProtectionHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the module is live (the key installed it). Keyless: false. */
        default boolean available()
        {
            return false;
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

    /** Whether the module is live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
