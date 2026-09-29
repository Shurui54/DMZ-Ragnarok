package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_dungeons.block.KiMoveEntry;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import java.util.List;

// edit one ki blast: searchable type dropdown (all 21 DMZ KiSkillTypes incl DOUBLE_SUNDAY), a Min/Max
// interval range (ticks between uses), a size, and a colour swatch (opens DmzColorPicker). writes back into
// the backing token list as a KiMoveEntry token (TYPE:cdMin:cdMax:size:colorMain).
public class KiMoveEntryEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final KiMoveEntry move;

    private DmzDropdown typeDropdown;

    public KiMoveEntryEditScreen(Screen parent, List<String> backing, int index) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.ki_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.move = KiMoveEntry.fromToken(backing.get(index));
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        headerSubtitle = I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.subtitle");
        typeDropdown = null;

        rowY = 30;

        label(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.type"), 14, rowY + 5);
        typeDropdown = dropdown(150, rowY, 132, options(KiMoveEntry.TYPES), indexOf(KiMoveEntry.TYPES, move.type)).searchable();
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.type_tip"));

        tf(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.interval_min"), intStr(move.cdMin), v -> move.cdMin = Math.max(1, parseI(v, move.cdMin)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.interval_min_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.interval_max"), intStr(move.cdMax), v -> move.cdMax = Math.max(1, parseI(v, move.cdMax)));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.interval_max_tip"));
        tf(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.size"), dbl(move.size), v -> move.size = parseD(v, move.size));
        tip(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.size_tip"));
        cf(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.colour"), move::colorHex, move::setColorHex);
        tip(I18n.get("gui.dmz_ragnarok.dungeons.ki_edit.colour_tip"));

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.dungeons.common.save"), () -> {
            applyFields();
            if (move.cdMax < move.cdMin) { // someone typed max < min, just swap them
                int t = move.cdMin;
                move.cdMin = move.cdMax;
                move.cdMax = t;
            }
            backing.set(index, move.toToken());
            back();
        });
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == typeDropdown && row >= 0 && row < KiMoveEntry.TYPES.length) {
            applyFields();
            move.type = KiMoveEntry.TYPES[row];
            rebuildWidgets();
        }
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equalsIgnoreCase(v)) {
                return i;
            }
        }
        return 0;
    }
}
