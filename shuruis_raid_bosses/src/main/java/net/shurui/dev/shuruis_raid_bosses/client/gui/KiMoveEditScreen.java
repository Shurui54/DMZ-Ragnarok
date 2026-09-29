package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_raid_bosses.raid.KiMove;

import java.util.ArrayList;
import java.util.List;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * Edit one ki move: a searchable move-type dropdown plus cooldown (ticks) and size inputs. Writes the
 * result back into the backing token list as a {@link KiMove} token.
 */
public class KiMoveEditScreen extends BaseEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<String> backing;
    private final int index;
    private final KiMove move;

    private DmzDropdown typeDropdown;
    private EditBox cooldownField, sizeField;

    public KiMoveEditScreen(Screen parent, List<String> backing, int index) {
        super(Component.translatable("gui.dmz_ragnarok.raid.ki_edit.title"), UI_W, UI_H, parent);
        this.backing = backing;
        this.index = index;
        this.move = KiMove.fromToken(backing.get(index));
    }

    @Override
    protected void init() {
        super.init();
        // Named entity: show the ki move's type in the panel's top left.
        panelName = move.type;
        label(I18n.get("gui.dmz_ragnarok.raid.ki_edit.move"), 12, 26);
        List<Component> opts = new ArrayList<>();
        for (String t : KiMove.TYPES) opts.add(Component.literal(t));
        typeDropdown = dropdown(60, 22, 228, opts, indexOf(KiMove.TYPES, move.type)).searchable();
        tooltip(12, 20, 240, 16, I18n.get("gui.dmz_ragnarok.raid.ki_edit.move_tip"));

        int y = 52;
        label(I18n.get("gui.dmz_ragnarok.raid.ki_edit.cooldown"), 12, y + 3);
        cooldownField = field(72, y, 60, Integer.toString(move.cooldown));
        tooltip(12, y, 240, 12, I18n.get("gui.dmz_ragnarok.raid.ki_edit.cooldown_tip"));
        y += 22;
        label(I18n.get("gui.dmz_ragnarok.raid.ki_edit.size"), 12, y + 3);
        sizeField = field(72, y, 60, trim(move.size));
        tooltip(12, y, 240, 12, I18n.get("gui.dmz_ragnarok.raid.ki_edit.size_tip"));

        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.raid.common.save_plain"), () -> {
            apply();
            backing.set(index, move.toToken());
            back();
        });
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        apply();
        if (dropdown == typeDropdown && row >= 0 && row < KiMove.TYPES.length) {
            move.type = KiMove.TYPES[row];
        }
        rebuildWidgets();
    }

    private void apply() {
        if (cooldownField != null) move.cooldown = Math.max(1, parseInt(cooldownField.getValue(), move.cooldown));
        if (sizeField != null) move.size = parseDouble(sizeField.getValue(), move.size);
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return Double.toString(v);
    }

    private static int indexOf(String[] arr, String v) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equalsIgnoreCase(v)) return i;
        return 0;
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    private static double parseDouble(String s, double fb) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
