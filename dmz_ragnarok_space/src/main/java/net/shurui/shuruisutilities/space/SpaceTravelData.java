package net.shurui.shuruisutilities.space;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

/**
 * Per-player space-travel origin (dimension id + x/y/z + facing) in PlayerPersisted NBT, so the return trip lands
 * exactly where the player left even across a relog (Forge copies the sub-tag onto the respawn clone). The dungeon
 * addon uses the raw persistent tag because its trip is short and the player stays online; space travel must survive a
 * relog, so it uses the PERSISTED sub-tag.
 */
public final class SpaceTravelData
{
    private SpaceTravelData()
    {
    }

    private static final String TAG = "su_space_origin";

    // the PlayerPersisted compound, created + re-attached if absent.
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

    public static boolean hasOrigin(Player player)
    {
        return persisted(player).contains(TAG);
    }

    // does not teleport; the caller does.
    public static void setOrigin(Player player)
    {
        CompoundTag o = new CompoundTag();
        o.putString("dim", player.level().dimension().location().toString());
        o.putDouble("x", player.getX());
        o.putDouble("y", player.getY());
        o.putDouble("z", player.getZ());
        o.putFloat("yaw", player.getYRot());
        o.putFloat("pitch", player.getXRot());
        persisted(player).put(TAG, o);
    }

    public static void clear(Player player)
    {
        persisted(player).remove(TAG);
    }

    /** The whole origin record as stored, for carrying across a cross-shard hop, or null when none is set. */
    public static CompoundTag rawOrigin(Player player)
    {
        return hasOrigin(player) ? persisted(player).getCompound(TAG).copy() : null;
    }

    /** Restore a whole origin record carried across a cross-shard hop (see SpaceHandoff.attachOrigin). */
    public static void setRawOrigin(Player player, CompoundTag origin)
    {
        if (origin != null)
        {
            persisted(player).put(TAG, origin.copy());
        }
    }

    // null if none / invalid / that dimension is gone.
    public static ServerLevel originLevel(Player player, MinecraftServer server)
    {
        if (!hasOrigin(player) || server == null)
        {
            return null;
        }
        ResourceLocation loc = ResourceLocation.tryParse(persisted(player).getCompound(TAG).getString("dim"));
        if (loc == null)
        {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, loc));
    }

    public static double originX(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("x");
    }

    public static double originY(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("y");
    }

    public static double originZ(Player player)
    {
        return persisted(player).getCompound(TAG).getDouble("z");
    }

    public static float originYaw(Player player)
    {
        return persisted(player).getCompound(TAG).getFloat("yaw");
    }

    public static float originPitch(Player player)
    {
        return persisted(player).getCompound(TAG).getFloat("pitch");
    }

    // if the origin somehow points at the space dim (admin edit / corruption), the caller treats it as "no origin"
    // and falls back to overworld spawn: never send a player back into space itself.
    public static boolean originIsSpace(Player player)
    {
        return SpaceDimension.ID.toString().equals(persisted(player).getCompound(TAG).getString("dim"));
    }
}
