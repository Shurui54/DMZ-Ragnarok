package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// consensual-PvP toggle. meta = [pvpEnabled, bountyForced]. bountyForced (a >=10k Zeni bounty on the viewer)
// renders the toggle LOCKED ON: can't opt out while the bounty stands. no authority here: the server re-derives
// effective PvP from PlayerInfo + BountyManager; toggling only stores the preference, re-applied once it clears.
public class PvpToggleScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private boolean enabled;
    private final boolean bountyForced;

    public PvpToggleScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.pvptoggle.title"), UI_W, UI_H, null);
        this.enabled = meta.size() > 0 && Boolean.parseBoolean(meta.get(0));
        this.bountyForced = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        headerSubtitle = bountyForced ? tr("gui.dmz_ragnarok.core.pvptoggle.locked")
                : (enabled ? tr("gui.dmz_ragnarok.core.pvptoggle.enabled") : tr("gui.dmz_ragnarok.core.pvptoggle.disabled"));
        rowY = 40;

        if (bountyForced)
        {
            // A >=10k bounty forces effective PvP on: show a locked, inactive toggle so it can't be turned off.
            label(Component.translatable("gui.dmz_ragnarok.core.pvptoggle.label").getString(), 14, rowY + 2);
            DmzTextureButton locked = btn(120, rowY, 106, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.pvptoggle.forced"), () -> {});
            locked.active = false;
            rowY += ROW_H;
            labelCentered("§7" + tr("gui.dmz_ragnarok.core.pvptoggle.bounty_note"), UI_W / 2, rowY + 6, 0xFFB0B0B0);
        }
        else
        {
            // Toggling immediately persists server-side (set action), which re-sends fresh meta.
            bf(Component.translatable("gui.dmz_ragnarok.core.pvptoggle.label").getString(), enabled, () ->
            {
                enabled = !enabled;
                EditorScreens.act("pvptoggle", "set", Boolean.toString(enabled));
            });
            tip(tr("gui.dmz_ragnarok.core.pvptoggle.tip"));
        }

        // Sparring lives here in the PvP screen: a spar is consensual PvP, so its start/manage controls sit next to
        // the PvP toggle. The button opens the server-driven sparring screen (challenge / accept / forfeit).
        btn(14, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.pvptoggle.spar"),
                () -> EditorScreens.reopen("sparring"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }
}
