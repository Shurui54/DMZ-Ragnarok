package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// per-region editor: tabbed FieldEditScreen with Flags, Settings (priority + teleport) and Members.
// meta = [name, dim, priority]; typed rows flag,label,id,state / owner,uuid,name / member,uuid,name.
public class RegionEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.region.tab_flags", "gui.dmz_ragnarok.core.region.tab_settings",
            "gui.dmz_ragnarok.core.region.tab_members" };

    private static int lastTab = 0;
    // kept across rebuilds (each flag edit recreates the screen) so scroll survives; reset on tab/region change
    private static int scroll = 0;
    private static String lastRegion = null;

    private final String region;
    private final String dim;
    private String priority;

    private final List<String[]> flags = new ArrayList<>();   // {label, id, state}
    private final List<String[]> people = new ArrayList<>();   // {type owner/member, uuid, name}

    private int tab;
    private EditBox addBox;

    public RegionEditScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.regions"), UI_W, UI_H, null);
        this.region = meta.isEmpty() ? "" : meta.get(0);
        this.dim = meta.size() > 1 ? meta.get(1) : "";
        this.priority = meta.size() > 2 ? meta.get(2) : "0";
        for (List<String> r : rows)
        {
            if (r.isEmpty())
                continue;
            switch (r.get(0))
            {
                case "flag" -> flags.add(new String[] { r.get(1), r.get(2), r.size() > 3 ? r.get(3) : "0" });
                case "owner" -> people.add(new String[] { "owner", r.get(1), r.size() > 2 ? r.get(2) : r.get(1) });
                case "member" -> people.add(new String[] { "member", r.get(1), r.size() > 2 ? r.get(2) : r.get(1) });
                default -> { }
            }
        }
        this.tab = Math.max(0, Math.min(lastTab, 2));
        if (!region.equals(lastRegion))
        {
            scroll = 0;
            lastRegion = region;
        }
    }

    private void selectTab(int t)
    {
        applyFields();
        tab = t;
        lastTab = t;
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = buildNamedTabHeader(tr("gui.dmz_ragnarok.core.region.header", region, shortDim(dim)), trAll(SECTION_KEYS), tab, this::selectTab);

        if (tab == 0)
            buildFlags();
        else if (tab == 1)
            buildSettings();
        else
            buildMembers();

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("regions"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void buildFlags()
    {
        int top = rowY;
        int rh = 14;
        int cap = rowsThatFit(top, rh);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, flags.size() - cap)));
        int end = Math.min(flags.size(), scroll + cap);
        for (int i = scroll; i < end; i++)
        {
            String[] f = flags.get(i);
            final String id = f[1];
            String st = f[2];
            String tag = st.equals("1") ? tr("gui.dmz_ragnarok.core.protection.allow")
                    : st.equals("2") ? tr("gui.dmz_ragnarok.core.protection.deny")
                    : tr("gui.dmz_ragnarok.core.protection.unset");
            int color = st.equals("1") ? 0xFF55FF55 : st.equals("2") ? 0xFFFF5555 : 0xFF888888;
            // Row stops at the scrollbar-reserved column so its trailing [tag] never sits under the bar.
            rowBtn(14, top + (i - scroll) * rh, rowControlRight() - 14, rh, Component.literal(f[0]),
                    () -> EditorScreens.act("regions", "cycle", region, id)).right(Component.literal("[" + tag + "]"), color);
        }
        scrollList(14, uiWidth, top, rh, cap, flags.size(), scroll, v -> { scroll = v; rebuildWidgets(); });
    }

    private void buildSettings()
    {
        tf(tr("gui.dmz_ragnarok.core.region.priority"), priority, v -> priority = v);
        tip(tr("gui.dmz_ragnarok.core.region.priority_tip"));
        btn(14, rowY + 4, 90, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.save"), () -> {
            applyFields();
            EditorScreens.act("regions", "priority", region, priority.trim());
        });
        btn(110, rowY + 4, 90, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.region.teleport"),
                () -> EditorScreens.runCommand("serverclaim tp " + region));
        label("§7" + tr("gui.dmz_ragnarok.core.region.dimension", dim), 14, rowY + 26, 0xFFB0B0B0);
        label("§7" + tr("gui.dmz_ragnarok.core.region.bounds_note"), 14, rowY + 38, 0xFF888888);
    }

    private void buildMembers()
    {
        int top = rowY;
        int rh = 14;
        // Reserve holds the add-member field and its Add owner / Add member buttons (drawn at UI_H - 44) clear.
        int cap = rowsThatFit(top, rh, 24);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, people.size() - cap)));
        int end = Math.min(people.size(), scroll + cap);
        for (int i = scroll; i < end; i++)
        {
            String[] pp = people.get(i);
            boolean owner = pp[0].equals("owner");
            final String uuid = pp[1];
            int ry = top + (i - scroll) * rh;
            label((owner ? "§6[O] " : "§b[M] ") + pp[2], 14, ry + 3, 0xFFFFFFFF);
            btn(rowControlRight() - 40, ry, 40, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("regions", owner ? "removeowner" : "removemember", region, uuid));
        }
        scrollList(14, uiWidth, top, rh, cap, people.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        int y = UI_H - 44;
        addBox = field(14, y, 120, "");
        addBox.setHint(Component.translatable("gui.dmz_ragnarok.core.region.player_hint"));
        addBox.setMaxLength(32);
        btn(138, y - 1, 66, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.region.add_owner"), () -> add(true));
        btn(208, y - 1, 78, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.region.add_member"), () -> add(false));
    }

    private void add(boolean owner)
    {
        String name = addBox.getValue().trim();
        if (!name.isBlank())
            EditorScreens.act("regions", owner ? "addowner" : "addmember", region, name);
    }

    private static String shortDim(String dim)
    {
        int i = dim.indexOf(':');
        return i >= 0 ? dim.substring(i + 1) : dim;
    }
}
