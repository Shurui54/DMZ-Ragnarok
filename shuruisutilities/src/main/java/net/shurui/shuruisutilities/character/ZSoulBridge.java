package net.shurui.shuruisutilities.character;

import java.lang.reflect.Method;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional bridge to Raid Bosses' Z-Soul system so character slots can store each character's banked
 * beyond-cap Z-Soul progress separately (otherwise per-player, shared across slots). Reflection-based, no hard
 * dependency on Raid Bosses; no-ops when it's absent.
 */
public final class ZSoulBridge
{
    private ZSoulBridge() {}

    // clear banked beyond-cap Z-Soul progress (prestige reset); no-op without Raid Bosses
    public static void reset(ServerPlayer player)
    {
        load(player, new CompoundTag());
    }

    private static boolean resolved;
    private static Method exportM; // ZSoulManager.exportBanked(ServerPlayer) -> CompoundTag
    private static Method importM; // ZSoulManager.importBanked(ServerPlayer, CompoundTag)

    // banked Z-Soul progress as NBT, or null if Raid Bosses is absent
    static CompoundTag export(ServerPlayer player)
    {
        resolve();
        if (exportM == null)
            return null;
        try
        {
            Object r = exportM.invoke(null, player);
            return r instanceof CompoundTag t ? t : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    // replace banked Z-Soul progress from NBT (empty/null clears it)
    static void load(ServerPlayer player, CompoundTag tag)
    {
        resolve();
        if (importM == null)
            return;
        try
        {
            importM.invoke(null, player, tag == null ? new CompoundTag() : tag);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName("net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager");
            exportM = c.getMethod("exportBanked", ServerPlayer.class);
            importM = c.getMethod("importBanked", ServerPlayer.class, CompoundTag.class);
        }
        catch (Throwable ignored)
        {
            exportM = null;
            importM = null;
        }
    }
}
