package net.shurui.dev.sdu.config.editor;

import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.client.ClientConfig;

import java.util.Map;

/**
 * sdu's own files in the options editor: {@code config/sdu-common.toml} (server), {@code grave_totem.json}
 * (server) and {@code sdu-client.toml} (the Client tab), plus the sdu editors on the Editors tab.
 */
public final class SduConfigSources {

    private SduConfigSources() {
    }

    // sdu-common.toml: the tab of each value. Everything is listed, so a value added later lands in General.
    private static final Map<String, String> COMMON_TABS = Map.ofEntries(
            Map.entry("requireOpToEdit", ConfigTabs.GENERAL),
            Map.entry("maxNpcHealth", ConfigTabs.NPCS),
            Map.entry("enableDmzIntegration", ConfigTabs.NPCS),
            Map.entry("enableAuraStacking", ConfigTabs.COMBAT),
            Map.entry("barrierDefaultLevel", ConfigTabs.TRAINING),
            Map.entry("chamberRadius", ConfigTabs.TRAINING),
            Map.entry("chamberMultiplier", ConfigTabs.TRAINING),
            Map.entry("chamberShareFraction", ConfigTabs.TRAINING),
            Map.entry("chamberGravity", ConfigTabs.TRAINING),
            Map.entry("shadowDummyCooldownSeconds", ConfigTabs.TRAINING),
            Map.entry("shadowDummyMaxAlivePerParty", ConfigTabs.TRAINING),
            Map.entry("partyTpFalloffThreshold", ConfigTabs.TRAINING),
            Map.entry("partyTpFalloffStepPercent", ConfigTabs.TRAINING),
            Map.entry("partyTpFalloffFloorPercent", ConfigTabs.TRAINING),
            Map.entry("allowPrivateWorldsWithoutKey", ConfigTabs.SPACE));

    /** Called once from the mod constructor (sources are lazy: nothing is read until the editor opens). */
    public static void register() {
        ConfigEditorRegistry.addServer(new ForgeSpecSource("sdu", "config/sdu-common.toml", Config::spec,
                ConfigTabs.GENERAL, Config::bake)
                .tabs(p -> COMMON_TABS.getOrDefault(p, ConfigTabs.GENERAL)));

        ConfigEditorRegistry.addServer(new SimpleSource("sdu.grave", "config/sdu/grave_totem.json", null)
                .integer("minutes", ConfigTabs.GENERAL,
                        "How many minutes a death totem (the grave a player leaves behind) stays before it expires.",
                        net.shurui.dev.sdu.grave.GraveTotemConfig::minutes,
                        net.shurui.dev.sdu.grave.GraveTotemConfig::set,
                        net.shurui.dev.sdu.grave.GraveTotemConfig.DEFAULT_MINUTES,
                        net.shurui.dev.sdu.grave.GraveTotemConfig.MIN_MINUTES,
                        net.shurui.dev.sdu.grave.GraveTotemConfig.MAX_MINUTES));

        // The sdu content editors, on the Editors tab (op level 2, the same gate their own packets use).
        link("form", "forms");
        link("race", "races");
        link("saga", "sagas");
        link("sidequest", "sidequests");
        link("wish", "wishes");
        link("shrine", "shrine");
    }

    private static void link(String editor, String key) {
        ConfigEditorRegistry.addLink(new EditorLink("sdu." + editor,
                "gui.dmz_ragnarok.npc.options.dir_" + key, "gui.dmz_ragnarok.npc.options.t_dir_" + key,
                net.shurui.dev.sdu.util.SduPerms::canEdit,
                p -> net.shurui.dev.sdu.network.DmzNet.openEditorFor(p, editor)));
    }

    /** Client tab: sdu-client.toml. Called from client setup only. */
    public static void registerClient() {
        ConfigEditorRegistry.addClient(new ForgeSpecSource("sdu.client", "config/sdu-client.toml",
                () -> ClientConfig.SPEC, ConfigTabs.CLIENT, ClientConfig::bake)
                .restartIf(p -> p.equals("reskinContainers") || p.equals("reskinCurios")));
    }
}
