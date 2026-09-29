package net.shurui.shuruisutilities.prestige.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.prestige.PacketPrestigeAdminSave;
import net.shurui.shuruisutilities.prestige.PacketPrestigeRaceSave;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

// admin config screen for prestige, opened by sneak-right-clicking a prestige NPC (su.prestige.admin).
// two tabs:
//   Config = global prestige settings (max level, TP/cap bonus per level) + the clicked NPC's name/model
//   Race   = per-race required-prestige editor, one numeric field per DMZ race for the min prestige to pick
//            it (0 = unlocked). saved via PacketPrestigeRaceSave.
// switching tabs preserves the Race-tab map (read back before the switch, re-seeded on re-init) so flipping
// tabs doesn't lose edits.
public class PrestigeAdminScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private int maxPrestige;
    private int tpPerLevel;
    private int capPerLevel;
    private int entityId;
    private String npcName = "";
    private String model = "";

    /** 0 = Config, 1 = Race. */
    private int activeTab;

    /** The race-gate map currently being edited (lowercase race id -> required level, only levels > 0). */
    private final Map<String, Integer> raceRequired = new HashMap<>();
    /** Race ids currently marked operator only, toggled per row and saved with the gates. */
    private final java.util.Set<String> opOnlyRaces = new java.util.HashSet<>();
    /** Loaded DMZ race ids, resolved once from ConfigManager (client-safe). */
    private final List<String> races = new ArrayList<>();

    // Config-tab fields.
    private EditBox maxBox;
    private EditBox tpBox;
    private EditBox capBox;
    private EditBox nameBox;
    private EditBox modelBox;

    // Race-tab per-race fields (index-aligned with the visible race window).
    private final List<EditBox> raceBoxes = new ArrayList<>();
    private int raceScroll;
    /** Number of race rows visible at once, derived from the space above the Save button (set in initRaceTab). */
    private int raceRows;
    private static final int RACE_ROW_H = 16;

    public PrestigeAdminScreen(int maxPrestige, int tpPerLevel, int capPerLevel, int entityId, String npcName,
                               String model, Map<String, Integer> raceRequired,
                               java.util.Set<String> opOnlyRaces)
    {
        super(Component.literal("Prestige Config"), UI_W, UI_H, null);
        this.maxPrestige = maxPrestige;
        this.tpPerLevel = tpPerLevel;
        this.capPerLevel = capPerLevel;
        this.entityId = entityId;
        this.npcName = npcName == null ? "" : npcName;
        this.model = model == null ? "" : model;
        if (raceRequired != null)
            for (Map.Entry<String, Integer> e : raceRequired.entrySet())
                if (e.getKey() != null && e.getValue() != null && e.getValue() > 0)
                    this.raceRequired.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        if (opOnlyRaces != null)
            for (String race : opOnlyRaces)
                if (race != null && !race.isBlank())
                    this.opOnlyRaces.add(race.toLowerCase(Locale.ROOT));
    }

    public static void open(int maxPrestige, int tpPerLevel, int capPerLevel, int entityId, String npcName,
                            String model, Map<String, Integer> raceRequired, java.util.Set<String> opOnlyRaces)
    {
        Minecraft.getInstance().setScreen(new PrestigeAdminScreen(maxPrestige, tpPerLevel, capPerLevel, entityId,
                npcName, model, raceRequired, opOnlyRaces));
    }

    /** Load the DMZ race list once (client-safe static). Guarded so a DMZ API change can't blank the screen. */
    private void ensureRaces()
    {
        if (!races.isEmpty())
            return;
        try
        {
            for (String r : com.dragonminez.common.config.ConfigManager.getLoadedRaces())
                if (r != null && !r.isBlank())
                    races.add(r);
        }
        catch (Throwable ignored)
        {
        }
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        boolean perNpc = entityId >= 0;
        // No centred descriptor (it collided with the tab row and only restated what the Config/Race tabs show).
        rowY = buildTabHeader(null, new String[] { "Config", "Race" }, activeTab, this::selectTab);

        if (activeTab == 1)
            initRaceTab();
        else
            initConfigTab(perNpc);
    }

    /** Read back the visible fields and switch to {@code tab}, preserving the in-progress race edits. */
    private void selectTab(int tab)
    {
        if (tab == activeTab)
            return;
        if (activeTab == 1)
            readRaceBoxes();
        activeTab = tab;
        raceScroll = 0;
        rebuildWidgets();
    }

    private void initConfigTab(boolean perNpc)
    {
        int y = rowY + 4;
        label("§7Max prestige level:", 14, y + 3);
        maxBox = field(160, y, 84, String.valueOf(maxPrestige));
        maxBox.setMaxLength(4);
        y += 22;
        label("§7TP bonus % / level:", 14, y + 3);
        tpBox = field(160, y, 84, String.valueOf(tpPerLevel));
        tpBox.setMaxLength(7);
        y += 22;
        label("§7Stat cap % / level:", 14, y + 3);
        capBox = field(160, y, 84, String.valueOf(capPerLevel));
        capBox.setMaxLength(7);
        y += 22;
        label("§7NPC model (entity id):", 14, y + 3);
        modelBox = field(160, y, 84, model);
        modelBox.setMaxLength(64);
        y += 22;
        label(perNpc ? "§7NPC name:" : "§7Default name:", 14, y + 3);
        nameBox = field(160, y, 84, npcName);
        nameBox.setMaxLength(48);
        y += 20;
        label(perNpc ? "§8Changing the model respawns this NPC."
                : "§8Model = default type for new /prestige npc.", 14, y + 3);

        btn(14, UI_H - 42, 110, 16, Component.literal("§aSave"), this::save);
        btn(132, UI_H - 42, 114, 16, Component.literal("§bSpawn NPC here"), this::spawnHere);
        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::onClose);
    }

    private void save()
    {
        send(false);
    }

    /** Save the current fields and spawn a prestige NPC (of the model type) at the player's location. */
    private void spawnHere()
    {
        send(true);
    }

    private void send(boolean spawnHere)
    {
        int mx = parseInt(maxBox, maxPrestige);
        int tp = parseInt(tpBox, tpPerLevel);
        int cap = parseInt(capBox, capPerLevel);
        String name = nameBox == null ? npcName : nameBox.getValue().trim();
        String mdl = modelBox == null ? model : modelBox.getValue().trim();
        NetworkUtils.INSTANCE.sendToServer(new PacketPrestigeAdminSave(mx, tp, cap, entityId, name, mdl, spawnHere));
        onClose();
    }

    private void initRaceTab()
    {
        ensureRaces();
        raceBoxes.clear();
        int top = rowY + 4;
        label("§7Required prestige per race (0 = unlocked), OP = operators only:", 14, top - 10);

        // Window the list to the space above the Save button so rows never overlap it. The visible row
        // count (raceRows) is derived from that height, not a fixed constant, so the list genuinely scrolls
        // whenever there are more races than fit.
        // Reserve holds the "Save Race Gates" button (drawn at UI_H - 42) clear of the last race row.
        raceRows = rowsThatFit(top, RACE_ROW_H, 22);
        int total = races.size();
        int maxScroll = Math.max(0, total - raceRows);
        raceScroll = Math.max(0, Math.min(raceScroll, maxScroll));
        int end = Math.min(total, raceScroll + raceRows);

        // Each race's value field stops at the scrollbar-reserved column so it never sits under the bar (the
        // reviewer's "scroll bar behind prestige values" defect). Width derived so the field's right edge lands
        // at rowControlRight().
        // The operator toggle sits at the right of the row and the value field is narrowed to make room, rather
        // than the toggle going after the scrollbar column where the bar's grab is tested first and swallows it.
        int opW = 30;
        int raceFieldX = 180;
        int raceFieldW = Math.max(1, rowControlRight() - raceFieldX - opW - 4);
        int opX = raceFieldX + raceFieldW + 4;
        int y = top;
        for (int i = raceScroll; i < end; i++)
        {
            String race = races.get(i);
            String key = race.toLowerCase(Locale.ROOT);
            label("§f" + race, 14, y + 3);
            int cur = raceRequired.getOrDefault(key, 0);
            EditBox b = field(raceFieldX, y, raceFieldW, cur > 0 ? String.valueOf(cur) : "0");
            b.setMaxLength(4);
            raceBoxes.add(b);   // index-aligned with the [raceScroll, end) window
            boolean opOnly = opOnlyRaces.contains(key);
            // Read the visible fields back before rebuilding, or toggling one row discards every unsaved number.
            btn(opX, y, opW, 14, Component.literal(opOnly ? "§aOP" : "§8OP"), () ->
            {
                readRaceBoxes();
                if (!opOnlyRaces.remove(key))
                    opOnlyRaces.add(key);
                rebuildWidgets();
            });
            y += RACE_ROW_H;
        }

        // Scrollbar + wheel for the race window. Bar drawn at the standard reserved column (uiWidth minus the panel
        // inset) so it lines up past the fields. cap MUST be the actual visible row count so the base's overflow
        // test (count > cap) fires correctly.
        scrollList(14, uiWidth, top, RACE_ROW_H, raceRows, total, raceScroll,
                v -> { readRaceBoxes(); raceScroll = v; rebuildWidgets(); });

        btn(14, UI_H - 42, 232, 16, Component.literal("§aSave Race Gates"), this::saveRaces);
        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::onClose);
    }

    /**
     * Read the currently-visible race fields back into {@link #raceRequired} (0/blank removes the gate).
     * {@link #raceBoxes} is index-aligned with the {@code [raceScroll, raceScroll + size)} window, so this
     * must be called BEFORE {@code raceScroll} is changed (which the scroll setter does) to avoid losing an
     * unsaved edit when the list is scrolled or the tab switched.
     */
    private void readRaceBoxes()
    {
        for (int i = 0; i < raceBoxes.size(); i++)
        {
            int idx = raceScroll + i;
            if (idx >= races.size())
                break;
            String key = races.get(idx).toLowerCase(Locale.ROOT);
            int v = parseInt(raceBoxes.get(i), 0);
            if (v > 0)
                raceRequired.put(key, v);
            else
                raceRequired.remove(key);
        }
    }

    private void saveRaces()
    {
        readRaceBoxes();
        NetworkUtils.sendToServer(new PacketPrestigeRaceSave(new HashMap<>(raceRequired),
                new java.util.HashSet<>(opOnlyRaces)));
        onClose();
    }

    private static int parseInt(EditBox box, int fallback)
    {
        try
        {
            return Math.max(0, Integer.parseInt(box.getValue().trim()));
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
