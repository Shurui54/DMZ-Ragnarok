package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// ritual settings editor: how many players must charge their ki around a Super Saiyan God ritual, and whether
// DragonMineZ's own god ritual may start. meta = [stored chargers (0 = the config value), the config value, the value
// in effect, stored god ritual choice (0 config, 1 on, 2 off), its config value, whether it is on now]. Save sends the field back; blank or 0 goes back to
// the config value. Server side: EditorServer.handleRituals, stored in RitualSettings and synced to every shard.
public class RitualSettingsScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private String chargers;
    private final String configDefault;
    private final String inEffect;
    // DragonMineZ's own god ritual: the choice saved with Save (1 on, 2 off), starting from what is in effect now
    private boolean dmzGodRitual;
    private final boolean dmzGodRitualConfig;
    private boolean dmzGodRitualTouched;

    public RitualSettingsScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.rituals"), UI_W, UI_H, null);
        String stored = meta.size() > 0 ? meta.get(0) : "0";
        this.chargers = "0".equals(stored) ? "" : stored;
        this.configDefault = meta.size() > 1 ? meta.get(1) : "5";
        this.inEffect = meta.size() > 2 ? meta.get(2) : configDefault;
        this.dmzGodRitualConfig = meta.size() <= 4 || Boolean.parseBoolean(meta.get(4));
        this.dmzGodRitual = meta.size() > 5 ? Boolean.parseBoolean(meta.get(5)) : dmzGodRitualConfig;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = 34;
        tf(tr("gui.dmz_ragnarok.core.ritual.ssg_chargers", inEffect), chargers, v -> chargers = v);
        tip(tr("gui.dmz_ragnarok.core.ritual.ssg_chargers_tip", configDefault));
        bf(tr("gui.dmz_ragnarok.core.ritual.dmz_god_ritual"), dmzGodRitual, () -> {
            dmzGodRitual = !dmzGodRitual;
            dmzGodRitualTouched = true;
        });
        tip(tr("gui.dmz_ragnarok.core.ritual.dmz_god_ritual_tip", tr(dmzGodRitualConfig
                ? "gui.dmz_ragnarok.core.common.on" : "gui.dmz_ragnarok.core.common.off")));

        btn(14, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        // the god ritual choice is sent only once it was toggled here, so a plain chargers save keeps the config value
        EditorScreens.act("rituals", "save", chargers == null ? "" : chargers.trim(),
                dmzGodRitualTouched ? (dmzGodRitual ? "1" : "2") : "");
    }
}
