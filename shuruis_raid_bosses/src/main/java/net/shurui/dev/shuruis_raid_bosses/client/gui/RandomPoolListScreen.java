package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;

import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Chooses which raids a selector raid may roll.
 *
 * <p>Presented as one list of every known raid with an in/out toggle rather than an add-then-delete pair, because
 * the question being answered is "which of these can come up", not "build me a list". A raid cannot include itself
 * (that would roll a selector that rolls a selector), and any raid type is offerable: the drawn raid runs as itself,
 * so a single random arena can produce a standard boss, a parallel quest or a boss rush.
 */
public class RandomPoolListScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> pool;
    private final List<RaidBossDef> candidates;
    private final String selfId;

    private int scroll = 0;

    public RandomPoolListScreen(Screen parent, List<String> pool, List<RaidBossDef> candidates, String selfId) {
        super(Component.translatable("gui.dmz_ragnarok.raid.pool.title"), UI_W, UI_H, parent);
        this.pool = pool;
        this.candidates = candidates;
        this.selfId = selfId;
    }

    @Override
    protected void init() {
        super.init();

        // Derive the row cap from the space between the list top and the footer so the list fills the panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = candidates.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            RaidBossDef d = candidates.get(i);
            boolean self = d.id != null && d.id.equalsIgnoreCase(selfId);
            boolean in = contains(d.id);
            label(d.name + " (" + d.raidType.label() + ")", 12, y + 5);
            var b = btn(234, y, 44, 14, Component.translatable(self
                            ? "gui.dmz_ragnarok.raid.pool.self"
                            : in ? "gui.dmz_ragnarok.raid.pool.in" : "gui.dmz_ragnarok.raid.pool.out"),
                    () -> {
                        toggle(d.id);
                        rebuildWidgets();
                    });
            // A raid may not roll itself; the row still shows so it is obvious why it cannot be picked.
            b.active = !self;
            y += ROW_H;
        }
        scrollList(12, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(UI_W - 106, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.raid.common.back"), this::back);
    }

    private boolean contains(String id) {
        if (id == null) return false;
        for (String e : pool) {
            if (id.equalsIgnoreCase(e)) return true;
        }
        return false;
    }

    private void toggle(String id) {
        if (id == null || id.isBlank()) return;
        for (int i = 0; i < pool.size(); i++) {
            if (id.equalsIgnoreCase(pool.get(i))) {
                pool.remove(i);
                return;
            }
        }
        pool.add(id);
    }
}
