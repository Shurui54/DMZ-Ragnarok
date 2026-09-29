package net.shurui.shuruisutilities.permissions;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.key.PermissionHooks;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.permissions.core.ZonePersistenceProvider;
import net.shurui.shuruisutilities.permissions.persistence.FlatfileProvider;
import net.shurui.shuruisutilities.permissions.persistence.JsonProvider;
import net.shurui.shuruisutilities.permissions.persistence.SingleFileProvider;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStoppingEvent;
import net.shurui.shuruisutilities.core.backup.BackupMaintenance;
import net.shurui.shuruisutilities.permissions.forge.SuForgePermissionHandler;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * What core keeps of the permissions module now that the module itself (engine included) lives in the Ragnarok Key.
 *
 * <p>{@link #init()} runs in the SU constructor BEFORE the module launcher, so {@code APIRegistry.perms} is set no
 * later than it used to be (the Permissions module's constructor used to set it during the launcher). Keyless it is
 * the {@link KeylessPermissionHelper}; the key replaces it through {@code PermissionHooks.install}.
 *
 * <p>It also keeps the Forge-bus duties core needs: offering {@link SuForgePermissionHandler} to Forge's PermissionAPI
 * (the handler is only USED when forge-server.toml names it, and it answers from Forge's own defaults while the
 * engine is absent), and, keyless only, the startup backup sweep the module used to run (old snapshots and race bundle
 * backups are still trimmed without the key; it never creates, restores or touches live data), reading the module's
 * toml switches, and loading the permission tree for the public player data the keyless helper serves.
 *
 * <p>The persistence providers (the permission file FORMAT) are core for that reason: keyed and keyless read and
 * write the same files the same way.
 */
public final class PermissionsBootstrap
{
    private static boolean initialized;

    private PermissionsBootstrap() {}

    /** The Permissions module's toml (written by the key's module, read here keyless and never written). */
    private static File moduleToml()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "Permissions.toml");
    }

    private static final Map<String, String> moduleSettings = new HashMap<>();

    /**
     * Read the [Permissions] switches from the module's toml by hand (the module that owns the spec is in the key),
     * so keyless keeps honouring the operator's choices. Missing file or key: the module's own defaults.
     */
    static synchronized void readModuleSettings()
    {
        moduleSettings.clear();
        File toml = moduleToml();
        if (toml.isFile())
        {
            try
            {
                String section = "";
                for (String raw : Files.readAllLines(toml.toPath(), StandardCharsets.UTF_8))
                {
                    String line = raw.trim();
                    if (line.startsWith("["))
                        section = line;
                    else if ("[Permissions]".equals(section) && !line.startsWith("#") && line.contains("="))
                    {
                        String key = line.substring(0, line.indexOf('=')).trim();
                        String value = line.substring(line.indexOf('=') + 1).trim();
                        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\""))
                            value = value.substring(1, value.length() - 1);
                        moduleSettings.put(key, value);
                    }
                }
            }
            catch (IOException | RuntimeException e)
            {
                LoggingHandler.sulog.warn("[Permissions] Could not read {}: {}", toml, e.toString());
            }
        }
        PermissionSettings.fakePlayerIsSpecialBunny = !"false".equalsIgnoreCase(moduleSettings.get("fakePlayerIsSpecialBunny"));
        PermissionSettings.fullcommandNode = "true".equalsIgnoreCase(moduleSettings.get("useEntireCommandNode"));
    }

    /**
     * The persistence provider the configured backend names, exactly as the Permissions module picks it
     * ({@code json}, {@code flatfile}, anything else the single file). Keyless reads the toml; the default is the
     * module's default, {@code singlejson}.
     */
    public static synchronized ZonePersistenceProvider configuredProvider()
    {
        String backend = moduleSettings.getOrDefault("persistenceBackend", "singlejson");
        switch (backend.toLowerCase())
        {
        case "json":
            return new JsonProvider();
        case "flatfile":
            return new FlatfileProvider();
        case "singlejson":
        default:
            return new SingleFileProvider();
        }
    }

    /**
     * Keyless only: the two nodes CORE reads that the Permissions module used to register (keyed, the key's module
     * still registers them with these same levels). Unregistered they would read as allowed, letting every player
     * past the player limit and onto the map teleport. Both stay OP, which keyless means vanilla op level 4.
     */
    private static void registerCoreNodes()
    {
        APIRegistry.perms.registerPermission(PermissionSettings.PERM_PLAYERLIMIT_BYPASS,
                net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel.OP,
                "Join even when the server is at its player limit.");
        APIRegistry.perms.registerPermission(PermissionSettings.PERM_MAP_TELEPORT,
                net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel.OP,
                "Use teleport commands issued by the world map (tp/teleport/execute).");
    }

    public static synchronized void init()
    {
        if (initialized)
            return;
        initialized = true;
        KeylessPermissionHelper.installDefault();
        MinecraftForge.EVENT_BUS.register(new ForgeHandlers());
    }

    /** Forge-bus handlers, registered by instance (no subscriber annotation). */
    public static final class ForgeHandlers
    {
        /**
         * Offers the SU handler to Forge's PermissionAPI. Registering only makes it AVAILABLE; it is used at runtime
         * only when config/forge-server.toml [server] permissionHandler names it.
         */
        @SubscribeEvent
        public void gatherForgePermissionHandler(PermissionGatherEvent.Handler event)
        {
            event.addPermissionHandler(SuForgePermissionHandler.IDENTIFIER, SuForgePermissionHandler::new);
            LoggingHandler.sulog.info(
                    "[Permissions] Registered SU Forge permission handler '{}'. Set config/forge-server.toml [server] permissionHandler to this id to route Forge PermissionAPI checks (e.g. CustomNPCs) through SU.",
                    SuForgePermissionHandler.IDENTIFIER);
        }

        /**
         * Keyless only: trim the timestamped backup rings to their retention, as the Permissions module's start did
         * before it moved to the key (keyed, the module still runs it itself, after its own snapshots); read the
         * module's toml switches; and load the permission tree for the public player data (read only; it is written
         * only after a public data write).
         */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void serverStarting(SUModuleServerStartingEvent event)
        {
            if (PermissionHooks.available() || !(APIRegistry.perms instanceof KeylessPermissionHelper keyless))
                return;
            BackupMaintenance.Result swept = BackupMaintenance.sweep(BackupMaintenance.KEEP);
            if (swept.total() > 0)
                LoggingHandler.sulog.info("[SUData] Cleaned up {} old backup folder(s), freeing {}. Retention is {} snapshots of each kind.",
                        swept.total(), BackupMaintenance.humanBytes(swept.bytesFreed()), BackupMaintenance.KEEP);
            readModuleSettings();
            registerCoreNodes();
            keyless.loadPublicData();
        }

        @SubscribeEvent
        public void serverStopping(SUModuleServerStoppingEvent event)
        {
            if (APIRegistry.perms instanceof KeylessPermissionHelper keyless)
                keyless.unloadPublicData();
        }
    }
}
