package net.shurui.shuruisutilities.jail;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player jail state in PlayerPersisted NBT so it survives relog + death (Forge copies the sub-tag onto the
 * respawn clone). Jail name, release time (0 = until manually released), and where the player was when jailed
 * so they're sent back on release.
 */
public final class JailData
{
    private JailData() {}

    private static final String TAG = "su_jail";

    // the PlayerPersisted compound, created + re-attached if absent
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

    public static boolean isJailed(Player player)
    {
        return persisted(player).contains(TAG);
    }

    public static String jailName(Player player)
    {
        return persisted(player).getCompound(TAG).getString("jail");
    }

    // epoch-millis release time, 0 = indefinite
    public static long releaseTime(Player player)
    {
        return persisted(player).getCompound(TAG).getLong("until");
    }

    public static String reason(Player player)
    {
        return persisted(player).getCompound(TAG).getString("reason");
    }

    // where the player stood when jailed (where /unjail returns them), or null
    public static WarpPoint returnPoint(Player player)
    {
        CompoundTag j = persisted(player).getCompound(TAG);
        if (!j.contains("rdim"))
        {
            return null;
        }
        return new WarpPoint(j.getString("rdim"), j.getDouble("rx"), j.getDouble("ry"), j.getDouble("rz"),
                j.getFloat("rpitch"), j.getFloat("ryaw"));
    }

    // record the jail state (doesn't teleport; the caller does)
    public static void set(ServerPlayer player, String jail, long releaseTimeMs, String reason)
    {
        CompoundTag j = new CompoundTag();
        j.putString("jail", jail);
        j.putLong("until", releaseTimeMs);
        j.putString("reason", reason == null ? "" : reason);
        j.putString("rdim", player.level().dimension().location().toString());
        j.putDouble("rx", player.getX());
        j.putDouble("ry", player.getY());
        j.putDouble("rz", player.getZ());
        j.putFloat("rpitch", player.getXRot());
        j.putFloat("ryaw", player.getYRot());
        persisted(player).put(TAG, j);
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }
}
