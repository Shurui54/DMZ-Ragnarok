package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.config.editor.OptionType;
import net.shurui.dev.sdu.config.editor.OptionView;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Edits one list option of the options editor: one text box per entry, a remove button on each, an add button, and
 * Done/Cancel. Done hands the joined value back to {@link OptionsOverviewScreen} as a pending edit (nothing is saved
 * until Save there), so the same server checks apply to every entry.
 */
public class OptionListEditScreen extends FieldEditScreen {

    private static final int UI_W = 320;
    private static final int UI_H = 260;
    private static final int ROW = 14;

    private final OptionsOverviewScreen owner;
    private final OptionView view;
    private final Consumer<String> onDone;
    private final List<String> items;
    private final List<EditBox> boxes = new ArrayList<>();

    OptionListEditScreen(OptionsOverviewScreen owner, OptionView view, String label, String value,
                         Consumer<String> onDone) {
        super(Component.literal(label), UI_W, UI_H, owner);
        this.owner = owner;
        this.view = view;
        this.onDone = onDone;
        this.items = new ArrayList<>(OptionView.splitList(value));
    }

    OptionsOverviewScreen owner() {
        return owner;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        boxes.clear();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.options.list_subtitle", items.size());
        int top = GuiTheme.CONTENT_TOP + 2;
        int bottom = footerY() - 16;
        beginScrollBand(top, bottom);
        rowY = top - scroll;
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            label("§7" + (i + 1), 12, rowY + 3);
            EditBox b = field(30, rowY + 2, 236, items.get(i), 1024);
            boxes.add(b);
            btn(270, rowY + 1, 14, 11, Component.literal("×"), () -> {
                readBack();
                items.remove(idx);
                rebuildWidgets();
            });
            tooltip(270, rowY + 1, 14, 11, tr("gui.dmz_ragnarok.npc.options.list_remove"));
            rowY += ROW;
        }
        commitBtn(30, rowY + 2, 80, 11, Component.translatable("gui.dmz_ragnarok.npc.common.add"), () -> {
            readBack();
            items.add(view.elementType() == OptionType.STRING ? "" : "0");
            rebuildWidgets();
        });
        rowY += ROW + 2;
        finishScrollBand(top, bottom, rowY);

        int fy = footerY();
        commitBtn(UI_W / 2 - 104, fy, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.options.list_done"),
                this::done);
        btn(UI_W / 2 + 4, fy, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.cancel"),
                () -> minecraft.setScreen(owner));
    }

    /** Escape returns to the options editor (its pending edits intact) rather than closing everything. */
    @Override
    public void onClose() {
        minecraft.setScreen(owner);
    }

    private void readBack() {
        for (int i = 0; i < boxes.size() && i < items.size(); i++) {
            items.set(i, boxes.get(i).getValue());
        }
    }

    @Override
    protected void applyBeforeBandScroll() {
        readBack();
    }

    private void done() {
        readBack();
        List<String> clean = new ArrayList<>();
        for (String s : items) {
            String t = view.elementType() == OptionType.STRING ? s : s.trim();
            if (view.elementType() != OptionType.STRING && t.isEmpty()) {
                continue;
            }
            clean.add(t.replace(OptionView.LIST_SEP, ' '));
        }
        onDone.accept(OptionView.joinList(clean));
        minecraft.setScreen(owner);
    }
}
