package net.shurui.shuruisutilities.util;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.Registry;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.registries.ForgeRegistries;

public abstract class ServerUtil
{

    public static DedicatedServerSettings getServerPropProvider(DedicatedServer currentServer)
    {
        return ObfuscationReflectionHelper.getPrivateValue(DedicatedServer.class, currentServer, "f_139604_"); // 1.20.1 SRG for DedicatedServer.settings (was 1.16 field_71340_o)
    }

    public static int parseIntDefault(String value, int defaultValue)
    {
        if (value == null)
            return defaultValue;
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    public static long parseLongDefault(String value, long defaultValue)
    {
        if (value == null)
            return defaultValue;
        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    public static double parseDoubleDefault(String value, double defaultValue)
    {
        if (value == null)
            return defaultValue;
        try
        {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e)
        {
            return defaultValue;
        }
    }

    // null on failure
    public static Integer tryParseInt(String value)
    {
        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    // null on failure
    public static Long tryParseLong(String value)
    {
        try
        {
            return Long.parseLong(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    // null on failure
    public static Float tryParseFloat(String value)
    {
        try
        {
            return Float.parseFloat(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    // null on failure
    public static Double tryParseDouble(String value)
    {
        try
        {
            return Double.parseDouble(value);
        }
        catch (NumberFormatException e)
        {
            return null;
        }
    }

    // world save directory. resolves the REAL level directory via the server's storage source
    // (MinecraftServer.getWorldPath(LevelResource.ROOT)), which honours the configured level-name on a
    // dedicated server and the selected save on an integrated client. do NOT reconstruct it from "world" or
    // a name string; a server whose level-name is not "world" would otherwise resolve the wrong folder and
    // break the legacy SUData migration source. falls back to the old behaviour only if the server or its
    // path is somehow unavailable, so callers never get a NoClassDefFound / NPE at boot.
    public static File getWorldPath()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null)
        {
            try
            {
                java.nio.file.Path root = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
                if (root != null)
                    return root.toFile();
            }
            catch (Throwable t)
            {
                // fall through to the legacy reconstruction below
            }
        }

        // defensive fallback (should not normally run): keep the historical resolution so behaviour never
        // regresses to null when the storage source is unavailable.
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient() && server != null)
            return new File(server.getFile("saves"), server.getWorldData().getLevelName());
        if (server != null)
            return new File(server.getServerDirectory(), "world");
        return new File("world");
    }

    // type-safe player list
    public static List<ServerPlayer> getPlayerList()
    {
        MinecraftServer mc = ServerLifecycleHooks.getCurrentServer();
        return mc == null || mc.getPlayerList() == null ? new ArrayList<>() : mc.getPlayerList().getPlayers();
    }

    // per-world tps
    public static double getWorldTPS(ResourceKey<Level> Level)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        long sum = 0L;
        long[] ticks = server.getTickTime(Level);
        for (long tick : ticks) {
            sum += tick;
        }
        double tps = (double) sum / (double) ticks.length * 1.0E-6D;
        if (tps < 50)
            return 20;
        else
            return 1000 / tps;
    }

    public static double getTPS()
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        final double meanTickTime = mean(server.tickTimes) * 1.0E-6D;
        return Math.min(1000 / meanTickTime, 20);// tps > 20 ? 20 : tps;
    }

    private static long mean(final long[] values)
    {
        long sum = 0;
        for (final long v : values)
        {
            sum += v;
        }
        return sum / values.length;
    }

    public static ServerLevel getOverworld()
    {
        return ServerLifecycleHooks.getCurrentServer().getLevel(Level.OVERWORLD);
    }

    public static long getOverworldTime()
    {
        return ServerLifecycleHooks.getCurrentServer().getLevel(Level.OVERWORLD).getDayTime();
    }

    public static boolean isServerRunning()
    {
        return ServerLifecycleHooks.getCurrentServer() != null && ServerLifecycleHooks.getCurrentServer().isRunning();
    }

    public static boolean isOnlineMode()
    {
        return ServerLifecycleHooks.getCurrentServer().usesAuthentication();
    }

    // used to ping a dead legacy status endpoint over HTTP; now just reads online-mode so SU makes no outbound
    // connection. online mode = already using Mojang session services.
    public static boolean getMojangServerStatus()
    {
        return isServerRunning() && isOnlineMode();
    }

    public static void copyNbt(CompoundTag nbt, CompoundTag data)
    {
        // Clear old data
        for (String key : new HashSet<>(nbt.getAllKeys()))
            nbt.remove(key);

        // Write new data
        for (String key : (Set<String>) data.getAllKeys())
            nbt.put(key, data.get(key));
    }

    public static String getItemPermission(Item item)
    {
        ResourceLocation loc = (ResourceLocation) ForgeRegistries.ITEMS.getKey(item);
        return (loc.getNamespace() + '.' + loc.getPath()).replace(' ', '_');
    }

    public static String getBlockName(Block block)
    {
        Object o = ForgeRegistries.BLOCKS.getKey(block).toString();
        if (o instanceof ResourceLocation)
        {
            ResourceLocation rl = (ResourceLocation) o;
            return rl.getPath();
        }
        else
        {
            return (String) o;
        }
    }

    public static String getBlockPermission(Block block)
    {
        ResourceLocation loc = (ResourceLocation) ForgeRegistries.BLOCKS.getKey(block);
        return (loc.getNamespace() + '.' + loc.getPath()).replace(' ', '_');
    }

    public static ServerLevel getWorldFromString(String dim)
    {
        return ServerLifecycleHooks.getCurrentServer().getLevel(getWorldKeyFromString(dim));
    }

    public static ResourceKey<Level> getWorldKeyFromString(String dim)
    {
        return ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, new ResourceLocation(dim));
    }
}
