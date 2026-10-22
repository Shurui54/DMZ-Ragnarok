package net.shurui.shuruisutilities.core.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigSource;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.EditorLink;
import net.shurui.dev.sdu.config.editor.ForgeSpecSource;
import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.events.ConfigReloadEvent;

/**
 * Every SU {@code .toml} (core, Space and the Ragnarok Key all register theirs into {@link ConfigBase}) in the in-game
 * options editor, plus the SU admin editors on its Editors tab and the {@code su.gui.options} permission.
 *
 * <p>The loaders are read from {@link ConfigBase} each time the editor asks, so a key or module that registers a
 * file simply adds it. {@link #RULES} decides the tab of each value and what stays out; a value no rule names goes
 * to its file's default tab, so a value added to a file tomorrow appears without anyone editing this class. Every
 * file owned by the Ragnarok Key is key-only: it only exists when the key is installed anyway, and the flag keeps
 * it out of a keyless viewer's payload whatever happens.
 */
public final class ConfigEditorSources
{
    private ConfigEditorSources()
    {
    }

    /** The node that lets a non-operator use the options editor. */
    public static final String PERM_OPTIONS = "su.gui.options";

    /** Marker tab meaning "not in the editor". */
    private static final String OUT = "-";

    /**
     * One placement rule: {@code file} is the loader name ({@code main}, {@code Auction}, ...), {@code path} a value
     * path or a prefix ending in {@code *}, {@code tab} the tab or {@link #OUT}, {@code flags} R (restart) and K (key
     * only). The first matching rule wins; {@code why} documents exclusions.
     */
    private record Rule(String file, String path, String tab, String flags, String why)
    {
        boolean matches(String f, String p)
        {
            if (!file.equals(f))
                return false;
            return path.endsWith("*") ? p.startsWith(path.substring(0, path.length() - 1)) : p.equals(path);
        }
    }

    private static Rule r(String file, String path, String tab, String flags)
    {
        return new Rule(file, path, tab, flags, "");
    }

    private static Rule out(String file, String path, String why)
    {
        return new Rule(file, path, OUT, "", why);
    }

    /** Client-only values of main.toml: they show on the Client tab (this player's own copy), never server side. */
    static final List<String> MAIN_CLIENT_PATHS = List.of("Core.CustomMainMenu", "Core.CustomStatHud",
            "Core.CustomStatHudScale", "Core.CustomQuestTracker", "Core.QuestTrackerXaeroCoordLines",
            "Core.QuestTrackerFallbackRightMargin", "Core.QuestTrackerFallbackTopY", "Core.QuestTrackerFallbackWidth",
            "Core.AuraTrails", "Core.FlightAura", "Core.SuiteAnnouncements", "Core.BlackHoleLensing",
            "Core.SpacePodTrails", "Misc.MuteCnpcDimensionWarning", "Misc.DragonBallShellAlpha",
            "Misc.DragonBallRenderStyle");

