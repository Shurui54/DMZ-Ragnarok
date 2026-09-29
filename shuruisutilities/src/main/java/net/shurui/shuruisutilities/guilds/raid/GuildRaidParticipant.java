package net.shurui.shuruisutilities.guilds.raid;

import net.minecraft.world.entity.player.Player;

/**
 * A tiny persistent flag marking a player as an active raider, kept in PlayerPersisted NBT so it survives relog
 * (Forge copies the sub-tag onto the respawn/relog clone), exactly like {@link
 * net.shurui.shuruisutilities.world.space.SurfaceTravelData}.
 *
 * <p>Its only job is to let the space-travel module tell a raider apart from an ordinary visitor standing on the same
 * surface dimension: an ordinary visitor who flies up returns to space, whereas a raider who flies up FORFEITS the
 * raid. The space module reads {@link #isRaider(Player)} to route the fly-up correctly. The flag is set when a raider
 * is brought in ({@code GuildRaid#enterActive()}) and cleared when they are restored on resolution.
 *
 * <p>It is a marker only, never the authoritative "who is in this raid" record: that stays in {@code GuildRaid}'s
 * participant set and, for the items and return spot, in {@link GuildRaidVault}. If this flag ever lingered (e.g. an
 * admin teleported the player out mid-raid), the worst case is one extra fly-up forfeit attempt on a raid that has
 * already ended, which the manager treats as a no-op because no live raid involves them.
 */
public final class GuildRaidParticipant
{
    private GuildRaidParticipant()
    {
    }

    private static final String TAG = "su_guild_raider";

    private static net.minecraft.nbt.CompoundTag persisted(Player player)
    {
        net.minecraft.nbt.CompoundTag data = player.getPersistentData();
        net.minecraft.nbt.CompoundTag pt = data.getCompound(Player.PERSISTED_NBT_TAG);
        if (!data.contains(Player.PERSISTED_NBT_TAG))
        {
            data.put(Player.PERSISTED_NBT_TAG, pt);
        }
        return pt;
    }

    public static boolean isRaider(Player player)
    {
        return persisted(player).getBoolean(TAG);
    }

    public static void mark(Player player)
    {
        persisted(player).putBoolean(TAG, true);
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }
}
