package net.shurui.dev.sdu.client.gui.form;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.form.FormData;
import net.shurui.dev.sdu.form.FormGroupData;
import net.shurui.dev.sdu.network.DeleteFormPacket;
import net.shurui.dev.sdu.network.DmzNet;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The form groups belonging to one race (or the global stack), reached by picking a race in
 * {@link FormRaceListScreen}. Keeps the full group set so new groups persist and ids stay unique, but
 * only shows (and creates) groups owned by this race.
 */
public class FormListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    /** Copied group, shared across screens so it survives navigation. */
    private static FormGroupData clipboard;

    private final List<FormGroupData> allGroups;
    private final String raceFilter;
    private int scroll;
    /** Group name armed for deletion: first Del click arms it, a second click confirms. Reset on any rebuild. */
    private String pendingDelete;
    /**
     * Why the last paste was refused, drawn above the footer until the next paste attempt. Null when there is
     * nothing to say. Pre-formatted (form id, then the group that already holds it) by {@link #duplicateFormClash}.
     */
    private String pasteRefusal;
    /** Virtual px reserved under the list for {@link #pasteRefusal}, so the list never grows over the message. */
    private static final int REFUSAL_ROW_H = 10;

    public FormListScreen(Screen parent, List<FormGroupData> allGroups, String raceFilter) {
        super(titleFor(raceFilter), UI_W, UI_H, parent);
        this.allGroups = allGroups;
        this.raceFilter = raceFilter == null ? "" : raceFilter;
    }

    private static Component titleFor(String race) {
        if (race == null || race.isBlank()) {
            return Component.translatable("gui.dmz_ragnarok.npc.forms.global_stack");
        }
        String name = GeneratedLang.raceName(race);
        return Component.literal(net.shurui.dev.sdu.util.ColorCodes.translate(name == null || name.isBlank() ? race : name));
    }

    private List<FormGroupData> visible() {
        List<FormGroupData> out = new ArrayList<>();
        for (FormGroupData g : allGroups) {
            if (g.primaryOwner().equals(raceFilter)) {
                out.add(g);
            }
        }
        return out;
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = "Forms";
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, pasteRefusal == null ? 0 : REFUSAL_ROW_H);
        List<FormGroupData> vis = visible();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final FormGroupData g = vis.get(i);
            String shown = g.displayName != null && !g.displayName.isBlank() ? g.displayName : g.groupName;
            label("§b" + net.shurui.dev.sdu.util.ColorCodes.translate(shown) + " §7" + tr("gui.dmz_ragnarok.npc.form_list.form_count", g.forms.size()), 10, y + 5);
            btn(168, y, 36, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"), () -> minecraft.setScreen(new FormGroupEditScreen(this, g)));
            btn(206, y, 32, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.copy"), () -> clipboard = g.copy());
            boolean armed = g.groupName.equals(pendingDelete);
            // circular X delete; armed state swaps to the confirm marker
            iconBtnRight(rowControlRight(), y, ROW_H,
                    Component.translatable(armed ? "gui.dmz_ragnarok.npc.btn.confirm_icon" : "gui.dmz_ragnarok.npc.btn.x"), () -> {
                if (armed) {
                    DmzNet.sendToServer(new DeleteFormPacket(g.primaryOwner(), g.groupName));
                    allGroups.remove(g);
                    pendingDelete = null;
                } else {
                    pendingDelete = g.groupName;
                }
                rebuildWidgets();
            });
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> {
            scroll = v;
            rebuildWidgets();
        });

        commitBtn(6, footerY(), 96, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.new_group"), this::newGroup);
        if (clipboard != null) {
            commitBtn(108, footerY(), 74, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.paste"), this::pasteGroup);
        }
        btn(212, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
    }

    private void newGroup() {
        FormGroupData g = new FormGroupData();
        g.groupName = uniqueName("custom_forms");
        g.ownerRaces.clear();
        g.ownerRaces.add(raceFilter);
        allGroups.add(g);
        minecraft.setScreen(new FormGroupEditScreen(this, g));
    }

    /**
     * Paste the copied group onto this race, unless doing so would duplicate a form this race already has.
     *
     * <p>A unique group NAME is not enough. DMZ gates a form on its group's {@code formType} (the skill), never on
     * the group name, so pasting {@code godforms} beside {@code godforms} gave the race a SECOND
     * {@code supersaiyangod} on the same {@code godforms} skill: one purchase, two copies, both unlocked, and a
     * player who transformed through the copy sat in a group the Super Saiyan God ritual cleanup did not recognise,
     * so their temporary god form was never taken back. That happened on a live shard. A refusal here is not
     * cosmetic tidiness, it is what stops the duplicate from existing.
     *
     * <p>Only the target race is examined, and only groups that resolve to the SAME form skill, so the ordinary use
     * (copying a group onto a race that does not have it) is untouched. Nothing is renamed or dropped behind the
     * user's back: a clash is reported and the paste does not happen.
     */
    private void pasteGroup() {
        String clash = duplicateFormClash(clipboard);
        if (clash != null) {
            pasteRefusal = clash;
            rebuildWidgets();
            return;
        }
        FormGroupData g = clipboard.copy();
        g.groupName = uniqueName(g.groupName + "_copy");
        g.ownerRaces.clear();
        g.ownerRaces.add(raceFilter);
        allGroups.add(g);
        pasteRefusal = null;
        rebuildWidgets();
    }

    /**
     * The message for the first form {@code src} would duplicate on this race, or null when the paste adds nothing
     * that is already there. A duplicate is a form with the same id (map key) or the same {@code name} living in a
     * group of this race that resolves to the same form SKILL as {@code src}.
     */
    private String duplicateFormClash(FormGroupData src) {
        if (src == null) {
            return null;
        }
        String srcSkill = formSkill(src.formType);
        for (FormGroupData g : allGroups) {
            if (g == src || !g.primaryOwner().equals(raceFilter) || !formSkill(g.formType).equals(srcSkill)) {
                continue;
            }
            for (Map.Entry<String, FormData> e : src.forms.entrySet()) {
                String id = holdsForm(g, e.getKey(), e.getValue());
                if (id != null) {
                    return tr("gui.dmz_ragnarok.npc.form_list.paste_duplicate", id, g.groupName);
                }
            }
        }
        return null;
    }

    /** The colliding id when {@code g} already holds this form (by key or by name), else null. */
    private static String holdsForm(FormGroupData g, String key, FormData form) {
        if (key != null && g.forms.containsKey(key)) {
            return key;
        }
        String name = form == null || form.name == null ? "" : form.name.trim();
        if (name.isEmpty()) {
            return null;
        }
        for (FormData other : g.forms.values()) {
            if (other != null && other.name != null && other.name.trim().equalsIgnoreCase(name)) {
                return name;
            }
        }
        return null;
    }

    /**
     * The form SKILL a {@code formType} is gated by, lowercased. DMZ's routing is SUBSTRING based (any type
     * containing {@code godform} / {@code superform} / {@code legendaryform} / {@code androidform} lands on that
     * stock skill), and sdu's {@code TransformationsHelperMixin} excepts registered custom types, so we ask DMZ
     * itself rather than reimplement the rule. Guarded: if that call ever goes away we fall back to the raw type,
     * which at worst compares two groups that share a literal type.
     */
    private static String formSkill(String formType) {
        String raw = formType == null ? "" : formType.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty()) {
            return "";
        }
        try {
            String skill = com.dragonminez.common.util.TransformationsHelper.getSkillNameForType(raw);
            return skill == null || skill.isBlank() ? raw : skill.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return raw;
        }
    }

    /**
     * Draw the paste refusal above the footer. Half scale, like the form-type id warning, because the sentence
     * names two ids and does not fit the 300px canvas at full size.
     */
    @Override
    protected void renderTopOverlay(net.minecraft.client.gui.GuiGraphics g, int vmx, int vmy) {
        super.renderTopOverlay(g, vmx, vmy);
        if (pasteRefusal == null) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(10, footerY() - REFUSAL_ROW_H + 1, 0);
        g.pose().scale(0.5f, 0.5f, 1f);
        g.drawString(this.font, pasteRefusal, 0, 0, 0xFFFF6B6B, false);
        g.pose().popPose();
    }

    private String uniqueName(String base) {
        String name = base;
        int n = 2;
        while (exists(name)) {
            name = base + "_" + n++;
        }
        return name;
    }

    private boolean exists(String name) {
        for (FormGroupData g : allGroups) {
            if (g.groupName.equals(name)) {
                return true;
            }
        }
        return false;
    }
}
