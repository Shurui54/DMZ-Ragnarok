package net.shurui.dev.sdu.client.gui.saga;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.saga.SagaData;

import java.util.ArrayList;
import java.util.List;

/** Edit one quest: title/description/id, flags, tree parent, and its objectives, rewards and requirements. */
public class QuestEditScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    // Canvas reduced from 384: the whole quest body now scrolls inside a content band, so the panel no
    // longer has to be tall enough to show every section at once.
    private static final int UI_H = GuiTheme.SCREEN_H;
    /** Top of the scroll band (below the title). The whole body is laid out from here minus {@link #scroll}. */
    private static final int BODY_TOP = 20;
    /**
     * Uniform downward shift applied to every band-body Y via {@link #sy}, so the first content row clears the
     * header/logo (logo bottom sits at y=23; the reviewer's "logo overlaps the first field" also affected this
     * screen's title row). It lifts BODY_TOP (20) to {@link GuiTheme#CONTENT_TOP} (28), i.e. +8, and because
     * every body element is placed through {@code sy()} the whole body moves down together, keeping the internal
     * spacing intact and the band's clip range aligned.
     */
    private static final int BODY_Y_OFFSET = GuiTheme.CONTENT_TOP - BODY_TOP;

    private final SagaData saga;
    private final SagaData.Quest quest;
    private EditBox titleField, descField, idField, timeLimitField, repeatField;
    private DmzDropdown branchDropdown;
    /** Candidate parent quest ids, parallel to the branch dropdown options after index 0 ("root"). */
    private final List<Integer> branchQuestIds = new ArrayList<>();
    private final int[] scrolls = new int[4];
    private final int[][] sectionRegions = new int[4][];
    private final int[] sectionCap = new int[4];
    private final int[] sectionCount = new int[4];
    /** Index of the section whose scrollbar thumb is being dragged, or -1. */
    private int draggingSection = -1;
    // This screen draws its own per-section scrollbars (one per objectives/rewards/requirements list) rather than
    // the shared scrollList bar, so the column X and width are pinned to the SAME reserved column the shared rule
    // uses (scrollbarColumnX(uiWidth)): SCROLLBAR_X = uiWidth - SCROLLBAR_PANEL_INSET - SCROLLBAR_WIDTH = 280 at the
    // full drawn width SCROLLBAR_WIDTH (12), not the old thin 3px bar at 287. Trailing row controls are placed at
    // rowControlRight() (which stops one grid UNIT left of this column) so nothing is ever drawn under the bar (the
    // "controls overlap the scrollbar in the saga gui" defect: the old X reached x=285, into the 280..292 column).
    private static final int SCROLLBAR_X = UI_W - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
    private static final int SCROLLBAR_W = GuiTheme.SCROLLBAR_WIDTH;

    public QuestEditScreen(Screen parent, SagaData saga, SagaData.Quest quest) {
        super(Component.translatable("gui.dmz_ragnarok.npc.quest_edit.edit_quest"), UI_W, UI_H, parent);
        this.saga = saga;
        this.quest = quest;
    }

    /** Shift a body Y by the current scroll offset (the whole body lives inside the content band). */
    private int sy(int baseY) {
        return baseY + BODY_Y_OFFSET - scroll;
    }

    @Override
    protected void init() {
        super.init();
        // Everything below the title scrolls as one band; Back is added afterwards so it stays pinned. The band
        // top carries the same BODY_Y_OFFSET as the content (so it clips below the header, not on the logo).
        int contentTop = BODY_TOP + BODY_Y_OFFSET;
        int contentBottom = UI_H - 24;
        beginScrollBand(contentTop, contentBottom);

        label( tr("gui.dmz_ragnarok.npc.quest_edit.title"), 10, sy(26));
        titleField = field(40, sy(22), 180, quest.title);
        tooltip(10, sy(20), 210, 16, tr("gui.dmz_ragnarok.npc.quest_edit.t_the_quest_s_title_sh"));
        label( tr("gui.dmz_ragnarok.npc.quest_edit.id"), 226, sy(26));
        idField = field(244, sy(22), 46, Integer.toString(quest.id));
        tooltip(224, sy(20), 66, 16, tr("gui.dmz_ragnarok.npc.quest_edit.t_numeric_quest_id_uni"));
        label( tr("gui.dmz_ragnarok.npc.quest_edit.desc"), 10, sy(46));
        descField = field(40, sy(42), 176, quest.description);
        tooltip(10, sy(40), 176, 16, tr("gui.dmz_ragnarok.npc.quest_edit.t_the_quest_s_descript"));
        label( tr("gui.dmz_ragnarok.npc.quest_edit.limit"), 220, sy(46));
        timeLimitField = field(250, sy(42), 40, Integer.toString(quest.timeLimit));
        tooltip(218, sy(40), 72, 16, tr("gui.dmz_ragnarok.npc.quest_edit.t_time_limit_in_second"));

        // Repeat delay (addon feature): after this quest is COMPLETED it re-unlocks for the player once
        // this many seconds elapse (0 = off). 86400 = daily, 604800 = weekly. Enforced addon-side by
        // QuestRepeatHandler (DMZ has no native repeat-with-cooldown and stores no completion timestamp).
        label(tr("gui.dmz_ragnarok.npc.quest_edit.repeat_delay"), 10, sy(90));
        repeatField = field(150, sy(86), 60, Long.toString(quest.repeatIntervalSeconds));
        tooltip(10, sy(84), 210, 16, tr("gui.dmz_ragnarok.npc.quest_edit.t_repeat_delay"));

        // Flags. Laid out by the shared flagRow helper so the whole strip fits inside the reserved content
        // column (stops at rowControlRight()) and the trailing "Branch" flag no longer runs under the scrollbar.
        flagRow(10, sy(58), 16,
                new Flag(tr("gui.dmz_ragnarok.npc.quest_edit.flag_party"), quest.partyScaling,
                        () -> { quest.partyScaling = !quest.partyScaling; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.quest_edit.t_party_scale_objectiv")),
                new Flag(tr("gui.dmz_ragnarok.npc.quest_edit.flag_secret"), quest.secret,
                        () -> { quest.secret = !quest.secret; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.quest_edit.t_secret_hide_this_que")),
                new Flag(tr("gui.dmz_ragnarok.npc.quest_edit.flag_parallel"), quest.parallelObjectives,
                        () -> { quest.parallelObjectives = !quest.parallelObjectives; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.quest_edit.t_parallel_objectives")),
                new Flag(tr("gui.dmz_ragnarok.npc.quest_edit.flag_branch"), quest.branch,
                        () -> { quest.branch = !quest.branch; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.quest_edit.t_branch_put_this_ques")),
                new Flag(tr("gui.dmz_ragnarok.npc.quest_edit.flag_consume"), quest.consumeItems,
                        () -> { quest.consumeItems = !quest.consumeItems; apply(); rebuildWidgets(); },
                        tr("gui.dmz_ragnarok.npc.quest_edit.t_consume")));

        // Tree parent: which quest this one branches off. DMZ positions the quest tree from each
        // quest's prerequisites (a SAGA_QUEST link to its parent), so this is how you shape the tree
        // instead of a straight line. "(root)" = no parent (a starting node of the saga).
        label( tr("gui.dmz_ragnarok.npc.quest_edit.branch_after"), 10, sy(78));
        List<Component> branchOpts = new ArrayList<>();
        branchOpts.add(Component.translatable("gui.dmz_ragnarok.npc.quest_edit.root_no_parent"));
        branchQuestIds.clear();
        int currentParent = currentParentId();
        int branchSel = 0;
        for (SagaData.Quest other : saga.quests) {
            if (other == quest) {
                continue;
            }
            branchQuestIds.add(other.id);
            branchOpts.add(Component.literal("#" + other.id + " " + other.title));
            if (other.id == currentParent) {
                branchSel = branchOpts.size() - 1;
            }
        }
        branchDropdown = dropdown(92, sy(74), 198, branchOpts, branchSel);
        tooltip(10, sy(72), 280, 18, tr("gui.dmz_ragnarok.npc.quest_edit.t_the_parent_quest_tha"));

        section(0, tr("gui.dmz_ragnarok.npc.quest_edit.hdr_objectives"), sy(114), quest.objectives.size(), 3,
                i -> quest.objectives.get(i).summary(),
                i -> minecraft.setScreen(new ObjectiveEditScreen(this, quest.objectives.get(i), false)),
                i -> { quest.objectives.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Objective o = new SagaData.Objective(); quest.objectives.add(o); minecraft.setScreen(new ObjectiveEditScreen(this, o, true)); },
                sy(174),
                (i, dir) -> {
                    int j = i + dir;
                    if (j < 0 || j >= quest.objectives.size()) return;
                    apply();
                    java.util.Collections.swap(quest.objectives, i, j);
                    rebuildWidgets();
                });
        tooltip(12, sy(114), 270, 8, tr("gui.dmz_ragnarok.npc.quest_edit.t_objectives_what_the"));

        section(1, tr("gui.dmz_ragnarok.npc.quest_edit.hdr_rewards"), sy(190), quest.rewards.size(), 2,
                i -> rewardSummary(quest.rewards.get(i)),
                i -> minecraft.setScreen(new RewardEditScreen(this, quest.rewards.get(i))),
                i -> { quest.rewards.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Reward r = new SagaData.Reward(); quest.rewards.add(r); minecraft.setScreen(new RewardEditScreen(this, r)); },
                sy(234));
        tooltip(12, sy(190), 270, 8, tr("gui.dmz_ragnarok.npc.quest_edit.t_rewards_what_the_pla"));

        section(2, tr("gui.dmz_ragnarok.npc.quest_edit.hdr_requirements"), sy(250), quest.requirements.size(), 2,
                i -> quest.requirements.get(i).summary(),
                i -> minecraft.setScreen(new ConditionEditScreen(this, quest.requirements.get(i))),
                i -> { quest.requirements.remove(i); rebuildWidgets(); },
                () -> { apply(); SagaData.Condition c = new SagaData.Condition(); quest.requirements.add(c); minecraft.setScreen(new ConditionEditScreen(this, c)); },
                sy(294));
        tooltip(12, sy(250), 270, 8, tr("gui.dmz_ragnarok.npc.quest_edit.t_extra_start_conditio"));

        // Prerequisites: DMZ HIDES the quest entirely until these are met (unlike Requirements, which
        // show the quest but block starting with a reason). Edit only the author-facing subset: the
        // SAGA_QUEST branch link is auto-managed by "Branch after" (setParent) and must never appear
        // here or be dropped/duplicated, so we index through editablePrereqs() (non-SAGA_QUEST only).
        List<Integer> prereqIdx = editablePrereqs();
        section(3, tr("gui.dmz_ragnarok.npc.quest_edit.hdr_prerequisites"), sy(312), prereqIdx.size(), 2,
                i -> quest.prerequisites.get(editablePrereqs().get(i)).summary(),
                i -> minecraft.setScreen(new ConditionEditScreen(this, quest.prerequisites.get(editablePrereqs().get(i)))),
                i -> { quest.prerequisites.remove((int) editablePrereqs().get(i)); rebuildWidgets(); },
                () -> { apply(); SagaData.Condition c = new SagaData.Condition(); quest.prerequisites.add(c); minecraft.setScreen(new ConditionEditScreen(this, c)); },
                sy(356));
        tooltip(12, sy(312), 270, 8, tr("gui.dmz_ragnarok.npc.quest_edit.t_prerequisites_hide"));

        // Band spans the whole body; total content height = the last laid-out Y (prereqs add button at 356
        // + its 14px height). Close the band before Back so Back is never hidden by the clamp.
        finishScrollBand(contentTop, contentBottom, sy(356 + 14));
        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { apply(); back(); });
    }

    /** Indices into {@code quest.prerequisites} of the author-editable conditions: everything EXCEPT the
     *  auto-managed SAGA_QUEST branch link (owned by setParent). Used so the Prerequisites section never
     *  shows, edits, or removes the branch link. */
    private List<Integer> editablePrereqs() {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < quest.prerequisites.size(); i++) {
            if (!"SAGA_QUEST".equals(quest.prerequisites.get(i).type)) {
                out.add(i);
            }
        }
        return out;
    }

    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        if (dropdown == branchDropdown) {
            apply();
            setParent(row == 0 ? 0 : branchQuestIds.get(row - 1));
            rebuildWidgets();
        }
    }

    /** The quest id this quest currently branches from (via a SAGA_QUEST prerequisite), or 0 = root. */
    private int currentParentId() {
        for (SagaData.Condition c : quest.prerequisites) {
            if ("SAGA_QUEST".equals(c.type) && (c.sagaId.isEmpty() || c.sagaId.equals(saga.id))) {
                return c.questId;
            }
        }
        return 0;
    }

    /** Set (or clear, when parentId == 0) the tree parent by rewriting this quest's SAGA_QUEST prereq. */
    private void setParent(int parentId) {
        quest.prerequisites.removeIf(c -> "SAGA_QUEST".equals(c.type)
                && (c.sagaId.isEmpty() || c.sagaId.equals(saga.id)));
        if (parentId > 0) {
            SagaData.Condition c = new SagaData.Condition();
            c.type = "SAGA_QUEST";
            c.sagaId = saga.id;
            c.questId = parentId;
            quest.prerequisites.add(c);
        }
    }

    /** Reward summary for the list; TECHNIQUE rewards show the DMZ lang name instead of the raw id. */
    private String rewardSummary(SagaData.Reward r) {
        if ("TECHNIQUE".equals(r.type) && r.technique != null && !r.technique.isBlank()) {
            return "Technique " + Component.translatable("technique.dragonminez." + r.technique).getString();
        }
        return r.summary();
    }

    private interface IntStr { String get(int i); }
    private interface IntAct { void run(int i); }
    private interface IntMove { void run(int i, int dir); }

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

        btn(12, addY, 150, GuiTheme.BUTTON_HEIGHT, Component.literal(tr("gui.dmz_ragnarok.npc.quest_edit.add", header)), add);
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

    private void label(String t, int x, int y, int color) {
        labels.add(new Lbl(t, x, y, color, false));
    }

    private void apply() {
        quest.title = titleField.getValue().trim();
        quest.description = descField.getValue().trim();
        try {
            quest.id = Integer.parseInt(idField.getValue().trim());
        } catch (NumberFormatException ignored) {
        }
        try {
            quest.timeLimit = Math.max(0, Integer.parseInt(timeLimitField.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
        try {
            quest.repeatIntervalSeconds = Math.max(0L, Long.parseLong(repeatField.getValue().trim()));
        } catch (NumberFormatException ignored) {
        }
    }
}
