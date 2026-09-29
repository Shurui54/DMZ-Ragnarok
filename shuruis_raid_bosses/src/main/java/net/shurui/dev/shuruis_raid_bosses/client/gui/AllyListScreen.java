package net.shurui.dev.shuruis_raid_bosses.client.gui;

import java.util.List;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_raid_bosses.raid.AllySpawn;

/**
 * Lists the NPCs that fight ON THE PLAYERS' SIDE, with Edit / Delete / Add.
 *
 * <p>Allies were built into the fight itself some time ago (they spawn, they are steered, they are kept out of the
 * victory count and they can change sides mid-stage), but nothing ever exposed them, so the only way to give a raid
 * one was to write the token by hand. This is that missing half.
 *
 * <p>Operates on the definition's token list directly, exactly as the enemy-wave list does, so changes persist when
 * the parent editor is saved. The same screen serves a raid's starting allies and a stage's reinforcements, since
 * both are the same list of tokens; only the title differs.
 */
public class AllyListScreen extends BaseEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> allies;
    private final boolean nested;
    private int scroll = 0;

    /**
     * @param nested true when this list belongs to a STAGE. A stage's allies are stored inside the stage's own
     *               token, so they use the nested separators rather than the top-level ones; getting that wrong
     *               writes a token the stage cannot read back.
     */
    public AllyListScreen(Screen parent, List<String> allies, boolean nested) {
        super(Component.translatable("gui.dmz_ragnarok.raid.ally_list.title"), UI_W, UI_H, parent);
        this.allies = allies;
        this.nested = nested;
    }

    @Override
    protected void init() {
        super.init();
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = allies.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final int idx = i;
            AllySpawn a = read(allies.get(idx));
            label(a.summary(), 12, y + 5);
            btn(186, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.edit"),
                    () -> this.minecraft.setScreen(new AllyEditScreen(this, allies, idx, nested)));
            btn(234, y, 44, 14, Component.translatable("gui.dmz_ragnarok.raid.common.delete_plain"), () -> {
                allies.remove(idx);
                if (scroll > 0 && scroll >= allies.size()) {
                    scroll--;
                }
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });

        btn(6, footerY(), 120, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.ally_list.add"),
                () -> {
                    allies.add(write(new AllySpawn()));
                    this.minecraft.setScreen(new AllyEditScreen(this, allies, allies.size() - 1, nested));
                });
        btn(UI_W - 106, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    private AllySpawn read(String token) {
        return nested ? AllySpawn.fromNestedToken(token) : AllySpawn.fromToken(token);
    }

    private String write(AllySpawn ally) {
        return nested ? ally.toNestedToken() : ally.toToken();
    }
}