    static final List<Rule> RULES = List.of(
            // ---- main.toml ----
            out("main", "Core.configRevision", "internal migration stamp"),
            out("main", "Core.format_gson_compat", "internal data-format compatibility"),
            out("main", "Core.modlistLocation", "internal file path"),
            out("main", "Core.worldScopedData", "moves where all SU data is stored; unsafe to flip on a live server"),
            out("main", "Core.debug", "developer debug switch"),
            out("main", "Core.safeMode", "developer safe mode, only on request"),
            r("main", "Core.format_*", ConfigTabs.GENERAL, ""),
            r("main", "Core.enableAilases", ConfigTabs.CHAT, "R"),
            r("main", "Core.overwriteConflicts", ConfigTabs.CHAT, "R"),
            r("main", "Core.versionCheck", ConfigTabs.GENERAL, "R"),
            r("main", "Core.hide_worldedit_help", ConfigTabs.CHAT, ""),
            r("main", "Core.logCommands", ConfigTabs.CHAT, ""),
            r("main", "Misc.MajoritySleepThreshold", ConfigTabs.GENERAL, "K"),
            r("main", "Misc.CheckSpacesInNames", ConfigTabs.GENERAL, ""),
            r("main", "Misc.ForceHostileSpawns", ConfigTabs.NPCS, ""),
            r("main", "Misc.StoreUrl", ConfigTabs.ECONOMY, "RK"),
            r("main", "Misc.NpcRegionAutoAggroBpFraction", ConfigTabs.NPCS, "K"),
            out("main", "Misc.Prestige*", "only seeds the prestige settings once; edited in the Prestige editor"),
            r("main", "Misc.WishTracking*", ConfigTabs.DRAGONBALLS, "K"),
            r("main", "Misc.Swap*", ConfigTabs.DRAGONBALLS, "K"),
            r("main", "Misc.SsgWishMinLevel", ConfigTabs.RITUALS, ""),
            // The two ritual values are the DEFAULTS; the world settings (same storage as /rg edit Rituals) are the
            // "su.rituals" rows right below them on the same tab, and say which value is in force.
            r("main", "Misc.SsgRitualRequiredChargers", ConfigTabs.RITUALS, ""),
            r("main", "Misc.DmzGodRitualEnabled", ConfigTabs.RITUALS, ""),
            r("main", "Misc.RuneAwakenZeniCost", ConfigTabs.ECONOMY, "K"),
            r("main", "Misc.HoldOverworld*", ConfigTabs.PROTECTION, ""),
            r("main", "Misc.ProtectedBuildDimensions", ConfigTabs.PROTECTION, ""),
            r("main", "Misc.NamekDragonBallDimension", ConfigTabs.DRAGONBALLS, "R"),
            r("main", "Misc.OverworldDragonBallScatterRange", ConfigTabs.DRAGONBALLS, ""),
            r("main", "Misc.SuperDragonBallMinPlacementDistance", ConfigTabs.DRAGONBALLS, ""),
            r("main", "Misc.RadarApproximateRadius", ConfigTabs.DRAGONBALLS, ""),
            r("main", "Misc.PlanetVegetaGravity", ConfigTabs.SPACE, ""),
            r("main", "Misc.ItemClear*", ConfigTabs.MAINTENANCE, ""),
            out("main", "Patreon.*", "Patreon backend link: holds the server API key and the backend URL"),
            r("main", "Output.*", ConfigTabs.CHAT, ""),
            // ---- core module files ----
            out("Hoverbikes", "Hoverbikes.speed_*", "edited in the Hoverbikes editor"),
            out("Hoverbikes", "Hoverbikes.sprintMultiplier", "edited in the Hoverbikes editor"),
            out("Hoverbikes", "Hoverbikes.soundEnabled", "edited in the Hoverbikes editor"),
            r("Hoverbikes", "*", ConfigTabs.GENERAL, ""),
            r("Senzu", "*", ConfigTabs.GENERAL, ""),
            r("SaibamanPets", "*", ConfigTabs.NPCS, ""),
            r("Swordsman", "*", ConfigTabs.COMBAT, ""),
            // ---- Space ----
            r("SpacePlanets", "SpacePlanets.enabled", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.spawnRange", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.generated*", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.fixed*", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.asteroidSpawnRange", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.starSpawnRange", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.starSectorSize", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.starDensity", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.blackHoleSpawnRange", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.blackHoleSectorSize", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.blackHoleDensity", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.surfaceColumnDepth", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.moonSurfaceSize", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "SpacePlanets.spaceSimulationDistance", ConfigTabs.SPACE, "R"),
            r("SpacePlanets", "*", ConfigTabs.SPACE, ""),
            r("SpaceTravel", "SpaceTravel.enabled", ConfigTabs.SPACE, "R"),
            r("SpaceTravel", "*", ConfigTabs.SPACE, ""),
            out("PlanetBuster", "PlanetBuster.clashDebug", "developer debug logging"),
            r("PlanetBuster", "PlanetBuster.enabled", ConfigTabs.SPACE, "R"),
            r("PlanetBuster", "*", ConfigTabs.SPACE, ""),
            // ---- Ragnarok Key files (all key only) ----
            r("Auction", "Auction.enabled", ConfigTabs.ECONOMY, "R"),
            r("Auction", "*", ConfigTabs.ECONOMY, ""),
            r("Chat", "*", ConfigTabs.CHAT, ""),
            out("Commands", "Commands Config.version", "internal file revision"),
            out("Commands", "Command Rules Settings.filename", "internal file path"),
            r("Commands", "*", ConfigTabs.CHAT, ""),
            r("Jail", "*", ConfigTabs.PROTECTION, ""),
            out("MultiworldV2", "*", "a developer test flag only"),
            r("Ocarina", "*", ConfigTabs.TRAINING, ""),
            r("Perftools", "*", ConfigTabs.MAINTENANCE, ""),
            out("Permissions", "Permissions.persistenceBackend", "storage backend; switching it orphans every saved permission"),
            r("Permissions", "*", ConfigTabs.PROTECTION, "R"),
            r("ScheduledRestart", "*", ConfigTabs.MAINTENANCE, ""),
            r("Shrine", "*", ConfigTabs.TRAINING, ""),
            r("StaffGoal", "*", ConfigTabs.CHAT, ""),
            r("TabList", "*", ConfigTabs.CHAT, ""),
            r("Teleport", "General.portalBlock", ConfigTabs.PROTECTION, "R"),
            r("Teleport", "*", ConfigTabs.GENERAL, ""),
            r("UpdateBlocks", "*", ConfigTabs.MAINTENANCE, ""));

