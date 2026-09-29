package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// Sparring controls reached from the PvP screen. This is a read-only mirror of the server's spar state (meta[0]);
// every button dispatches an existing /spar subcommand that the server re-validates, so the screen holds no
// authority of its own. meta = [state, opponentOrChallenger, target1, target2, ...] (see HubRowSparring.openSparringPlayer):
//   "disabled" -> sparring is off or the player has no DragonMineZ character.
//   "spar"     -> already sparring meta[1]; a Forfeit button.
//   "invite"   -> meta[1] challenged the viewer; Accept / Decline buttons.
//   "idle"     -> meta[2..] are eligible online targets (may be empty); a picker + Challenge button.
public class SparringScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final String state;
    private final String other;
    private final List<String> targets = new ArrayList<>();
    private DmzDropdown targetDd;

    public SparringScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.spar.screen_title"), UI_W, UI_H, null);
        this.state = meta.isEmpty() ? "disabled" : meta.get(0);
        this.other = meta.size() > 1 ? meta.get(1) : "";
        for (int i = 2; i < meta.size(); i++)
            targets.add(meta.get(i));
    }

    @Override
    protected void init()
    {
        super.init();
        int y = 46;

        switch (state)
        {
            case "spar" ->
            {
                label(tr("gui.dmz_ragnarok.core.spar.in_progress", other), 14, y);
                btn(UI_W / 2 - 45, y + 16, 90, GuiTheme.BUTTON_HEIGHT,
                        Component.translatable("gui.dmz_ragnarok.core.spar.forfeit"), () ->
                        {
                            EditorScreens.runCommand("spar cancel");
                            EditorScreens.reopen("sparring");
                        });
            }
            case "invite" ->
            {
                label(tr("gui.dmz_ragnarok.core.spar.invited_by", other), 14, y);
                btn(UI_W / 2 - 92, y + 16, 88, GuiTheme.BUTTON_HEIGHT,
                        Component.translatable("gui.dmz_ragnarok.core.spar.accept"), () ->
                        {
                            EditorScreens.runCommand("spar accept");
                            EditorScreens.reopen("sparring");
                        });
                btn(UI_W / 2 + 4, y + 16, 88, GuiTheme.BUTTON_HEIGHT,
                        Component.translatable("gui.dmz_ragnarok.core.spar.decline"), () ->
                        {
                            EditorScreens.runCommand("spar deny");
                            EditorScreens.reopen("sparring");
                        });
            }
            case "idle" ->
            {
                if (targets.isEmpty())
                {
                    targetDd = null;
                    labelCentered("§7" + tr("gui.dmz_ragnarok.core.spar.no_targets"), UI_W / 2, y + 6, 0xFFB0B0B0);
                }
                else
                {
                    label(tr("gui.dmz_ragnarok.core.spar.pick_target"), 14, y);
                    targetDd = dropdown(14, y + 12, 130, options(targets.toArray(new String[0])), 0).searchable();
                    btn(150, y + 12, 76, GuiTheme.BUTTON_HEIGHT,
                            Component.translatable("gui.dmz_ragnarok.core.spar.challenge"), this::challenge);
                }
            }
            default -> labelCentered("§7" + tr("gui.dmz_ragnarok.core.spar.disabled_msg"), UI_W / 2, y + 6, 0xFFB0B0B0);
        }

        // Back to the PvP screen (where sparring is reached from) and out to the player hub.
        btn(14, footerY(), 60, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.menu.pvptoggle"), () -> EditorScreens.reopen("pvptoggle"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.menu"), EditorScreens::openPlayerHub);
    }

    private void challenge()
    {
        if (targetDd == null || targets.isEmpty())
            return;
        int idx = targetDd.getIndex();
        if (idx < 0 || idx >= targets.size())
            return;
        EditorScreens.runCommand("spar " + targets.get(idx));
        EditorScreens.reopen("sparring");
    }
}
