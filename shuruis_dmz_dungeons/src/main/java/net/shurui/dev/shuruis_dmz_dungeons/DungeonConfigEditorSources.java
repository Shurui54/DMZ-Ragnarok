package net.shurui.dev.shuruis_dmz_dungeons;

import net.shurui.dev.sdu.config.editor.ConfigEditorRegistry;
import net.shurui.dev.sdu.config.editor.ConfigTabs;
import net.shurui.dev.sdu.config.editor.EditorLink;
import net.shurui.dev.sdu.config.editor.ForgeSpecSource;

import java.util.ArrayList;
import java.util.List;

/**
 * The Dungeons module's file in the SDU options editor ({@code config/shuruis_dmz_dungeons-common.toml}) and the
 * dungeon editor on its Editors tab. Registered only when this module is installed. Instanced dungeons are a key
 * feature, so their values are key only; the editor gate and the WorldEdit highlight serve the public spawner too.
 */
final class DungeonConfigEditorSources {

    private DungeonConfigEditorSources() {
    }

    static void register() {
        List<String> styles = new ArrayList<>();
        styles.add("VARIED");
        for (net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle s
                : net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle.values()) {
            styles.add(s.name());
        }
        ConfigEditorRegistry.addServer(new ForgeSpecSource("dungeons", "config/shuruis_dmz_dungeons-common.toml",
                () -> Config.SPEC, ConfigTabs.DUNGEONS, Config::bake)
                .choices("dungeonDefaultLayoutStyle", styles)
                .keyOnlyIf(p -> p.startsWith("dungeon")));
        ConfigEditorRegistry.addLink(EditorLink.command("dungeons.edit", "gui.dmz_ragnarok.npc.options.link.dungeons",
                "gui.dmz_ragnarok.npc.options.link.dungeons.tip", "rg dungeon edit"));
    }
}