    private static Rule rule(String file, String path)
    {
        if (file.equals("main") && MAIN_CLIENT_PATHS.contains(path))
            return out("main", path, "client-only, on the Client tab");
        for (Rule r : RULES)
            if (r.matches(file, path) || (r.path().equals("*") && r.file().equals(file)))
                return r;
        return null;
    }

    private static final Map<ConfigLoader, ConfigSource> CACHE = new IdentityHashMap<>();

    /** Called once from the SU constructor. Sources are built lazily from the loaders when the editor opens. */
    public static void register()
    {
        ConfigEditorRegistry.addServerProvider(ConfigEditorSources::serverSources);
        ConfigEditorRegistry.addServer(ritualWorldSettings());
        ConfigEditorRegistry.setExtraPermission(ConfigEditorSources::hasOptionsNode);
        // The SU admin editors that own settings this editor deliberately does not repeat. Each row is listed only
        // when the admin hub would offer it to this player (key, registered feature row and node all checked there).
        String[][] links = {
                {"rituals", "rituals"}, {"events", "events"}, {"npcregions", "npcregions"},
                {"prestige", "prestige"}, {"hoverbikes", "hoverbikes"}, {"sparringsettings", "sparring"},
                {"saibamanpets", "saibamanpets"}, {"shrines", "shrines"}, {"economy", "economy"},
                {"guilds", "guilds"}, {"regions", "regions"}, {"permissions", "permissions"}, {"chat", "chat"},
                {"banitem", "banitem"}, {"shadowdragons", "shadowdragons"}, {"tasks", "tasks"},
                {"crates", "crates"}, {"holograms", "holograms"}, {"portals", "portals"},
                {"worldborder", "worldborder"}, {"cosmetics_admin", "cosmetics"}};
        for (String[] l : links)
        {
            String row = l[0];
            ConfigEditorRegistry.addLink(new EditorLink("su." + row, "gui.dmz_ragnarok.npc.options.link." + l[1],
                    "gui.dmz_ragnarok.npc.options.link." + l[1] + ".tip",
                    p -> net.shurui.shuruisutilities.hub.HubServer.offersAdminRow(p, row),
                    p -> net.shurui.shuruisutilities.hub.HubServer.tryOpen(p, row)));
        }
    }

    private static final List<String> GOD_RITUAL_CHOICES = List.of("FOLLOW_CONFIG", "ON", "OFF");

    /**
     * The world's ritual settings: the very values the Rituals editor ({@code /rg edit}, Rituals) stores in
     * {@code RitualSettings}, read and written through the same setters, so the two editors can never disagree. Each
     * row's hover text names the value actually in force, and "follow config" / 0 hand the choice back to main.toml.
     */
    private static ConfigSource ritualWorldSettings()
    {
        return new net.shurui.dev.sdu.config.editor.SimpleSource("su.rituals",
                "world: ritual settings (also /rg edit Rituals)", null)
                .choice("dmzGodRitual", ConfigTabs.RITUALS,
                        "World override for DragonMineZ's own god ritual, the same switch as the Rituals editor. "
                                + "FOLLOW_CONFIG uses DmzGodRitualEnabled from main.toml; ON or OFF override it on this world.",
                        GOD_RITUAL_CHOICES,
                        () -> {
                            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                            int v = server == null ? 0
                                    : net.shurui.shuruisutilities.ritual.RitualSettings.get(server).storedDmzGodRitual();
                            return GOD_RITUAL_CHOICES.get(v == 1 || v == 2 ? v : 0);
                        },
                        v -> {
                            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                            if (server != null)
                                net.shurui.shuruisutilities.ritual.RitualSettings.get(server)
                                        .setDmzGodRitual(GOD_RITUAL_CHOICES.indexOf(v));
                        }, "FOLLOW_CONFIG")
                .label("DMZ god ritual (world override)")
                .note(() -> {
                    var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                    return server == null ? "" : "In force now: "
                            + (net.shurui.shuruisutilities.ritual.RitualSettings.dmzGodRitualEnabled(server) ? "ON" : "OFF");
                })
                .integer("ssgRequiredChargers", ConfigTabs.RITUALS,
                        "World override for how many saiyans must charge around the Super Saiyan God ritual, the same "
                                + "value as the Rituals editor. 0 uses SsgRitualRequiredChargers from main.toml.",
                        () -> {
                            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                            return server == null ? 0 : net.shurui.shuruisutilities.ritual.RitualSettings.get(server)
                                    .storedSsgRequiredChargers();
                        },
                        v -> {
                            var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                            if (server != null)
                                net.shurui.shuruisutilities.ritual.RitualSettings.get(server).setSsgRequiredChargers(v);
                        }, 0, 0, 100)
                .label("SSG ritual chargers (world override, 0 = config)")
                .note(() -> {
                    var server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                    return server == null ? "" : "In force now: "
                            + net.shurui.shuruisutilities.ritual.RitualSettings.ssgRequiredChargers(server);
                });
    }

