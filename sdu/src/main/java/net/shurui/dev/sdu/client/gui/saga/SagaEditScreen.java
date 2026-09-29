package net.shurui.dev.sdu.client.gui.saga;

import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.Saga;
import com.google.gson.Gson;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveSagaPacket;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Edit a saga's id/name/previous-saga and its ordered quest list; Save writes it to the world save. */
public class SagaEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final Gson GSON = new Gson();

    /** Copied quest, shared across screens so it survives navigating between sagas. */
    private static SagaData.Quest clipboard;

    private final SagaData saga;
    private EditBox idField, nameField, prevField;
    private DmzDropdown gateSagaDropdown, gateQuestDropdown;
    // Parallel to the two gate dropdowns' option rows. Index 0 is always the "none" row (blank saga / quest 0).
    private final List<String> gateSagaIds = new ArrayList<>();
    private final List<Integer> gateQuestIds = new ArrayList<>();
    private int scroll;

    public SagaEditScreen(Screen parent, SagaData saga) {
        super(Component.translatable("gui.dmz_ragnarok.npc.saga_edit.edit_saga"), UI_W, UI_H, parent);
        this.saga = saga;
    }

    @Override
    protected void init() {
        super.init();
        // First content row must clear the header/logo (logo bottom sits at y=23): start it at CONTENT_TOP so
        // the field never sits under the wordmark (the reviewer's "logo overlaps the Saga ID field"). The label
        // is offset within the same row; contentTop() clamps the field top up to the header floor.
        int row1 = contentTop(22);
        label( tr("gui.dmz_ragnarok.npc.saga_edit.saga_id"), 12, row1 + 4);
        idField = field(72, row1, 188, saga.id);
        tooltip(12, row1 - 2, 250, 18, tr("gui.dmz_ragnarok.npc.saga_edit.t_unique_saga_id_lower"));
        label( tr("gui.dmz_ragnarok.npc.saga_edit.name"), 12, 46);
        nameField = field(72, 42, 188, saga.name);
        tooltip(12, 40, 250, 18, tr("gui.dmz_ragnarok.npc.saga_edit.t_display_name_of_the"));
        label( tr("gui.dmz_ragnarok.npc.saga_edit.prev_saga"), 12, 66);
        prevField = field(72, 62, 188, saga.previousSaga);
        tooltip(12, 60, 250, 18, tr("gui.dmz_ragnarok.npc.saga_edit.t_id_of_the_saga_that"));

        // Addon quest prerequisite: gate this saga behind a specific quest in another saga. Two dependent
        // dropdowns - pick the saga, then a quest within it. Both blank/none = no quest gate (today's default).
        // ANDed with the previous-saga gate above.
        buildGateOptions();
        label( tr("gui.dmz_ragnarok.npc.saga_edit.req_quest_saga"), 12, 86);
        gateSagaDropdown = dropdown(96, 82, 164, gateSagaLabels(), currentGateSagaRow()).searchable();
        label( tr("gui.dmz_ragnarok.npc.saga_edit.req_quest"), 12, 106);
        gateQuestDropdown = dropdown(96, 102, 164, gateQuestLabels(), currentGateQuestRow()).searchable();
        tooltip(12, 80, 250, 42, tr("gui.dmz_ragnarok.npc.saga_edit.t_req_quest"));

        // Region-flag bypass: when on, this saga's quests may be started (and resummoned) even inside a region
        // whose quest-start flag is set to deny. Off by default, so existing sagas stay blocked. Enforced
        // server-side by shuruisutilities' MixinDmzQuestStartRegion, which reads the mirrored sidecar via
        // SagaRegionBypass; the value rides the saga bundle (SagaData.allowStartInQuestBlockedRegion).
        flagRow(12, 124, 16,
                new Flag(tr("gui.dmz_ragnarok.npc.saga_edit.flag_region_bypass"),
                        saga.allowStartInQuestBlockedRegion,
                        () -> { applyFields();
                                saga.allowStartInQuestBlockedRegion = !saga.allowStartInQuestBlockedRegion;
                                rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.saga_edit.t_region_bypass")));

        int listTop = 152;
        // Reserve 24px so the list stops above the add/paste button row at y=212 (contentBottom 236 - 212).
        int maxRows = rowsThatFit(listTop, ROW_H, 24);
        int total = saga.quests.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        String more = total > maxRows ? "  §7(" + (scroll + 1) + "-" + end + "/" + total + ")" : "";
        label("§e" + tr("gui.dmz_ragnarok.npc.saga_edit.hdr_quests") + more, 12, 142);

        int y = listTop;
        for (int i = scroll; i < end; i++) {
            final SagaData.Quest q = saga.quests.get(i);
            String tag = q.branch ? "§d⌥ " : "§7#" + q.id + " §r"; // branch quests marked, main line shows id
            String qName = q.title == null || q.title.isBlank() ? "" : net.minecraft.client.resources.language.I18n.get(q.title);
            label(tag + qName + (q.branch ? " §8" + tr("gui.dmz_ragnarok.npc.saga_edit.branch_of", q.branchParentId()) : ""), 14, y + 5);
            btn(150, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new QuestEditScreen(this, saga, q)));
            btn(188, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = q.copy());
            // Standard circular X delete, right-aligned to the reserved row-control column so it never sits under
            // the scrollbar (rowControlRight applies the same bar-width + gap reservation the fields use).
            iconBtnRight(rowControlRight(), y, ROW_H, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> {
                saga.quests.remove(q);
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(14, uiWidth, listTop, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });

        tooltip(14, 142, 250, 10, tr("gui.dmz_ragnarok.npc.saga_edit.t_quests_in_this_saga"));
        commitBtn(12, 212, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.saga_edit.add_quest"), this::addQuest);
        tooltip(12, 212, 120, 16, tr("gui.dmz_ragnarok.npc.saga_edit.t_add_a_new_quest_to_t"));
        if (clipboard != null) {
            commitBtn(140, 212, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.saga_edit.paste_quest"), this::pasteQuest);
            tooltip(140, 212, 120, 16, tr("gui.dmz_ragnarok.npc.saga_edit.t_paste_the_copied_que"));
        }

        commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.saga_edit.save_saga"), this::save);
        tooltip(UI_W / 2 - 118, UI_H - 24, 110, 18, tr("gui.dmz_ragnarok.npc.saga_edit.t_write_this_saga_to_t"));
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::applyAndBack);
        tooltip(UI_W / 2 + 8, UI_H - 24, 110, 18, tr("gui.dmz_ragnarok.npc.saga_edit.t_return_to_the_saga_l"));
    }

    /** Rebuild the (saga, quest) option lists for the two gate dropdowns from the client saga registry. */
    private void buildGateOptions() {
        gateSagaIds.clear();
        gateSagaIds.add(""); // row 0 = no quest gate
        Map<String, Saga> sagas = new TreeMap<>(QuestRegistry.getClientSagas());
        for (String id : sagas.keySet()) {
            gateSagaIds.add(id);
        }
        // Keep a stored gate saga selectable even if the client registry hasn't loaded it (e.g. authored this
        // session, restart pending), so re-opening the editor doesn't silently drop the author's choice.
        if (saga.prereqQuestSaga != null && !saga.prereqQuestSaga.isBlank()
                && !gateSagaIds.contains(saga.prereqQuestSaga)) {
            gateSagaIds.add(saga.prereqQuestSaga);
        }
        gateQuestIds.clear();
        gateQuestIds.add(0); // row 0 = no quest gate
        Saga gateSaga = saga.prereqQuestSaga == null || saga.prereqQuestSaga.isBlank()
                ? null : QuestRegistry.getClientSagas().get(saga.prereqQuestSaga);
        if (gateSaga != null) {
            for (Quest q : gateSaga.getQuests()) {
                gateQuestIds.add(q.getId());
            }
        }
        // Preserve a stored quest id that the registry can't enumerate, for the same reason as the saga above.
        if (saga.prereqQuestId > 0 && !gateQuestIds.contains(saga.prereqQuestId)) {
            gateQuestIds.add(saga.prereqQuestId);
        }
    }

    private List<Component> gateSagaLabels() {
        List<Component> out = new ArrayList<>();
        for (String id : gateSagaIds) {
            if (id.isEmpty()) {
                out.add(Component.translatable("gui.dmz_ragnarok.npc.saga_edit.req_none"));
            } else {
                Saga s = QuestRegistry.getClientSagas().get(id);
                String name = s != null && s.getName() != null && !s.getName().isBlank() ? s.getName() : id;
                out.add(Component.literal(name));
            }
        }
        return out;
    }

    private int currentGateSagaRow() {
        int idx = gateSagaIds.indexOf(saga.prereqQuestSaga == null ? "" : saga.prereqQuestSaga);
        return Math.max(0, idx);
    }

    private List<Component> gateQuestLabels() {
        List<Component> out = new ArrayList<>();
        Saga gateSaga = saga.prereqQuestSaga == null || saga.prereqQuestSaga.isBlank()
                ? null : QuestRegistry.getClientSagas().get(saga.prereqQuestSaga);
        for (int qid : gateQuestIds) {
            if (qid == 0) {
                out.add(Component.translatable("gui.dmz_ragnarok.npc.saga_edit.req_none"));
                continue;
            }
            Quest q = gateSaga == null ? null : gateSaga.getQuestById(qid);
            if (q != null && q.getTitle() != null && !q.getTitle().isBlank()) {
                out.add(Component.literal("#" + qid + " ").append(Component.translatable(q.getTitle())));
            } else {
                out.add(Component.literal("#" + qid));
            }
        }
        return out;
    }

    private int currentGateQuestRow() {
        int idx = gateQuestIds.indexOf(saga.prereqQuestId);
        return Math.max(0, idx);
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        if (dropdown == gateSagaDropdown) {
            applyFields();
            saga.prereqQuestSaga = row >= 0 && row < gateSagaIds.size() ? gateSagaIds.get(row) : "";
            saga.prereqQuestId = 0; // the saga changed, so its quest choice no longer applies
            rebuildWidgets();
        } else if (dropdown == gateQuestDropdown) {
            applyFields();
            saga.prereqQuestId = row >= 0 && row < gateQuestIds.size() ? gateQuestIds.get(row) : 0;
            rebuildWidgets();
        }
    }

    private void addQuest() {
        applyFields();
        SagaData.Quest q = new SagaData.Quest();
        q.id = nextQuestId();
        q.title = "Quest " + q.id;
        saga.quests.add(q);
        minecraft.setScreen(new QuestEditScreen(this, saga, q));
    }

    /** Paste the copied quest into this saga with a fresh, unique quest id. */
    private void pasteQuest() {
        applyFields();
        SagaData.Quest q = clipboard.copy();
        q.id = nextQuestId();
        q.title = q.title + " (Copy)";
        saga.quests.add(q);
        rebuildWidgets();
    }

    private int nextQuestId() {
        int nextId = 1;
        for (SagaData.Quest q : saga.quests) {
            nextId = Math.max(nextId, q.id + 1);
        }
        return nextId;
    }

    private void applyFields() {
        saga.id = net.shurui.dev.sdu.util.SduIds.sanitize(idField.getValue());
        saga.name = nameField.getValue().trim();
        saga.previousSaga = prevField.getValue().trim();
        saga.questFolder = "saga_" + saga.id;
    }

    private void applyAndBack() {
        applyFields();
        back();
    }

    private void save() {
        applyFields();
        DmzNet.sendLargeToServer("saga", GSON.toJson(saga.toBundle()));
        back();
    }
}
