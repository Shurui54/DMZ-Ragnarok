package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons;
import net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds;

/**
 * Core-side hook for the PRIVATE shadow dragon editor (S20; logic in the Ragnarok Key, {@code dmz_ragnarok_key}): the
 * "shadowdragons" admin hub row, and the handler bodies of packets 45 ({@code PacketSaveShadowDragons}, the seven slot
 * definitions) and 46 ({@code PacketSetShadowDragonBounds}, an arena from a WorldEdit selection).
 *
 * <p>What stays PUBLIC in core, running keyless as before: the corrupted dragon ball cycle that is the shadow dragon
 * race's unlock path (wish tracking, the corrupted ball scatter, the seven-ball summon, the cinematic, the shadow
 * dragon encounter, the kill tallies and race / sub-race unlocks, the defiled radar sync), and the stored slot
 * definitions ({@code ShadowDragonStorage}) that encounter reads. Only editing those definitions in game is private.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: both packets are ignored, and there is no editor row.
 */
public final class CorruptedHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "shadowdragoneditor";

    private CorruptedHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /**
         * Whether the shadow dragon editor is installed (the key installed it). Keyless: false, which is also what
         * turns on the keyless boss defaults (S20b, {@code corrupted.ShadowDragonKeylessDefaults}: Majin Buu stats
         * and random spawn spots where a slot is still unedited).
         */
        default boolean available()
        {
            return false;
        }

        /** Packet 45, the editor's save of the slot definitions. Keyless: ignored. */
        default void onSaveDefs(ServerPlayer p, PacketSaveShadowDragons packet)
        {
        }

        /** Packet 46, a slot's arena from the sender's WorldEdit selection. Keyless: ignored. */
        default void onSetBounds(ServerPlayer p, PacketSetShadowDragonBounds packet)
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

    /** Whether the shadow dragon editor is installed on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
