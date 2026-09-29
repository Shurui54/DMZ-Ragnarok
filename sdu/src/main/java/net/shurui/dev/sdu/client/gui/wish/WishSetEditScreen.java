package net.shurui.dev.sdu.client.gui.wish;

import com.google.gson.Gson;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveWishPacket;
import net.shurui.dev.sdu.wish.WishData;
import net.shurui.dev.sdu.wish.WishSetData;

/**
 * Edit one dragon's wish set: the scrollable list of its wishes. The dragon id is fixed (shown as a
 * heading) - this screen only edits wishes; it does not create new dragons or dragonball packs.
 */
public class WishSetEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final Gson GSON = new Gson();

    private static WishData clipboard;

    private final WishSetData set;
    /** When true this set belongs to a custom dragon: edits stay in-memory and are saved with its pack. */
    private final boolean embedded;
    private int scroll;

    public WishSetEditScreen(Screen parent, WishSetData set) {
        this(parent, set, false);
    }

    public WishSetEditScreen(Screen parent, WishSetData set, boolean embedded) {
        super(Component.translatable("gui.dmz_ragnarok.npc.wishset_edit.edit_wishes"), UI_W, UI_H, parent);
        this.set = set;
        this.embedded = embedded;
    }

    @Override
    protected void init() {
        super.init();
        label("§b" + tr("gui.dmz_ragnarok.npc.wishset.dragon", set.dragon), 12, 26);
        tooltip(12, 20, 268, 16, tr("gui.dmz_ragnarok.npc.wishset.editing", set.dragon));

        int listTop = 98;
        // Reserve 40px so the list stops above the add/paste button row at y=196 (contentBottom 236 - 196).
        int maxRows = rowsThatFit(listTop, ROW_H, 40);
        int total = set.wishes.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        String more = total > maxRows ? "  §7(" + (scroll + 1) + "-" + end + "/" + total + ")" : "";
        label("§e" + tr("gui.dmz_ragnarok.npc.wishset.hdr_wishes") + more, 12, 86);

        int y = listTop;
        for (int i = scroll; i < end; i++) {
            final WishData w = set.wishes.get(i);
            String wishName = w.name == null || w.name.isBlank() ? "" : net.minecraft.client.resources.language.I18n.get(w.name);
            label("§7- §r" + w.summary(wishName), 16, y + 5);
            btn(160, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new WishEditScreen(this, w)));
            btn(198, y, 34, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = w.copy());
            // Unified circular X delete, right-aligned to the reserved scrollbar column.
            iconBtnRight(rowControlRight(), y, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.btn.x"),
                    () -> { set.wishes.remove(w); rebuildWidgets(); });
            y += ROW_H;
        }
        scrollList(14, uiWidth, listTop, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(12, 196, 128, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.wishset_edit.add_wish"), this::addWish);
        if (clipboard != null) {
            commitBtn(148, 196, 128, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.wishset_edit.paste_wish"), () -> { set.wishes.add(clipboard.copy()); rebuildWidgets(); });
        }
        if (embedded) {
            // The custom dragon's editor persists these wishes when it writes the pack; just go back.
            btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
        } else {
            commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.save"), this::save);
            tooltip(UI_W / 2 - 118, UI_H - 24, 110, 18, tr("gui.dmz_ragnarok.npc.wishset_edit.t_write_the_wishes_for"));
            btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
        }
    }

    private void addWish() {
        set.wishes.add(new WishData());
        minecraft.setScreen(new WishEditScreen(this, set.wishes.get(set.wishes.size() - 1)));
    }

    private void save() {
        DmzNet.sendLargeToServer("wish", GSON.toJson(set.toBundle()));
        back();
    }
}