    private static boolean hasOptionsNode(ServerPlayer p)
    {
        return net.shurui.shuruisutilities.hub.HubServer.has(p, PERM_OPTIONS);
    }

    private static synchronized Collection<ConfigSource> serverSources()
    {
        ConfigBase base = ShuruisUtilities.getConfigManager();
        if (base == null)
            return List.of();
        List<ConfigSource> out = new ArrayList<>();
        List<ConfigLoader> loaders = base.builtLoadersView();
        loaders.sort((a, b) -> order(a).compareTo(order(b)));
        for (ConfigLoader loader : loaders)
        {
            ConfigData data = loader.returnData();
            if (data == null || data.getSpec() == null)
                continue;
            out.add(CACHE.computeIfAbsent(loader, ConfigEditorSources::build));
        }
        return out;
    }

    private static String order(ConfigLoader l)
    {
        String n = l.returnData() == null ? "" : l.returnData().getName();
        return (n.equals("main") ? "0" : "1") + n;
    }

    private static ConfigSource build(ConfigLoader loader)
    {
        String name = loader.returnData().getName();
        boolean keyFile = loader.getClass().getName().startsWith("net.shurui.ragnarokkey.");
        return new ForgeSpecSource("su." + name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_'),
                "ShuruisUtilities/" + name + ".toml", () -> loader.returnData().getSpec(), ConfigTabs.GENERAL,
                () -> rebake(loader))
                .tabs(p -> {
                    Rule r = rule(name, p);
                    if (r == null)
                        return ConfigTabs.GENERAL;
                    return OUT.equals(r.tab()) ? null : r.tab();
                })
                .restartIf(p -> {
                    Rule r = rule(name, p);
                    return r != null && r.flags().contains("R");
                })
                .keyOnlyIf(p -> {
                    Rule r = rule(name, p);
                    return keyFile || (r != null && r.flags().contains("K"));
                })
                .comment("Misc.DmzGodRitualEnabled", "This is the default. The world override row on this tab (the same"
                        + " switch as /rg edit Rituals) wins whenever it is set to ON or OFF.")
                .comment("Misc.SsgRitualRequiredChargers", "This is the default. The world override row on this tab (the"
                        + " same value as /rg edit Rituals) wins whenever it is above 0.");
    }

    /** What /su reload does for one file: bake its values into the code's copies, then tell listeners. */
    static void rebake(ConfigLoader loader)
    {
        loader.bakeConfig(true);
        APIRegistry.getSUEventBus().post(new ConfigReloadEvent());
    }

    /** The main.toml loader, for the Client tab's copy of its client-only values. */
    static ConfigLoader mainLoader()
    {
        ConfigBase base = ShuruisUtilities.getConfigManager();
        if (base == null)
            return null;
        for (ConfigLoader l : base.builtLoadersView())
            if (l.returnData() != null && "main".equals(l.returnData().getName()))
                return l;
        return null;
    }

    /** The Client tab: this player's copy of main.toml's client-only values. Called from client setup. */
    public static void registerClient()
    {
        ConfigEditorRegistry.addClientProvider(() -> {
            ConfigLoader main = mainLoader();
            if (main == null)
                return List.of();
            return List.of(clientMain(main));
        });
    }

    private static ConfigSource clientMainCache;

    private static synchronized ConfigSource clientMain(ConfigLoader main)
    {
        if (clientMainCache == null)
        {
            java.util.function.Supplier<ForgeConfigSpec> spec = () -> main.returnData().getSpec();
            clientMainCache = new ForgeSpecSource("su.main.client", "ShuruisUtilities/main.toml", spec,
                    ConfigTabs.CLIENT, () -> main.bakeConfig(true))
                    .tabs(p -> MAIN_CLIENT_PATHS.contains(p) ? ConfigTabs.CLIENT : null);
        }
        return clientMainCache;
    }
}
