package net.shurui.dev.sdu.client.gui.wish;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzSkills;
import net.shurui.dev.sdu.client.GameItems;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.wish.WishData;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit one {@link WishData}. A type dropdown chooses the wish kind (item/tps/command/skill/multi_wish/…)
 * and the fields below change to match. Name/description are stored as-is - a lang key resolves to its
 * text in-game, or plain text shows literally.
 */
public class WishEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final WishData wish;
    private DmzDropdown typeDropdown;

    public WishEditScreen(Screen parent, WishData wish) {
        super(Component.translatable("gui.dmz_ragnarok.npc.wish_edit.edit_wish"), UI_W, height(wish), parent);
        this.wish = wish;
    }

    /**
     * The multi-wish editor grows a row per item (item dropdown + count + remove) plus an "Add item"
     * button, so the screen height depends on how many items it has; every other type is fixed.
     */
    private static int height(WishData wish) {
        if ("multi_wish".equals(wish.type)) {
            return 132 + Math.max(1, wish.items.size()) * ROW_H * 2 + 22;
        }
        return UI_H;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        label( tr("gui.dmz_ragnarok.npc.wish_edit.type"), 12, 30);
        typeDropdown = dropdown(64, 26, 160, options(WishData.TYPES), indexOf(WishData.TYPES, wish.type));

        rowY = 50;
        tf( tr("gui.dmz_ragnarok.npc.wish_edit.name"), wish.name, v -> wish.name = v);
        tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_shown_title_of_the_w"));
        tf( tr("gui.dmz_ragnarok.npc.wish_edit.description"), wish.description, v -> wish.description = v);
        tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_shown_description_la"));

        switch (wish.type) {
            case "item" -> {
                df( tr("gui.dmz_ragnarok.npc.wish_edit.item"), itemIds(), wish.itemId, v -> wish.itemId = v);
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_the_item_granted"));
                tf( tr("gui.dmz_ragnarok.npc.wish_edit.count"), intStr(wish.count), v -> wish.count = parseI(v, wish.count));
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_how_many_of_the_item"));
            }
            case "tps" -> {
                tf( tr("gui.dmz_ragnarok.npc.wish_edit.tp_amount"), intStr(wish.amount), v -> wish.amount = parseI(v, wish.amount));
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_training_points_gran"));
            }
            case "command" -> {
                tf( tr("gui.dmz_ragnarok.npc.wish_edit.commands"), String.join(" | ", wish.commands), this::setCommands);
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_commands_run_on_gran"));
            }
            case "skill" -> {
                df( tr("gui.dmz_ragnarok.npc.wish_edit.skill"), DmzSkills.skillIds(), wish.skill, v -> wish.skill = v);
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_skill_granted"));
                tf( tr("gui.dmz_ragnarok.npc.wish_edit.level"), intStr(wish.level), v -> wish.level = parseI(v, wish.level));
                tip( tr("gui.dmz_ragnarok.npc.wish_edit.t_skill_level_set"));
            }
            case "multi_wish" -> {
                label(tr("gui.dmz_ragnarok.npc.wish_edit.items"), 14, rowY + 4);
                tooltip(12, rowY, 272, ROW_H, tr("gui.dmz_ragnarok.npc.wish_edit.t_several_items_given"));
                rowY += ROW_H;
                for (int i = 0; i < wish.items.size(); i++) {
                    final WishData.MultiItem m = wish.items.get(i);
                    int dropRow = rowY;
                    df(tr("gui.dmz_ragnarok.npc.wish_edit.item_n", i + 1), itemIds(), m.itemId, v -> m.itemId = v);
                    iconBtnAt(285, dropRow, 11, Component.translatable("gui.dmz_ragnarok.npc.btn.x"),
                            () -> { applyFields(); wish.items.remove(m); rebuildWidgets(); });
                    tf(tr("gui.dmz_ragnarok.npc.wish_edit.count"), intStr(m.count), v -> m.count = parseI(v, m.count));
                }
                commitBtn(14, rowY + 1, 132, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.wish_edit.add_item"),
                        () -> { applyFields(); wish.items.add(new WishData.MultiItem()); rebuildWidgets(); });
                rowY += ROW_H + 2;
            }
            default -> {
                label( tr("gui.dmz_ragnarok.npc.wish_edit.no_extra_fields_for_this"), 14, rowY + 4);
                rowY += ROW_H;
            }
        }

        btn(uiWidth / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { applyFields(); back(); });
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == typeDropdown) {
            applyFields();
            wish.type = WishData.TYPES[Math.max(0, Math.min(row, WishData.TYPES.length - 1))];
            rebuildWidgets();
        }
    }

    private void setCommands(String v) {
        wish.commands.clear();
        for (String part : v.split("\\|")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                wish.commands.add(s);
            }
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals(v)) {
                return i;
            }
        }
        return 0;
    }

    private static List<String> itemIds() {
        List<String> out = new ArrayList<>();
        for (ResourceLocation r : GameItems.itemIds()) {
            out.add(r.toString());
        }
        return out;
    }
}
