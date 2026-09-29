package net.shurui.shuruisutilities.util;

import java.util.Optional;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftSessionService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

public class UserIdentUtils
{

    public static String reformatUUID(String s)
    {
        if (s.length() != 32)
            throw new IllegalArgumentException();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16) + "-" + s.substring(16, 20)
                + "-" + s.substring(20, 32);
    }

    public static UUID stringToUUID(String s)
    {
        if (s.length() == 32)
            s = reformatUUID(s);
        return UUID.fromString(s);

    }

    // name -> UUID via the server GameProfileCache (memory hit, else vanilla's GameProfileRepository). all the
    // network work lives outside SU, so we never open a connection from SU bytecode. null off-server / no match.
    public static UUID resolveMissingUUID(String name)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || name == null)
            return null;
        try
        {
            LoggingHandler.sulog.debug("Resolving UUID for " + name + " via server profile cache");
            Optional<GameProfile> profile = server.getProfileCache().get(name);
            if (profile.isPresent() && profile.get().getId() != null)
                return profile.get().getId();
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.debug("Failed to resolve UUID for " + name + ": " + e);
        }
        return null;
    }

    // UUID -> name via GameProfileCache (memory only for UUID lookups), then vanilla
    // fillProfileProperties through the game's session service. all built-in, so SU opens no HTTP. null
    // off-server / no match.
    public static String resolveMissingUsername(UUID id)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || id == null)
            return null;
        try
        {
            LoggingHandler.sulog.debug("Resolving name for " + id + " via server profile machinery");
            GameProfileCache cache = server.getProfileCache();
            Optional<GameProfile> cached = cache.get(id);
            if (cached.isPresent() && cached.get().getName() != null && !cached.get().getName().isEmpty())
                return cached.get().getName();

            // Cache miss: let the session service fill in the name (vanilla does the sessionserver call itself).
            MinecraftSessionService session = server.getSessionService();
            if (session != null)
            {
                GameProfile filled = session.fillProfileProperties(new GameProfile(id, null), false);
                if (filled != null && filled.getName() != null && !filled.getName().isEmpty())
                {
                    cache.add(filled);
                    return filled.getName();
                }
            }
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.debug("Failed to resolve name for " + id + ": " + e);
        }
        return null;
    }

}
