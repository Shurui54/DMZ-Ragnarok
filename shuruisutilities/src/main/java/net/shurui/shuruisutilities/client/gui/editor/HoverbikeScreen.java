package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// hoverbike tuning editor: per-bike speed (Mk1..Mk4), shared sprint mult, engine-sound toggle, backed by
// Hoverbikes.toml. Save clamps to the config ranges server-side, persists the toml, re-bakes so ridden bikes
// pick up new speeds live. meta = [speed1..4, sprintMultiplier, soundEnabled].
public class HoverbikeScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private String speed1, speed2, speed3, speed4;
    private String sprint;
    private boolean sound;

    public HoverbikeScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.hoverbikes"), UI_W, UI_H, null);
        this.speed1 = meta.size() > 0 ? meta.get(0) : "0.35";
        this.speed2 = meta.size() > 1 ? meta.get(1) : "0.40";
        this.speed3 = meta.size() > 2 ? meta.get(2) : "0.45";
        this.speed4 = meta.size() > 3 ? meta.get(3) : "0.50";
        this.sprint = meta.size() > 4 ? meta.get(4) : "1.6";
        this.sound = meta.size() > 5 && Boolean.parseBoolean(meta.get(5));
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = 34;
        tf(tr("gui.dmz_ragnarok.core.hoverbike.speed", "1"), speed1, v -> speed1 = v);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.speed_tip", "1"));
        tf(tr("gui.dmz_ragnarok.core.hoverbike.speed", "2"), speed2, v -> speed2 = v);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.speed_tip", "2"));
        tf(tr("gui.dmz_ragnarok.core.hoverbike.speed", "3"), speed3, v -> speed3 = v);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.speed_tip", "3"));
        tf(tr("gui.dmz_ragnarok.core.hoverbike.speed", "4"), speed4, v -> speed4 = v);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.speed_tip", "4"));
        tf(tr("gui.dmz_ragnarok.core.hoverbike.sprint"), sprint, v -> sprint = v);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.sprint_tip"));
        bf(tr("gui.dmz_ragnarok.core.hoverbike.sound"), sound, () -> sound = !sound);
        tip(tr("gui.dmz_ragnarok.core.hoverbike.sound_tip"));

        btn(14, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("hoverbikes", "save", speed1.trim(), speed2.trim(), speed3.trim(), speed4.trim(),
                sprint.trim(), Boolean.toString(sound));
    }
}
