package net.shurui.shuruisutilities.sparring;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * The fighter's PRIOR friendly-fist flag, carried on their own PERSISTED NBT rather than in a per-world record.
 *
 * <h2>Why this exists next to {@link SparData}</h2>
 * A spar forces DragonMineZ friendly-fist ON for both fighters, and that flag is persisted in DMZ's stats, so every
 * exit path has to put the fighter's own value back. {@link SparData} remembers it for a crash, but it is a
 * {@code SavedData} on THIS server's overworld: it does not follow a player to another shard. So a fighter who leaves
 * mid spar in a way this server cannot tear down cleanly (a timeout, a kick, a proxy fallback, or a hop that skipped
 * the pre-hop teardown) arrives somewhere else carrying a forced flag, with the only record of what it used to be
 * sitting in a world they are no longer in. That is "since I changed servers mid spar I cannot damage other players":
 * friendly fist is on for good, because the login restore has nothing to read.
 *
 * <p>This mirror rides {@code Player.PERSISTED_NBT_TAG}, which Forge copies onto the respawn clone and which
 * {@code ShardPayload} carries wholesale in the vault, so the answer travels with the player and the arrival side can
 * correct them whatever the origin managed to do. Same "clear it on BOTH ends" discipline the SDU form and racial
 * stat buckets use.
 *
 * <p>Written and cleared in lockstep with {@link SparData}: whoever puts one puts the other, whoever removes one
 * removes the other. It is a fallback, never the first answer, so a plain relog on the same server still restores
 * from {@link SparData} exactly as it did before.
 */
public final class SparCarry
{
    private SparCarry() {}

    private static final String TAG = "su_spar_prior_ff";

    // the PlayerPersisted compound, created and re-attached if absent (same idiom as the vehicle deploy records).
    private static CompoundTag persisted(Player player)
    {
        CompoundTag data = player.getPersistentData();
        CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
        {
            data.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    /** Record the flag this fighter had before the spar forced it on. */
    public static void mark(Player player, boolean priorFriendlyFist)
    {
        if (player == null)
        {
            return;
        }
        CompoundTag t = new CompoundTag();
        t.putBoolean("ff", priorFriendlyFist);
        persisted(player).put(TAG, t);
    }

    public static boolean marked(Player player)
    {
        return player != null && persisted(player).contains(TAG);
    }

    /** The recorded prior flag, false when there is none (which is also DMZ's own default). */
    public static boolean prior(Player player)
    {
        return player != null && persisted(player).getCompound(TAG).getBoolean("ff");
    }

    public static void clear(Player player)
    {
        if (player != null)
        {
            persisted(player).remove(TAG);
        }
    }
}
