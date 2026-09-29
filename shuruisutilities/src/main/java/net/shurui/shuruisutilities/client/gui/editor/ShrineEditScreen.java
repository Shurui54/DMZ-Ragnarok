package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.util.StringUtil;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// shrine edit view: tabbed. Settings edits display name (& codes), type (STAT|TP), stat key (STAT only),
// percent, duration, cooldown (labels show H:MM:SS next to the raw-seconds field). Bindings is a read-only list
// of bound positions with per-row Unbind (binding is done in-world). Save sends
// "save name displayName type statKey percent duration cooldown"; server clamps/persists + re-sends.
// meta = [name, displayName, type, statKey, percent, duration, cooldown], rows = [posKey, "dim x y z"].
public class ShrineEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.shrine.tab_settings", "gui.dmz_ragnarok.core.shrine.tab_bindings" };

    // stat keys a STAT shrine may buff (mirrors ConfigShrine's default allow-list)
    private static final String[] STAT_KEYS = { "STR", "SKP", "RES", "STM", "VIT", "PWR", "ENE" };
    private static final String[] TYPES = { "STAT", "TP" };

    private final String name;
    private String displayName;
    private int typeIdx;
    private String statKey;
    private String percent;
    private String duration;
    private String cooldown;

    private final List<List<String>> bindings;
    private int section = 0;
    private int bindScroll = 0;

    private DmzDropdown typeDd, statDd;

    public ShrineEditScreen(List<String> meta, List<List<String>> bindings)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.shrines"), UI_W, UI_H, null);
        this.name = meta.get(0);
        this.displayName = meta.size() > 1 ? meta.get(1) : "";
        String type = meta.size() > 2 ? meta.get(2) : "STAT";
        this.typeIdx = "TP".equalsIgnoreCase(type) ? 1 : 0;
        this.statKey = meta.size() > 3 && !meta.get(3).isBlank() ? meta.get(3) : "STR";
        this.percent = meta.size() > 4 ? meta.get(4) : "0";
        this.duration = meta.size() > 5 ? meta.get(5) : "0";
        this.cooldown = meta.size() > 6 ? meta.get(6) : "0";
        this.bindings = bindings;
    }

    private void selectSection(int s)
    {
        applyFields();
        section = s;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        typeDd = statDd = null;
        rowY = buildNamedTabHeader(name, trAll(SECTION_KEYS), section, this::selectSection);

        if (section == 0)
            buildSettings();
        else
            buildBindings();

        btn(14, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(82, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("shrines"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void buildSettings()
    {
        tf(tr("gui.dmz_ragnarok.core.shrine.display_name"), displayName, v -> displayName = v);
        tip(tr("gui.dmz_ragnarok.core.shrine.display_name_tip"));

        label(tr("gui.dmz_ragnarok.core.shrine.type"), 14, rowY + 5);
        typeDd = dropdown(150, rowY, 132, options(TYPES), typeIdx);
        tooltip(150, rowY, 132, 11, tr("gui.dmz_ragnarok.core.shrine.type_tip"));
        rowY += ROW_H;

        if (typeIdx == 0)
        {
            label(tr("gui.dmz_ragnarok.core.shrine.stat"), 14, rowY + 5);
            int si = indexOf(STAT_KEYS, statKey);
            statDd = dropdown(150, rowY, 132, options(STAT_KEYS), Math.max(0, si));
            tooltip(150, rowY, 132, 11, tr("gui.dmz_ragnarok.core.shrine.stat_tip"));
            rowY += ROW_H;
        }

        tf(tr("gui.dmz_ragnarok.core.shrine.percent"), percent, v -> percent = v);
        tip(tr("gui.dmz_ragnarok.core.shrine.percent_tip"));
        tf(tr("gui.dmz_ragnarok.core.shrine.duration", fmt(duration)), duration, v -> duration = v);
        tip(tr("gui.dmz_ragnarok.core.shrine.duration_tip"));
        tf(tr("gui.dmz_ragnarok.core.shrine.cooldown", fmt(cooldown)), cooldown, v -> cooldown = v);
        tip(tr("gui.dmz_ragnarok.core.shrine.cooldown_tip"));
    }

    private void buildBindings()
    {
        int listTop = rowY;
        label("§7" + tr("gui.dmz_ragnarok.core.shrine.bound_blocks", bindings.size()), 14, listTop, 0xFFB0B0B0);
        listTop += 12;
        int rh = 13;
        // Reserve holds the bind hint drawn below the list (at UI_H - 36) clear of the last row.
        int cap = rowsThatFit(listTop, rh, 16);
        bindScroll = Math.max(0, Math.min(bindScroll, Math.max(0, bindings.size() - cap)));
        int end = Math.min(bindings.size(), bindScroll + cap);
        for (int i = bindScroll; i < end; i++)
        {
            final List<String> b = bindings.get(i);
            final String key = b.get(0);
            String display = b.size() > 1 ? b.get(1) : key;
            int ry = listTop + (i - bindScroll) * rh;
            label("§f" + display, 14, ry + 3, 0xFFFFFFFF);
            btn(rowControlRight() - 48, ry, 48, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.shrine.unbind"),
                    () -> EditorScreens.act("shrines", "unbind", name, key));
        }
        scrollList(14, uiWidth, listTop, rh, cap, bindings.size(), bindScroll,
                v -> { bindScroll = v; rebuildWidgets(); });

        label("§7" + tr("gui.dmz_ragnarok.core.shrine.bind_hint", name),
                14, UI_H - 36, 0xFF888888);
    }

    private void save()
    {
        applyFields();
        if (typeDd != null)
            typeIdx = typeDd.getIndex();
        if (statDd != null)
            statKey = STAT_KEYS[Math.max(0, Math.min(statDd.getIndex(), STAT_KEYS.length - 1))];
        List<String> args = new ArrayList<>();
        args.add(name);
        args.add(displayName == null ? "" : displayName);
        args.add(TYPES[Math.max(0, Math.min(typeIdx, TYPES.length - 1))]);
        args.add(statKey == null ? "STR" : statKey);
        args.add(percent.trim());
        args.add(duration.trim());
        args.add(cooldown.trim());
        EditorScreens.act("shrines", "save", args);
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row)
    {
        if (dropdown == typeDd)
        {
            applyFields();
            typeIdx = row;
            rebuildWidgets();
        }
        else if (dropdown == statDd)
        {
            statKey = STAT_KEYS[Math.max(0, Math.min(row, STAT_KEYS.length - 1))];
        }
    }

    // seconds string -> H:MM:SS for a label; "?" if unparseable
    private static String fmt(String secs)
    {
        try
        {
            return StringUtil.formatDuration(Long.parseLong(secs.trim()));
        }
        catch (NumberFormatException e)
        {
            return "?";
        }
    }

    private static int indexOf(String[] arr, String v)
    {
        for (int i = 0; i < arr.length; i++)
            if (arr[i].equalsIgnoreCase(v))
                return i;
        return -1;
    }
}
