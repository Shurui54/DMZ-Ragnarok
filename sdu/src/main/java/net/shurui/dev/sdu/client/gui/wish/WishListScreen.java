package net.shurui.dev.sdu.client.gui.wish;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.wish.WishSetData;

import java.util.ArrayList;
import java.util.List;

/**
 * Top-level wishes editor: one row per dragon that exists, opening its wish set for editing.
 *
 * <p>It used to do a second job as well, creating fully-custom dragons that were written out as generated
 * DragonMineZ dragonball packs. That whole route (a custom dragon, its ball set and its wishes, delivered
 * through a pack) has been removed, so this edits the wishes of dragons that already exist and nothing more.
 * Wishes are stored by {@code WishFileManager} under {@code <world>/dragonminez/wishes/}, which is DMZ's own
 * world data rather than a pack, and is untouched by that removal.
 */
public class WishListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private static final java.util.Set<String> DEFAULT_DRAGONS = java.util.Set.of("shenron", "porunga");

    private final List<WishSetData> sets;
    private boolean showDefaults = false;
    private int scroll;

    public WishListScreen(List<WishSetData> sets) {
        super(Component.translatable("gui.dmz_ragnarok.npc.wishes.title"), UI_W, UI_H, null);
        this.sets = sets;
    }

    /** DMZ's own two are hidden behind the toggle, so a server's own dragons are what you see first. */
    private List<WishSetData> visibleSets() {
        List<WishSetData> out = new ArrayList<>();
        for (WishSetData s : sets) {
            String id = s.dragon == null ? "" : s.dragon.toLowerCase();
            if (showDefaults || !DEFAULT_DRAGONS.contains(id)) {
                out.add(s);
            }
        }
        return out;
    }

    public static void open(List<WishSetData> sets) {
        Minecraft.getInstance().setScreen(new WishListScreen(new ArrayList<>(sets)));
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<WishSetData> vis = visibleSets();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final WishSetData set = vis.get(i);
            boolean def = DEFAULT_DRAGONS.contains(set.dragon == null ? "" : set.dragon.toLowerCase());
            label("§b" + set.dragon + (def ? " §7(" + tr("gui.dmz_ragnarok.npc.common.default") + ")" : "")
                    + " §7" + tr("gui.dmz_ragnarok.npc.wish_list.wish_count", set.wishes.size()), 10, y + 5);
            btn(222, y, 34, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"),
                    () -> minecraft.setScreen(new WishSetEditScreen(this, set)));
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });
        tooltip(10, LIST_TOP, 240, maxRows * ROW_H, tr("gui.dmz_ragnarok.npc.wishes.tip"));

        btn(6, footerY(), 82, footerBtnHeight(), Component.translatable(showDefaults ? "gui.dmz_ragnarok.npc.btn.defaults_on" : "gui.dmz_ragnarok.npc.btn.defaults_off"),
                () -> { showDefaults = !showDefaults; scroll = 0; rebuildWidgets(); });
        btn(198, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.SduHubScreen()));
    }
}
