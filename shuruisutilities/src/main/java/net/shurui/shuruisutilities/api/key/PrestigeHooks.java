package net.shurui.shuruisutilities.api.key;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.dev.sdu.api.KeyFeatures;
import net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave;
import net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave;

/**
 * Core-side hook for the PRIVATE prestige ACTIONS (owner Q1; logic in the Ragnarok Key, {@code dmz_ragnarok_key}):
 * prestiging itself, the per-level rewards (character slots, the level kits, the citizen group, the pre-1.0 veteran
 * rank), the reward ledger bookkeeping, the server-wide prestige floor ({@code /prestige forceall}), the admin
 * reset, the prestige NPCs and their screens, {@code /prestige}, the admin hub row, the handler bodies of packets
 * 32, 34 and 41, and the {@code prestige:settings} shard state.
 *
 * <p>Everything a player has ALREADY EARNED stays public in core and works keyless: the prestige level itself (per
 * character slot, in player data), the TP-gain bonus it gives, the widened stat cap, the prestige name colour, the
 * race, quest and hard-difficulty gates read from the stored {@code PrestigeSettings}, the client syncs of all of
 * those, and the character slot count the ledger stores. Core keeps {@code prestige.PrestigeManager} as a facade: its
 * read and sync statics stay real, and the action statics route here.
 *
 * <p>The {@link Impl} DEFAULTS are the keyless behaviour: nobody can prestige, and no reward, ledger write, floor or
 * reset ever runs. The three packets are ignored.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class PrestigeHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "prestige";

    private PrestigeHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the prestige actions are live (the key installed them). Keyless: false. */
        default boolean available()
        {
            return false;
        }

        /** Whether this character may prestige now. Keyless: false. */
        default boolean canPrestige(ServerPlayer p)
        {
            return false;
        }

        /** Prestige the active character; null on success, else the error. Keyless: refused. */
        default String prestige(ServerPlayer p)
        {
            return "Prestige is not available on this server.";
        }

        /** The admin reset of a player's prestige (/rgreset). Keyless: nothing. */
        default void resetPrestige(ServerPlayer p)
        {
        }

        /** Raise this player to the server-wide prestige floor; the level they end on. Keyless: 0, nothing. */
        default int applyMinimum(ServerPlayer p)
        {
            return 0;
        }

        /** One-time seed of the reward ledger. Keyless: nothing. */
        default void seedLedgerIfUnset(ServerPlayer p)
        {
        }

        /** Backfill access to the level kits already reached. Keyless: nothing. */
        default void backfillLevelKitUnlocks(ServerPlayer p)
        {
        }

        /** Packet 32, the prestige screen's action. Keyless: ignored. */
        default void onAction(ServerPlayer p, String action)
        {
        }

        /** Packet 34, the admin settings save (and NPC spawn / edit). Keyless: ignored. */
        default void onAdminSave(ServerPlayer p, PacketPrestigeAdminSave packet)
        {
        }

        /** Packet 41, the race prestige gate save. Keyless: ignored. */
        default void onRaceSave(ServerPlayer p, PacketPrestigeRaceSave packet)
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

    /** Whether the prestige actions are live on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
