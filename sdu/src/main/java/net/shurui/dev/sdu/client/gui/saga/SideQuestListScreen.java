package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.network.DeleteSideQuestPacket;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.saga.SideQuestData;

import java.util.ArrayList;
import java.util.List;

/** Top-level side-quest editor: lists side quests found in the world save; create / edit / copy / delete. */
public class SideQuestListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    /** Copied side quest, shared across screens so it survives navigating in and out. */
    private static SideQuestData clipboard;

    private final List<SideQuestData> quests;
    private boolean showDefaults = false;
    private int scroll;

    public SideQuestListScreen(List<SideQuestData> quests) {
        super(Component.translatable("gui.dmz_ragnarok.npc.sidequests.title"), UI_W, UI_H, null);
        this.quests = quests;
    }

    /** A DMZ default side quest uses a lang-key title (e.g. {@code dmz.sidequest.*.name}); custom ones are plain text. */
    private static boolean isDefault(SideQuestData sq) {
        return sq.title != null && net.minecraft.client.resources.language.I18n.exists(sq.title);
    }

    /** The quest's shown name: its title resolved through the language file when it's a lang key. */
    private static String displayName(SideQuestData sq) {
        return net.minecraft.client.resources.language.I18n.get(sq.title);
    }

    private List<SideQuestData> visible() {
        List<SideQuestData> out = new ArrayList<>();
        for (SideQuestData sq : quests) {
            if (showDefaults || !isDefault(sq)) {
                out.add(sq);
            }
        }
        return out;
    }

    public static void open(List<SideQuestData> quests) {
        Minecraft.getInstance().setScreen(new SideQuestListScreen(new ArrayList<>(quests)));
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<SideQuestData> vis = visible();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final SideQuestData sq = vis.get(i);
            String tag = isDefault(sq) ? " §9(default)" : "";
            label(displayName(sq) + tag + "  §7(" + sq.category + ")", 10, y + 5);
            btn(150, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new SideQuestEditScreen(this, sq, knownNpcIds())));
            btn(188, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = sq.copy());
            // Unified circular X delete, right-aligned to the reserved scrollbar column.
            iconBtnRight(rowControlRight(), y, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> {
                if (sq.fileName != null && !sq.fileName.isBlank()) {
                    DmzNet.sendToServer(new DeleteSideQuestPacket(sq.fileName));
                }
                quests.remove(sq);
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });

        commitBtn(6, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.new"), this::newQuest);
        btn(56, footerY(), 88, footerBtnHeight(), Component.translatable(showDefaults ? "gui.dmz_ragnarok.npc.btn.defaults_on" : "gui.dmz_ragnarok.npc.btn.defaults_off"),
                () -> { showDefaults = !showDefaults; scroll = 0; rebuildWidgets(); });
        if (clipboard != null) {
            commitBtn(146, footerY(), 52, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.paste"), this::pasteQuest);
        }
        btn(200, footerY(), 62, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.SduHubScreen()));
    }

    private void newQuest() {
        SideQuestData sq = new SideQuestData();
        sq.id = uniqueId("custom_sidequest");
        sq.title = "New Side Quest";
        sq.fileName = "";
        quests.add(sq);
        minecraft.setScreen(new SideQuestEditScreen(this, sq, knownNpcIds()));
    }

    /** All quest-giver / turn-in ids currently used by the loaded side quests (for the editor dropdowns). */
    private List<String> knownNpcIds() {
        List<String> ids = new ArrayList<>();
        for (SideQuestData s : quests) {
            if (s.questGiver != null && !s.questGiver.isBlank()) {
                ids.add(s.questGiver);
            }
            if (s.turnIn != null && !s.turnIn.isBlank()) {
                ids.add(s.turnIn);
            }
        }
        return ids;
    }

    /** Paste the copied side quest as a new, uniquely-identified quest (edit + Save to persist it). */
    private void pasteQuest() {
        SideQuestData sq = clipboard.copy();
        sq.id = uniqueId(sq.id + "_copy");
        sq.title = sq.title + " (Copy)";
        sq.fileName = "";     // a fresh file, so it doesn't overwrite the original
        quests.add(sq);
        rebuildWidgets();
    }

    /** Make an id that doesn't collide with an existing side quest, appending _2, _3, ... if needed. */
    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (exists(id)) {
            id = base + "_" + n++;
        }
        return id;
    }

    private boolean exists(String id) {
        for (SideQuestData s : quests) {
            if (s.id.equals(id)) {
                return true;
            }
        }
        return false;
    }
}
