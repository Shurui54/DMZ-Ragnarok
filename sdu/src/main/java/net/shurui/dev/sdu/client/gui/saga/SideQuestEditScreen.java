package net.shurui.dev.sdu.client.gui.saga;

import com.google.gson.Gson;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzQuestNpcs;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveSideQuestPacket;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.saga.SagaData;
import net.shurui.dev.sdu.saga.SideQuestData;

import java.util.ArrayList;
import java.util.List;

/**
 * Edit one side quest: id/title/description/category, the optional NPC quest-giver and turn-in,
 * flags, and its objectives, rewards and start requirements. Save writes it to the world save.
 */
public class SideQuestEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    // Canvas reduced from 440: the whole body scrolls in a content band, so the panel need not be tall
    // enough to show every section at once.
    private static final int UI_H = GuiTheme.SCREEN_H;
    /** Top of the scroll band (below the title). The whole body is laid out from here minus {@link #scroll}. */
    private static final int BODY_TOP = 20;
    /** Uniform downward shift so the first body row clears the header/logo; see the twin constant in
     *  {@code QuestEditScreen}. Lifts BODY_TOP (20) to {@link GuiTheme#CONTENT_TOP} (28). */
    private static final int BODY_Y_OFFSET = GuiTheme.CONTENT_TOP - BODY_TOP;
    private static final Gson GSON = new Gson();

    private final SideQuestData quest;
    /** NPC ids already used by other loaded side quests, merged into the giver/turn-in dropdowns. */
    private final List<String> knownNpcIds;
    private EditBox idField, titleField, descField, categoryField, repeatField;
    private DmzDropdown giverDropdown, turnInDropdown;
    /** Option-index -> npc id for the giver/turn-in dropdowns (index 0 = "(none)"). */
    private final List<String> giverOptionIds = new ArrayList<>();
    private final List<String> turnInOptionIds = new ArrayList<>();
    private final int[] scrolls = new int[4];
    private final int[][] sectionRegions = new int[4][];
    private final int[] sectionCap = new int[4];
    private final int[] sectionCount = new int[4];
    /** Index of the section whose scrollbar thumb is being dragged, or -1. */
    private int draggingSection = -1;
    // Own per-section scrollbars (see QuestEditScreen): pin the column X and width to the SAME reserved column the
    // shared rule uses so no trailing control is ever drawn under the bar. SCROLLBAR_X = uiWidth -
    // SCROLLBAR_PANEL_INSET - SCROLLBAR_WIDTH = 280 at the full drawn width (12), not the old thin 3px bar at 287.
    private static final int SCROLLBAR_X = UI_W - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
    private static final int SCROLLBAR_W = GuiTheme.SCROLLBAR_WIDTH;

    public SideQuestEditScreen(Screen parent, SideQuestData quest, List<String> knownNpcIds) {
        super(Component.translatable("gui.dmz_ragnarok.npc.sidequest_edit.edit_side_quest"), UI_W, UI_H, parent);
        this.quest = quest;
        this.knownNpcIds = knownNpcIds == null ? new ArrayList<>() : knownNpcIds;
    }

    /** Shift a body Y by the current scroll offset (the whole body lives inside the content band). */
    private int sy(int baseY) {
        return baseY + BODY_Y_OFFSET - scroll;
    }

    @Override
    protected void init() {
        super.init();
        // Everything below the title scrolls as one band; Save/Back are added afterwards so they stay pinned. The
        // band top carries the same BODY_Y_OFFSET as the content so it clips below the header, not on the logo.
        int contentTop = BODY_TOP + BODY_Y_OFFSET;
        int contentBottom = UI_H - 26;
        beginScrollBand(contentTop, contentBottom);

        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.id"), 10, sy(26));
        idField = field(34, sy(22), 116, quest.id);
        tooltip(10, sy(20), 142, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_unique_side_quest_id"));
        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.cat"), 158, sy(26));
        categoryField = field(182, sy(22), 108, quest.category);
        tooltip(156, sy(20), 136, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_category_used_to_gro"));

        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.title"), 10, sy(46));
        titleField = field(46, sy(42), 244, quest.title);
        tooltip(10, sy(40), 280, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_the_quest_s_title_sh"));
        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.desc"), 10, sy(66));
        descField = field(46, sy(62), 244, quest.description);
        tooltip(10, sy(60), 280, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_the_quest_s_descript"));

        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.giver"), 10, sy(86));
        giverDropdown = npcDropdown(46, sy(82), 104, quest.questGiver, giverOptionIds);
        tooltip(10, sy(80), 140, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_npc_that_offers_this"));
        label( tr("gui.dmz_ragnarok.npc.sidequest_edit.turn_in"), 158, sy(86));
        turnInDropdown = npcDropdown(198, sy(82), 92, quest.turnIn, turnInOptionIds);
        tooltip(156, sy(80), 136, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_npc_the_quest_is_tur"));

        // Quest type: SIDEQUEST (one-shot) vs DAILY/EVENT (repeatable). Pair a repeatable type with a
        // TIME start requirement to set its cooldown.
        btn(10, sy(100), 140, GuiTheme.BUTTON_HEIGHT, Component.literal(typeLabel()), () -> { apply(); cycleType(); rebuildWidgets(); });
        tooltip(10, sy(100), 140, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_quest_type"));
        // Repeat delay (addon feature): after this quest is COMPLETED it re-unlocks for the player once
        // this many seconds elapse (0 = off). 86400 = daily, 604800 = weekly. Enforced addon-side by
        // QuestRepeatHandler; independent of the DMZ DAILY/EVENT type above (DMZ has no repeat cooldown).
        label(tr("gui.dmz_ragnarok.npc.sidequest_edit.repeat_delay"), 158, sy(104));
        repeatField = field(212, sy(100), 78, Long.toString(quest.repeatIntervalSeconds));
        tooltip(156, sy(98), 136, 16, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_repeat_delay"));

        // Flags. Laid out by the shared flagRow helper so the whole strip fits inside the reserved content
        // column (stops at rowControlRight()) and the trailing flag no longer runs under the scrollbar.
        flagRow(10, sy(118), 16,
                new Flag(tr("gui.dmz_ragnarok.npc.sidequest_edit.flag_party"), quest.partyScaling,
                        () -> { quest.partyScaling = !quest.partyScaling; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.sidequest_edit.t_party_scale_objectiv")),
                new Flag(tr("gui.dmz_ragnarok.npc.sidequest_edit.flag_secret"), quest.secret,
                        () -> { quest.secret = !quest.secret; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.sidequest_edit.t_secret_hide_this_que")),
                new Flag(tr("gui.dmz_ragnarok.npc.sidequest_edit.flag_parallel"), quest.parallelObjectives,
                        () -> { quest.parallelObjectives = !quest.parallelObjectives; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.sidequest_edit.t_parallel_objectives")),
                new Flag(tr("gui.dmz_ragnarok.npc.sidequest_edit.flag_consume"), quest.consumeItems,
                        () -> { quest.consumeItems = !quest.consumeItems; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.sidequest_edit.t_consume")));

        section(0, tr("gui.dmz_ragnarok.npc.sidequest_edit.hdr_objectives"), sy(140), quest.objectives.size(), 3,
                i -> quest.objectives.get(i).summary(),
                i -> minecraft.setScreen(new ObjectiveEditScreen(this, quest.objectives.get(i), false)),
                i -> { quest.objectives.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Objective o = new SagaData.Objective(); quest.objectives.add(o); minecraft.setScreen(new ObjectiveEditScreen(this, o, true)); },
                sy(200),
                (i, dir) -> {
                    int j = i + dir;
                    if (j < 0 || j >= quest.objectives.size()) return;
                    apply();
                    java.util.Collections.swap(quest.objectives, i, j);
                    rebuildWidgets();
                });
        tooltip(12, sy(140), 270, 8, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_objectives_what_the"));

        section(1, tr("gui.dmz_ragnarok.npc.sidequest_edit.hdr_rewards"), sy(216), quest.rewards.size(), 2,
                i -> quest.rewards.get(i).summary(),
                i -> minecraft.setScreen(new RewardEditScreen(this, quest.rewards.get(i))),
                i -> { quest.rewards.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Reward r = new SagaData.Reward(); quest.rewards.add(r); minecraft.setScreen(new RewardEditScreen(this, r)); },
                sy(260));
        tooltip(12, sy(216), 270, 8, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_rewards_what_the_pla"));

        section(2, tr("gui.dmz_ragnarok.npc.sidequest_edit.hdr_requirements"), sy(276), quest.requirements.size(), 2,
                i -> quest.requirements.get(i).summary(),
                i -> minecraft.setScreen(new ConditionEditScreen(this, quest.requirements.get(i))),
                i -> { quest.requirements.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Condition c = new SagaData.Condition(); quest.requirements.add(c); minecraft.setScreen(new ConditionEditScreen(this, c)); },
                sy(320));
        tooltip(12, sy(276), 270, 8, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_requirements_conditi"));

        // Prerequisites: DMZ HIDES the quest entirely until these are met (Requirements only block the
        // start with a reason). Side quests normally have no SAGA_QUEST branch link, but guard defensively:
        // index through editablePrereqs() so any SAGA_QUEST entry is never shown, edited, or dropped.
        section(3, tr("gui.dmz_ragnarok.npc.sidequest_edit.hdr_prerequisites"), sy(336), editablePrereqs().size(), 2,
                i -> quest.prerequisites.get(editablePrereqs().get(i)).summary(),
                i -> minecraft.setScreen(new ConditionEditScreen(this, quest.prerequisites.get(editablePrereqs().get(i)))),
                i -> { quest.prerequisites.remove((int) editablePrereqs().get(i)); rebuildWidgets(); },
                () -> { apply(); SagaData.Condition c = new SagaData.Condition(); quest.prerequisites.add(c); minecraft.setScreen(new ConditionEditScreen(this, c)); },
                sy(380));
        tooltip(12, sy(336), 270, 8, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_prerequisites_hide"));

        // Band spans the whole body; total content height = the prereqs add button at 380 + its 14px height.
        // Close the band before Save/Back so those buttons are never hidden by the clamp.
        finishScrollBand(contentTop, contentBottom, sy(380 + 14));
        commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.save"), this::save);
        tooltip(UI_W / 2 - 118, UI_H - 22, 110, 18, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_write_this_side_ques"));
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { apply(); back(); });
        tooltip(UI_W / 2 + 8, UI_H - 22, 110, 18, tr("gui.dmz_ragnarok.npc.sidequest_edit.t_return_to_the_side_q"));
    }

    private interface IntStr { String get(int i); }
    private interface IntAct { void run(int i); }
    private interface IntMove { void run(int i, int dir); }

    /** Indices into {@code quest.prerequisites} of the author-editable conditions: everything EXCEPT any
     *  auto-managed SAGA_QUEST branch link. Side quests normally have none, but this keeps the section from
     *  showing/editing/removing one if present. */
    private List<Integer> editablePrereqs() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < quest.prerequisites.size(); i++) {
            if (!"SAGA_QUEST".equals(quest.prerequisites.get(i).type)) {
                out.add(i);
            }
        }
        return out;
    }

    private void section(int si, String header, int headerY, int count, int cap, IntStr summary,
                         IntAct edit, IntAct remove, Runnable add, int addY) {
        section(si, header, headerY, count, cap, summary, edit, remove, add, addY, null);
    }

    private void section(int si, String header, int headerY, int count, int cap, IntStr summary,
                         IntAct edit, IntAct remove, Runnable add, int addY, IntMove move) {
        int maxScroll = Math.max(0, count - cap);
        int scroll = Math.max(0, Math.min(scrolls[si], maxScroll));
        scrolls[si] = scroll;

        String more = count > cap ? "  §7(" + (scroll + 1) + "-" + Math.min(count, scroll + cap) + "/" + count + ")" : "";
        label("§e" + header + more, 12, headerY);

        int listTop = headerY + 10;
        int y = listTop;
        int shown = Math.min(cap, count - scroll);
        for (int i = 0; i < shown; i++) {
            final int idx = scroll + i;
            label("§7- §r" + summary.get(idx), 16, y + 4, 0xFFE0E0E0);
            if (move != null) {
                // Reorder arrows: order is what players see AND what gates progression (non-parallel
                // quests complete objectives top to bottom). Standard circular icon discs.
                iconBtnAt(172, y, 16, Component.literal(idx > 0 ? "▲" : "§8▲"), () -> move.run(idx, -1));
                iconBtnAt(192, y, 16, Component.literal(idx < count - 1 ? "▼" : "§8▼"), () -> move.run(idx, 1));
            }
            btn(212, y, 40, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> edit.run(idx));
            // Unified circular X delete, right-aligned to the shared reserved column (rowControlRight) so it stops
            // one grid UNIT left of the scrollbar and never sits under it.
            iconBtnRight(rowControlRight(), y, 16, Component.translatable("gui.dmz_ragnarok.npc.btn.x"), () -> remove.run(idx));
            y += 16;
        }

        if (count > cap) {
            int listH = cap * 16;
            rect(SCROLLBAR_X, listTop, SCROLLBAR_W, listH, 0xFF10281A);
            int thumbH = Math.max(8, listH * cap / count);
            int thumbY = listTop + (maxScroll == 0 ? 0 : (listH - thumbH) * scroll / maxScroll);
            rect(SCROLLBAR_X, thumbY, SCROLLBAR_W, thumbH, draggingSection == si ? 0xFF9BE0AB : 0xFF6AB07A);
        }
        sectionRegions[si] = new int[]{listTop, listTop + cap * 16};
        sectionCap[si] = cap;
        sectionCount[si] = count;

        btn(12, addY, 150, GuiTheme.BUTTON_HEIGHT, Component.literal(tr("gui.dmz_ragnarok.npc.sidequest_edit.add", header)), add);
    }

    /** Flush field text before an outer-band wheel-scroll rebuild, so in-progress typing isn't lost. */
    @Override
    protected void applyBeforeBandScroll() {
        apply();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        double vy = toVirtualY(my);
        for (int si = 0; si < sectionRegions.length; si++) {
            int[] region = sectionRegions[si];
            // Only claim the wheel for a section list that actually overflows; otherwise let the wheel fall
            // through to the outer content band (super) so the whole panel can scroll.
            if (region != null && sectionCount[si] > sectionCap[si] && vy >= region[0] && vy < region[1]) {
                scrolls[si] = Math.max(0, scrolls[si] - (int) Math.signum(delta));
                apply();
                rebuildWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        double vx = toVirtualX(mx), vy = toVirtualY(my);
        int si = scrollbarSectionAt(vx, vy);
        if (si >= 0) {
            draggingSection = si;
            dragSectionTo(si, vy);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingSection >= 0) {
            dragSectionTo(draggingSection, toVirtualY(my));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (draggingSection >= 0) {
            draggingSection = -1;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** Section whose scrollbar track (and overflow) contains the point, or -1. */
    private int scrollbarSectionAt(double vx, double vy) {
        if (vx < SCROLLBAR_X - 1 || vx >= SCROLLBAR_X + SCROLLBAR_W) {
            return -1;
        }
        for (int si = 0; si < sectionRegions.length; si++) {
            int[] region = sectionRegions[si];
            if (region != null && sectionCount[si] > sectionCap[si] && vy >= region[0] && vy < region[1]) {
                return si;
            }
        }
        return -1;
    }

    /** Snap a section's scroll so the thumb centre tracks the cursor, then rebuild the list. */
    private void dragSectionTo(int si, double vy) {
        int cap = sectionCap[si], count = sectionCount[si];
        int maxScroll = Math.max(0, count - cap);
        int listTop = sectionRegions[si][0];
        int listH = cap * 16;
        int thumbH = Math.max(8, listH * cap / Math.max(1, count));
        double travel = listH - thumbH;
        double t = travel <= 0 ? 0 : (vy - listTop - thumbH / 2.0) / travel;
        int scroll = (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * maxScroll);
        if (scroll != scrolls[si]) {
            scrolls[si] = scroll;
            apply();
            rebuildWidgets();
        }
    }

    /** Searchable NPC-id dropdown: option 0 = "(none)", then canonical + known + current ids. Fills
     *  {@code optionIds} parallel to the options (index 0 = ""). */
    private DmzDropdown npcDropdown(int x, int y, int w, String current, List<String> optionIds) {
        List<String> extra = new ArrayList<>(knownNpcIds);
        if (current != null && !current.isBlank()) {
            extra.add(current);
        }
        optionIds.clear();
        optionIds.add("");
        optionIds.addAll(DmzQuestNpcs.npcIds(extra));
        List<Component> opts = new ArrayList<>();
        opts.add(Component.translatable("gui.dmz_ragnarok.npc.sidequest_edit.none"));
        for (int i = 1; i < optionIds.size(); i++) {
            opts.add(Component.literal(optionIds.get(i)));
        }
        int sel = current == null ? 0 : Math.max(0, optionIds.indexOf(current));
        return dropdown(x, y, w, opts, sel).searchable();
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        if (dropdown == giverDropdown) {
            apply();
            quest.questGiver = row >= 0 && row < giverOptionIds.size() ? giverOptionIds.get(row) : "";
            rebuildWidgets();
        } else if (dropdown == turnInDropdown) {
            apply();
            quest.turnIn = row >= 0 && row < turnInOptionIds.size() ? turnInOptionIds.get(row) : "";
            rebuildWidgets();
        }
    }

    private void label(String t, int x, int y, int color) {
        labels.add(new Lbl(t, x, y, color, false));
    }

    /** Repeatable (DAILY/EVENT) types show green; the one-shot SIDEQUEST shows grey. */
    private String typeLabel() {
        boolean repeatable = !"SIDEQUEST".equals(quest.questType);
        return (repeatable ? "§a" : "§7") + tr("gui.dmz_ragnarok.npc.sidequest_edit.type_label", quest.questType);
    }

    /** Advance the quest type through {@link SideQuestData#QUEST_TYPES} (SIDEQUEST → DAILY → EVENT → …). */
    private void cycleType() {
        String[] types = SideQuestData.QUEST_TYPES;
        int idx = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i].equals(quest.questType)) { idx = i; break; }
        }
        quest.questType = types[(idx + 1) % types.length];
    }

    private void apply() {
        quest.id = net.shurui.dev.sdu.util.SduIds.sanitize(idField.getValue());
        quest.title = titleField.getValue().trim();
        quest.description = descField.getValue().trim();
        quest.category = categoryField.getValue().trim().isEmpty() ? "general" : categoryField.getValue().trim();
        try {
            quest.repeatIntervalSeconds = Math.max(0L, Long.parseLong(repeatField.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        // questGiver / turnIn are set directly by the dropdowns (onDropdownSelect).
    }

    private void save() {
        apply();
        DmzNet.sendLargeToServer("sidequest", GSON.toJson(quest.toBundle()));
        back();
    }
}
