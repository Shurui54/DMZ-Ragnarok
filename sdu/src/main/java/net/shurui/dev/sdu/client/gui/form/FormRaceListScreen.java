package net.shurui.dev.sdu.client.gui.form;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzRaces;
import net.shurui.dev.sdu.client.GeneratedLang;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.form.FormGroupData;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Top of the form editor: pick a race (or the global stack) to drill into its form groups. Keeps the
 * flat list short - each race is one row and opens {@link FormListScreen} scoped to that race.
 */
public class FormRaceListScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;
    private static final int LIST_TOP = 34;

    private final List<FormGroupData> allGroups;
    private final List<String> races;
    private final java.util.Set<String> defaultRaces = DmzRaces.defaultRaceIds();
    private boolean showDefaults = false;
    private int scroll;

    /** The Global Stack row plus custom races always; DMZ default races only when the toggle is on. */
    private List<String> visible() {
        List<String> out = new ArrayList<>();
        for (String r : races) {
            if (r.isBlank() || showDefaults || !defaultRaces.contains(r)) {
                out.add(r);
            }
        }
        return out;
    }

    public FormRaceListScreen(List<FormGroupData> allGroups) {
        super(Component.translatable("gui.dmz_ragnarok.npc.forms.title"), UI_W, UI_H, null);
        this.allGroups = allGroups;
        this.races = buildRaces();
    }

    public static void open(List<FormGroupData> groups) {
        Minecraft.getInstance().setScreen(new FormRaceListScreen(new ArrayList<>(groups)));
    }

    /** "" (the global stack) first, then every known race, then any owner races only present in the data. */
    private List<String> buildRaces() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add("");
        set.addAll(DmzRaces.raceIds());
        for (FormGroupData g : allGroups) {
            set.add(g.primaryOwner());
        }
        return new ArrayList<>(set);
    }

    private int count(String race) {
        int n = 0;
        for (FormGroupData g : allGroups) {
            if (g.primaryOwner().equals(race)) {
                n++;
            }
        }
        return n;
    }

    private static String raceName(String race) {
        String name = GeneratedLang.raceName(race);
        return net.shurui.dev.sdu.util.ColorCodes.translate(name == null || name.isBlank() ? race : name);
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        List<String> vis = visible();
        int total = vis.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final String race = vis.get(i);
            boolean def = !race.isBlank() && defaultRaces.contains(race);
            String disp = race.isBlank() ? "§9" + tr("gui.dmz_ragnarok.npc.formrace_list.global_stack")
                    : "§b" + raceName(race) + (def ? " §7(" + tr("gui.dmz_ragnarok.npc.common.default") + ")" : "");
            label(disp + " §7" + tr("gui.dmz_ragnarok.npc.formrace_list.group_count", count(race)), 10, y + 5);
            btn(214, y, 62, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.open"),
                    () -> minecraft.setScreen(new FormListScreen(this, allGroups, race)));
            y += ROW_H;
        }
        scrollList(10, uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll, v -> { scroll = v; rebuildWidgets(); });

        btn(6, footerY(), 84, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.form_types"),
                () -> minecraft.setScreen(new FormTypesScreen(this)));
        btn(94, footerY(), 92, footerBtnHeight(), Component.translatable(showDefaults ? "gui.dmz_ragnarok.npc.btn.defaults_on" : "gui.dmz_ragnarok.npc.btn.defaults_off"),
                () -> { showDefaults = !showDefaults; scroll = 0; rebuildWidgets(); });
        btn(212, footerY(), 70, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new net.shurui.dev.sdu.client.gui.SduHubScreen()));
    }
}
