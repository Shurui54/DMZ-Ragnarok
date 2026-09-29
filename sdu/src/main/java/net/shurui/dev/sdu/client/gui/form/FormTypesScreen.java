package net.shurui.dev.sdu.client.gui.form;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.form.FormTypeManager;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveFormTypePacket;

import java.util.ArrayList;
import java.util.List;

/**
 * Lists DragonMine Z's form types (form skills) and lets you register custom ones. Custom types become
 * selectable as a form group's {@code formType} and are unlockable per race via the Costs tab. DMZ's
 * built-ins are marked and can't be deleted (their display name can still be overridden).
 */
public class FormTypesScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<String> types;
    private int scroll;
    /** Form-type id armed for deletion: first Del click arms it, a second confirms. Reset on screen change. */
    private String pendingDelete;

    public FormTypesScreen(Screen parent) {
        super(Component.translatable("gui.dmz_ragnarok.npc.btn.form_types"), UI_W, UI_H, parent);
        this.types = loadTypes();
    }

    /** DMZ built-in form + stack skills that can't be deleted (or renamed). */
    static boolean isDefault(String type) {
        return FormTypeManager.DEFAULTS.contains(type) || FormTypeManager.STACK_DEFAULTS.contains(type);
    }

    private static boolean isRegisteredStack(String type) {
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            return skills != null && skills.getStackSkills() != null && skills.getStackSkills().contains(type);
        } catch (Throwable t) {
            return false;
        }
    }

    /** All registered form + stack skills (DMZ defaults first, then any custom ones), from the live config. */
    private static List<String> loadTypes() {
        List<String> out = new ArrayList<>(FormTypeManager.DEFAULTS);
        for (String s : FormTypeManager.STACK_DEFAULTS) {
            if (!out.contains(s)) {
                out.add(s);
            }
        }
        try {
            var skills = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
            if (skills != null) {
                addAll(out, skills.getFormSkills());
                addAll(out, skills.getStackSkills());
            }
        } catch (Throwable ignored) {
            // DMZ not present / config not ready: just show the defaults.
        }
        return out;
    }

    private static void addAll(List<String> out, List<String> src) {
        if (src == null) {
            return;
        }
        for (String t : src) {
            if (t != null && !t.isBlank() && !out.contains(t)) {
                out.add(t);
            }
        }
    }

    /** Add a newly-registered type so the list reflects it immediately (server applies on rejoin). */
    void addType(String id) {
        if (id != null && !id.isBlank() && !types.contains(id)) {
            types.add(id);
        }
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = types.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final String type = types.get(i);
            boolean def = isDefault(type);
            boolean stack = FormTypeManager.STACK_DEFAULTS.contains(type) || isRegisteredStack(type);
            String name = GeneratedLang.formTypeName(type);
            String shown = net.shurui.dev.sdu.util.ColorCodes.translate((name == null || name.isBlank()) ? type : name);
            label((def ? "§9" : "§b") + shown + " §7(" + type + ")"
                    + (stack ? " §d[" + tr("gui.dmz_ragnarok.npc.formtypes.tag_stack") + "]" : "")
                    + (def ? " §8" + tr("gui.dmz_ragnarok.npc.common.default") : ""), 10, y + 5);
            btn(190, y, 42, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.edit"),
                    () -> minecraft.setScreen(new FormTypeEditScreen(this, type)));
            if (!def) {
                boolean armed = type.equals(pendingDelete);
                // circular X delete; armed state swaps to the confirm marker
                iconBtnRight(rowControlRight(), y, ROW_H,
                        Component.translatable(armed ? "gui.dmz_ragnarok.npc.btn.confirm_icon" : "gui.dmz_ragnarok.npc.btn.x"), () -> {
                    if (armed) {
                        DmzNet.sendToServer(new SaveFormTypePacket(type, true, false, List.of()));
                        types.remove(type);
                        pendingDelete = null;
                    } else {
                        pendingDelete = type;
                    }
                    rebuildWidgets();
                });
            }
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        commitBtn(8, footerY(), 96, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.new_type"),
                () -> minecraft.setScreen(new FormTypeEditScreen(this, null)));
        btn(200, footerY(), 80, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.back"), this::back);
    }
}
