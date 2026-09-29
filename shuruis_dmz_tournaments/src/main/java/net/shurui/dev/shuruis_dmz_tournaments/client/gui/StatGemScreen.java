package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;
import net.shurui.dev.shuruis_dmz_tournaments.network.StatGemChoicePacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;

/**
 * The Stat Gem picker. Right-clicking a gem opens this; picking one of the six DMZ core stats sends a
 * {@link StatGemChoicePacket} with the held hand and stat key, then closes. The server re-validates the
 * key, character, still-held gem and stat before applying the amount to that one stat and consuming it.
 */
public class StatGemScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // The six DMZ core stats in display order, paired with their picker-button label keys.
    private static final String[] STAT_KEYS = DmzHooks.CORE_STAT_KEYS;
    private static final String[] LABEL_KEYS = {
            "gui.dmz_ragnarok.stat_gem.stat.str",
            "gui.dmz_ragnarok.stat_gem.stat.skp",
            "gui.dmz_ragnarok.stat_gem.stat.res",
            "gui.dmz_ragnarok.stat_gem.stat.vit",
            "gui.dmz_ragnarok.stat_gem.stat.pwr",
            "gui.dmz_ragnarok.stat_gem.stat.ene",
    };

    private final InteractionHand hand;

    public StatGemScreen(InteractionHand hand) {
        super(Component.translatable("gui.dmz_ragnarok.stat_gem.title"), UI_W, UI_H, null);
        this.hand = hand;
    }

    /** Open the picker on the client for the gem held in {@code hand}. */
    public static void open(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new StatGemScreen(hand));
    }

    @Override
    protected void init() {
        super.init();
        // Two columns of three, one button per DMZ core stat.
        int colW = 100;
        int gap = 8;
        int leftX = (UI_W - (colW * 2 + gap)) / 2;
        int rightX = leftX + colW + gap;
        int rowH = 26;
        int y = contentTop(44);
        for (int i = 0; i < STAT_KEYS.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int x = col == 0 ? leftX : rightX;
            int by = y + row * rowH;
            final String statKey = STAT_KEYS[i];
            commitBtn(x, by, colW, 20, Component.translatable(LABEL_KEYS[i]),
                    () -> choose(statKey));
        }
        btn(UI_W / 2 - 45, GuiTheme.footerY(UI_H), 90, 14,
                Component.translatable("gui.dmz_ragnarok.tournaments.common.close"), this::onClose);
    }

    private void choose(String statKey) {
        TournamentNet.sendToServer(new StatGemChoicePacket(hand, statKey));
        onClose();
    }
}
