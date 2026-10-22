package net.shurui.dev.shuruis_raid_bosses;

import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.EditorLink;
import net.shurui.dev.sdu.config.editor.ForgeSpecSource;
import net.shurui.dev.sdu.config.editor.SimpleSource;

/**
 * The Raids module's file in the SDU options editor ({@code config/shuruis_raid_bosses-common.toml}), the raid editor
 * on its Editors tab, and the boss music switch on the Client tab. The values are read live through the spec, so a
 * save applies at once. Z-Soul is a key feature, so its values are key only.
 */
public final class RaidConfigEditorSources {

    private RaidConfigEditorSources() {
    }

    static void register() {
        ConfigEditorRegistry.addServer(new ForgeSpecSource("raids", "config/shuruis_raid_bosses-common.toml",
                () -> Config.SPEC, ConfigTabs.RAIDS, null)
                .tabs(p -> p.startsWith("zsoul.") ? ConfigTabs.TRAINING : ConfigTabs.RAIDS)
                .keyOnlyIf(p -> p.startsWith("zsoul.")));
        ConfigEditorRegistry.addLink(EditorLink.command("raids.edit", "gui.dmz_ragnarok.npc.options.link.raids",
                "gui.dmz_ragnarok.npc.options.link.raids.tip", "rg raid edit"));
    }

    /** Client tab: the boss music switch (its own properties file). Called from client setup. */
    public static void registerClient() {
        ConfigEditorRegistry.addClient(new SimpleSource("raids.client", "config/dmz_ragnarok-bossmusic.properties", null)
                .bool("enabled", ConfigTabs.CLIENT, "Play the raid boss music during a boss fight.",
                        net.shurui.dev.shuruis_raid_bosses.client.BossMusicOptions::enabled,
                        net.shurui.dev.shuruis_raid_bosses.client.BossMusicOptions::setEnabled, true));
    }
}
