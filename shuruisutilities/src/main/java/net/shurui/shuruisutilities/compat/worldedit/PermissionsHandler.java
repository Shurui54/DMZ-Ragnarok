package net.shurui.shuruisutilities.compat.worldedit;

import net.shurui.shuruisutilities.api.APIRegistry;
import com.sk89q.worldedit.forge.ForgePermissionsProvider;

import net.minecraft.server.level.ServerPlayer;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

public class PermissionsHandler implements ForgePermissionsProvider
{

    @Override
    public boolean hasPermission(ServerPlayer player, String permission)
    {
        LoggingHandler.sulog.debug("[WorldEdit] Checking WE permission: {}", permission);
        return APIRegistry.perms.checkPermission(player, permission);
    }

    @Override
    public void registerPermission(String permission)
    {
        LoggingHandler.sulog.debug("[WorldEdit] Registering WE permission: {}", permission);
        APIRegistry.perms.registerPermission(permission, DefaultPermissionLevel.OP, "");
        // boolean allowForAllPlayers = permission.startsWith("worldedit.selection");
        // PermissionManager.registerCommandPermission(permission.split("\\.")[1], permission,
        // allowForAllPlayers ? DefaultPermissionLevel.ALL : DefaultPermissionLevel.OP);
    }

}