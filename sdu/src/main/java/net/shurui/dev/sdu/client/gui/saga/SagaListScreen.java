package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.network.DeleteSagaPacket;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.saga.SagaData;
import net.shurui.dev.sdu.saga.SagaFileManager;

import java.util.ArrayList;
import java.util.List;

/** Top-level saga editor: lists sagas found in the world save; create / edit / copy / delete. */
public class SagaListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    /** Copied saga, shared across screens so it survives navigating in and out. */
    private static SagaData clipboard;

    private final List<SagaData> sagas;
    private boolean showDefaults = false;
    private int scroll;

    public SagaListScreen(List<SagaData> sagas) {
        super(Component.translatable("gui.dmz_ragnarok.npc.sagas.title"), UI_W, UI_H, null);
        this.sagas = sagas;
    }

    private List<SagaData> visible() {
        List<SagaData> out = new ArrayList<>();
        for (SagaData s : sagas) {
            if (showDefaults || !SagaFileManager.isDefault(s.id)) {
                out.add(s);
            }
        }
        return out;
    }

    public static void open(List<SagaData> sagas) {
        Minecraft.getInstance().setScreen(new SagaListScreen(new ArrayList<>(sagas)));
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<SagaData> vis = visible();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final SagaData saga = vis.get(i);
            String tag = SagaFileManager.isDefault(saga.id) ? " §9(" + tr("gui.dmz_ragnarok.npc.common.default") + ")" : "";
            String shown = saga.name != null && !saga.name.isBlank()
                    ? net.minecraft.client.resources.language.I18n.get(saga.name) : saga.id;
            label(shown + tag + "  §7" + tr("gui.dmz_ragnarok.npc.saga_list.quest_count", saga.quests.size()), 10, y + 5);
            btn(150, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new SagaEditScreen(this, saga)));
            btn(188, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = saga.copy());
            // Unified circular X delete, right-aligned to the reserved scrollbar column (was a "Del" word button
            // that ran under the scrollbar; now consistent with every other list's delete icon).
            iconBtnRight(rowControlRight(), y, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> {
                DmzNet.sendToServer(new DeleteSagaPacket(saga.id, saga.questFolder));
                sagas.remove(saga);
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });

        commitBtn(6, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.new"), this::newSaga);
        btn(56, footerY(), 88, footerBtnHeight(), Component.translatable(showDefaults ? "gui.dmz_ragnarok.npc.btn.defaults_on" : "gui.dmz_ragnarok.npc.btn.defaults_off"),
                () -> { showDefaults = !showDefaults; scroll = 0; rebuildWidgets(); });
        if (clipboard != null) {
            commitBtn(146, footerY(), 52, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.paste"), this::pasteSaga);
        }
        btn(200, footerY(), 62, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.SduHubScreen()));
    }

    private void newSaga() {
        SagaData s = new SagaData();
        String id = uniqueId("custom_saga");
        s.id = id;
        s.name = "New Custom Saga";
        s.questFolder = "saga_" + id;
        sagas.add(s);
        minecraft.setScreen(new SagaEditScreen(this, s));
    }

    /** Paste the copied saga as a new, uniquely-identified saga (edit + Save to persist it). */
    private void pasteSaga() {
        SagaData s = clipboard.copy();
        s.id = uniqueId(s.id + "_copy");
        s.name = s.name + " (Copy)";
        s.questFolder = "saga_" + s.id;
        sagas.add(s);
        rebuildWidgets();
    }

    /** Make an id that doesn't collide with an existing saga, appending _2, _3, ... if needed. */
    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (exists(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    private boolean exists(String id) {
        for (SagaData s : sagas) {
            if (s.id.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
