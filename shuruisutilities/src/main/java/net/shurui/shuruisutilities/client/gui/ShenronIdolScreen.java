package net.shurui.shuruisutilities.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.ritual.DragonBallRecreation;
import net.shurui.shuruisutilities.ritual.PacketIdolConfirm;
import net.shurui.shuruisutilities.ritual.PacketIdolPrompt;

/**
 * The idol's bargain: what you get, what it costs, and one button to accept it.
 *
 * <p>Built on {@link SagaBaseScreen} so it is the same panel, the same buttons and the same scaling rules as every
 * other screen in the suite. A one-off screen with its own look would be the odd one out at exactly the moment a
 * player is being asked to spend five thousand levels, which is the worst possible time for an interface to feel
 * unfamiliar.
 *
 * <p>Everything shown arrived from the server in the prompt packet, so the screen never works out a term of the deal
 * itself. That matters because the cost is irreversible: the numbers read here are the numbers that will be charged.
 *
 * <p>When the ritual cannot be performed the screen still opens and still lists the terms, with the reason where the
 * accept button would be. Being told why beats an item that appears not to work.
 */
public final class ShenronIdolScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    // Room for the terms, plus up to three wrapped lines of refusal above the footer. See the layout note in init().
    private static final int UI_H = GuiTheme.SCREEN_H;

    // Longest a refusal may run before it wraps, measured inside the panel's content padding with a little slack so a
    // line never touches the border. A refusal is a whole sentence and some of them are long in English before any
    // translator gets to them, so this wraps rather than shrinks: shrinking one line to fit would leave it noticeably
    // smaller than everything around it.
    private static final int REFUSAL_SLACK = 10;
    private static final int LINE_H = 10;
    private static final int MAX_REFUSAL_LINES = 3;

    private final PacketIdolPrompt prompt;

    private ShenronIdolScreen(PacketIdolPrompt prompt)
    {
        super(Component.translatable("gui.dmz_ragnarok.idol.title"), UI_W, UI_H, null);
        this.prompt = prompt;
    }

    /** Called from the prompt packet on the client thread. */
    public static void open(PacketIdolPrompt prompt)
    {
        Minecraft.getInstance().setScreen(new ShenronIdolScreen(prompt));
    }

    private boolean allowed()
    {
        return prompt.refusal == DragonBallRecreation.Refusal.NONE.ordinal();
    }

    private DragonBallRecreation.Refusal refusal()
    {
        DragonBallRecreation.Refusal[] all = DragonBallRecreation.Refusal.values();
        return all[Math.max(0, Math.min(all.length - 1, prompt.refusal))];
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.idol.subtitle");

        int x = GuiTheme.CONTENT_PADDING + 6;
        int y = 40;

        // What you get, then what it costs. In that order: the price only means something once you know what it buys,
        // and this is a decision a player has walked a long way to be able to make.
        label(tr("gui.dmz_ragnarok.idol.gain"), x, y, GuiTheme.COLOR_MUTED);
        y += 12;
        label(tr("gui.dmz_ragnarok.idol.gain.balls", prompt.ballCount), x + 8, y, GuiTheme.COLOR_VALUE);
        y += 11;
        label(tr("gui.dmz_ragnarok.idol.gain.form"), x + 8, y, GuiTheme.COLOR_VALUE);
        y += 14;

        label(tr("gui.dmz_ragnarok.idol.cost"), x, y, GuiTheme.COLOR_MUTED);
        y += 12;
        label(tr("gui.dmz_ragnarok.idol.cost.levels", prompt.levelCost), x + 8, y, GuiTheme.COLOR_LABEL);
        y += 11;
        // The level line doubles as the requirement readout, coloured by whether they meet it, so a player who is
        // short can see how short without hunting for it.
        int levelColour = prompt.level >= prompt.requiredLevel ? GuiTheme.COLOR_VALUE : GuiTheme.COLOR_DISABLED;
        label(tr("gui.dmz_ragnarok.idol.cost.level", prompt.level, prompt.requiredLevel), x + 8, y, levelColour);

        // The refusal is anchored UP from the footer rather than down from the terms, so however many lines it wraps
        // to they always sit between the two instead of the last one sliding under the buttons.
        if (!allowed())
        {
            java.util.List<net.minecraft.util.FormattedCharSequence> lines = this.font.split(
                    DragonBallRecreation.refusalMessage(new DragonBallRecreation.Prompt(
                            prompt.set, prompt.level, prompt.requiredLevel, prompt.levelCost, prompt.ballCount,
                            refusal())),
                    uiWidth - GuiTheme.CONTENT_PADDING * 2 - REFUSAL_SLACK);
            int shown = Math.min(MAX_REFUSAL_LINES, lines.size());
            int top = footerY() - 6 - shown * LINE_H;
            for (int i = 0; i < shown; i++)
            {
                labelCentered(lineText(lines.get(i)), uiWidth / 2, top + i * LINE_H, GuiTheme.COLOR_DISABLED);
            }
        }

        // Footer built off the shared geometry so the buttons sit clear of the panel border like every other screen.
        int btnY = footerY();
        int btnH = footerBtnHeight();
        int half = (uiWidth - GuiTheme.CONTENT_PADDING * 2 - GuiTheme.UNIT) / 2;
        if (allowed())
        {
            // commitBtn, not btn: this is the accepting press, and it plays the suite's confirm sound.
            commitBtn(GuiTheme.CONTENT_PADDING, btnY, half, btnH,
                    Component.translatable("gui.dmz_ragnarok.idol.confirm"), () -> {
                        NetworkUtils.sendToServer(new PacketIdolConfirm());
                        onClose();
                    });
            btn(GuiTheme.CONTENT_PADDING + half + GuiTheme.UNIT, btnY, half, btnH,
                    Component.translatable("gui.dmz_ragnarok.idol.cancel"), this::onClose);
        }
        else
        {
            btn(GuiTheme.CONTENT_PADDING, btnY, uiWidth - GuiTheme.CONTENT_PADDING * 2, btnH,
                    Component.translatable("gui.dmz_ragnarok.idol.close"), this::onClose);
        }
    }

    // Font.split hands back FormattedCharSequences, but the label list this screen draws through takes plain strings,
    // so each wrapped line is flattened back to its characters. The refusals carry no styling of their own; the colour
    // comes from the label call.
    private static String lineText(net.minecraft.util.FormattedCharSequence line)
    {
        StringBuilder out = new StringBuilder();
        line.accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        return out.toString();
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
