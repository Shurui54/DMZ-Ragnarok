package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.corrupted.ShadowDragonDef;
import net.shurui.shuruisutilities.corrupted.network.PacketSaveShadowDragons;
import net.shurui.shuruisutilities.corrupted.network.PacketSetShadowDragonBounds;
import net.shurui.shuruisutilities.corrupted.region.Region;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.ragnarok.RgNpcPicker;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraftforge.registries.ForgeRegistries;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * The shadow-dragon boss/arena editor: one screen, one tab per fixed slot (1..7). Each tab shows that slot's
 * display name, entity type, the eight DMZ-style stats, an explicit spawn point and the arena bounds (WorldEdit
 * or manual). Built on SU's {@link FieldEditScreen} toolkit. Every tab fits on one non-scrolling page: SU's
 * SagaBaseScreen has no finishScrollBand / clampContentWidgets, so widgets added after beginScrollBand would not
 * be clipped, and a scrolling form would render edit boxes outside the band. Kept to a single page, like
 * {@link NpcRegionEditScreen} and {@link RegionEditScreen}.
 *
 * <p>Client-authoritative-nothing: the seven defs are edited locally and pushed back with a single
 * {@link PacketSaveShadowDragons}; the server clamps everything. The arena is set through a dedicated bounds
 * packet whose result re-seeds this screen's state.
 */
public class ShadowDragonEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // the seven slot defs, edited in place; index into this list is (slot - 1)
    private final List<ShadowDragonDef> defs;
    private int tab = 0;

    // pending arena/spawn corner text for the active tab, held so a "Set manually" reads them back
    private EditBox spawnX, spawnY, spawnZ;
    private EditBox minX, minY, minZ, maxX, maxY, maxZ;
    private String status = "";

    public ShadowDragonEditScreen(List<ShadowDragonDef> defs)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.shadowdragons"), UI_W, UI_H, null);
        this.defs = defs;
    }

    /** Open the editor from {@link net.shurui.shuruisutilities.corrupted.network.PacketOpenShadowDragonEditor}. */
    public static void open(List<CompoundTag> defTags)
    {
        Minecraft.getInstance().setScreen(new ShadowDragonEditScreen(decode(defTags)));
    }

    /** Re-seed an already-open editor after a bounds change, keeping the current tab. */
    public static void boundsResult(List<CompoundTag> defTags)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ShadowDragonEditScreen s)
        {
            List<ShadowDragonDef> fresh = decode(defTags);
            // Replace each slot's arena/spawn (the server-owned fields) while keeping any unsaved local stat edits
            // the admin has typed. Match by index so slot order can never desync.
            for (ShadowDragonDef nd : fresh)
            {
                ShadowDragonDef cur = s.defBySlot(nd.index);
                if (cur != null)
                {
                    cur.arena = nd.arena;
                    cur.spawnPos = nd.spawnPos;
                }
            }
            s.status = "§a" + tr("gui.dmz_ragnarok.core.shadow.status_arena_updated");
            s.rebuildWidgets();
        }
    }

    private static List<ShadowDragonDef> decode(List<CompoundTag> defTags)
    {
        List<ShadowDragonDef> out = new ArrayList<>();
        if (defTags != null)
            for (CompoundTag t : defTags)
                out.add(ShadowDragonDef.load(t));
        return out;
    }

    private ShadowDragonDef defBySlot(int slot)
    {
        for (ShadowDragonDef d : defs)
            if (d.index == slot)
                return d;
        return null;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        spawnX = spawnY = spawnZ = null;
        minX = minY = minZ = maxX = maxY = maxZ = null;

        if (defs.isEmpty())
        {
            // Defensive: the server always seeds seven slots, but never index into an empty list.
            label("§c" + tr("gui.dmz_ragnarok.core.shadow.no_slots"), 14, 40);
            btn(UI_W / 2 - 50, footerY(), 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                    EditorScreens::openAdminHub);
            return;
        }

        String[] sections = new String[defs.size()];
        for (int i = 0; i < defs.size(); i++)
            sections[i] = tr("gui.dmz_ragnarok.core.shadow.slot", defs.get(i).index);
        tab = Math.max(0, Math.min(tab, defs.size() - 1));

        // No centred "Boss Arenas" descriptor: it collided with the tab row and only restated the per-slot tabs.
        rowY = buildTabHeader(null, sections, tab, this::selectTab);

        ShadowDragonDef d = defs.get(tab);

        // Display name, then the dragon itself, then the model it changes to (full-width rows).
        tf(tr("gui.dmz_ragnarok.core.shadow.name"), d.name, v -> d.name = v);
        tip(tr("gui.dmz_ragnarok.core.shadow.name_tip"));
        // The dragon row used to be a free-text ENTITY id, which read "dmz_ragnarok:rgnpc_fighter" on every slot: the
        // chassis, never the dragon you are meant to fight. The chassis is not the interesting half. Every ragnarok
        // character is its own row in this dropdown (see RgNpcPicker), so the row names the DRAGON, and the pick is
        // split back into the entity type and the model the fighter wears at spawn. Plain entity ids are still in the
        // list, so a slot can still be pointed at something else entirely.
        df(tr("gui.dmz_ragnarok.core.shadow.dragon"),
                RgNpcPicker.options(entityIds()),
                RgNpcPicker.value(d.entityType, d.baseModel),
                v -> {
                    if (v.isBlank())
                        return;
                    d.entityType = RgNpcPicker.entityOf(v);
                    String model = RgNpcPicker.modelOf(v);
                    // A plain entity id carries no character, so keep the slot's dragon rather than blanking it: the
                    // model is ignored for a non-fighter entity anyway, and re-picking the fighter would otherwise
                    // come back as the generic default.
                    if (!model.isBlank())
                        d.baseModel = model;
                });
        tip(tr("gui.dmz_ragnarok.core.shadow.dragon_tip"));
        // The model the dragon swaps to at half health. Blank is a real answer (Eis Shenron has no transformed form),
        // which is what df's own "(none)" row sets, so no separate clear control is needed.
        df(tr("gui.dmz_ragnarok.core.shadow.transform"),
                RgNpcModels.ids().stream().sorted(String::compareToIgnoreCase).toList(),
                d.transformModel,
                v -> d.transformModel = v.trim());
        tip(tr("gui.dmz_ragnarok.core.shadow.transform_tip"));

        // Eight stats in two columns to keep the tab on one page. Left column x, right column x.
        int gridTop = rowY + 2;
        int rh = ROW_H;
        int col1Label = 14, col1Field = 78, fieldW = 54;
        int col2Label = 152, col2Field = 224;

        statCell(col1Label, col1Field, fieldW, gridTop,               tr("gui.dmz_ragnarok.core.shadow.health"), dbl(d.health), v -> d.health = parseD(v, d.health));
        statCell(col2Label, col2Field, fieldW, gridTop,               tr("gui.dmz_ragnarok.core.shadow.melee"), dbl(d.meleeDamage), v -> d.meleeDamage = parseD(v, d.meleeDamage));
        statCell(col1Label, col1Field, fieldW, gridTop + rh,          tr("gui.dmz_ragnarok.core.shadow.defense"), dbl(d.defense), v -> d.defense = parseD(v, d.defense));
        statCell(col2Label, col2Field, fieldW, gridTop + rh,          tr("gui.dmz_ragnarok.core.shadow.speed"), dbl(d.moveSpeed), v -> d.moveSpeed = parseD(v, d.moveSpeed));
        statCell(col1Label, col1Field, fieldW, gridTop + rh * 2,      tr("gui.dmz_ragnarok.core.shadow.scale"), dbl(d.scale), v -> d.scale = parseD(v, d.scale));
        statCell(col2Label, col2Field, fieldW, gridTop + rh * 2,      tr("gui.dmz_ragnarok.core.shadow.bp"), intStr(d.battlePower), v -> d.battlePower = parseI(v, d.battlePower));
        statCell(col1Label, col1Field, fieldW, gridTop + rh * 3,      tr("gui.dmz_ragnarok.core.shadow.ki_blast"), dbl(d.kiBlastDamage), v -> d.kiBlastDamage = parseD(v, d.kiBlastDamage));
        statCell(col2Label, col2Field, fieldW, gridTop + rh * 3,      tr("gui.dmz_ragnarok.core.shadow.ai_tier"), intStr(d.aiTier), v -> d.aiTier = parseI(v, d.aiTier));

        // Hover-help over the whole stat grid explaining the "0 = keep entity default" convention.
        tooltip(col1Label, gridTop, UI_W - col1Label - 12, rh * 4,
                tr("gui.dmz_ragnarok.core.shadow.stats_tip"));
        rowY = gridTop + rh * 4 + 4;

        // Explicit spawn point (nullable). Three coord boxes + a Set button.
        BlockPos sp = d.spawnPos;
        label("§7" + tr("gui.dmz_ragnarok.core.shadow.spawn"), 14, rowY + 2);
        spawnX = coordBox(48, sp == null ? "" : Integer.toString(sp.getX()), "x");
        spawnY = coordBox(96, sp == null ? "" : Integer.toString(sp.getY()), "y");
        spawnZ = coordBox(144, sp == null ? "" : Integer.toString(sp.getZ()), "z");
        btn(212, rowY, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.shadow.set_spawn"), () -> setSpawn(d));
        tip(tr("gui.dmz_ragnarok.core.shadow.spawn_tip"));
        rowY += ROW_H + 2;

        // Arena bounds: status line + Set via WE, then two manual-corner rows + Set manually.
        boundsRows(d);

        // Status line (last action feedback) + Save / Back.
        if (!status.isEmpty())
            label(status, 14, UI_H - 38, 0xFFA0FFA0);

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                EditorScreens::openAdminHub);
    }

    // one "Label [field]" stat cell at an explicit position (no rowY advance), read back on applyFields()
    private void statCell(int labelX, int fieldX, int fieldW, int y, String labelText, String value,
                          java.util.function.Consumer<String> setter)
    {
        label(labelText, labelX, y + 2);
        rawField(fieldX, y + 1, fieldW, value, setter);
    }

    private void boundsRows(ShadowDragonDef d)
    {
        Region arena = d.arena;
        String s = arena == null ? "§c" + tr("gui.dmz_ragnarok.core.shadow.not_set")
                : "§a" + arena.min().toShortString() + " → " + arena.max().toShortString();
        label("§7" + tr("gui.dmz_ragnarok.core.shadow.arena", s), 14, rowY + 2);
        btn(212, rowY, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.shadow.set_we"), () -> setViaWorldEdit(d));
        rowY += ROW_H;

        BlockPos mn = arena == null ? null : arena.min();
        BlockPos mx = arena == null ? null : arena.max();
        label("§7" + tr("gui.dmz_ragnarok.core.shadow.min"), 14, rowY + 2);
        minX = coordBox(42, mn == null ? "" : Integer.toString(mn.getX()), "x");
        minY = coordBox(90, mn == null ? "" : Integer.toString(mn.getY()), "y");
        minZ = coordBox(138, mn == null ? "" : Integer.toString(mn.getZ()), "z");
        rowY += ROW_H;
        label("§7" + tr("gui.dmz_ragnarok.core.shadow.max"), 14, rowY + 2);
        maxX = coordBox(42, mx == null ? "" : Integer.toString(mx.getX()), "x");
        maxY = coordBox(90, mx == null ? "" : Integer.toString(mx.getY()), "y");
        maxZ = coordBox(138, mx == null ? "" : Integer.toString(mx.getZ()), "z");
        btn(212, rowY - 1, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.shadow.set_manual"), () -> setArenaManual(d));
        tip(tr("gui.dmz_ragnarok.core.shadow.arena_tip"));
        rowY += ROW_H;
    }

    // a compact numeric coordinate box at the given row, pre-filled and hinted; NOT read back on applyFields()
    // (bounds/spawn are committed by their own buttons, not the field toolkit)
    private EditBox coordBox(int x, String value, String hint)
    {
        EditBox b = field(x, rowY + 1, 44, value);
        b.setHint(Component.literal("§8" + hint));
        return b;
    }

    private void selectTab(int t)
    {
        applyFields(); // keep the current tab's typed stat edits before switching
        tab = t;
        status = "";
        rebuildWidgets();
    }

    private void setSpawn(ShadowDragonDef d)
    {
        applyFields();
        Integer x = coordVal(spawnX), y = coordVal(spawnY), z = coordVal(spawnZ);
        if (x == null && y == null && z == null)
        {
            d.spawnPos = null; // all blank clears the explicit spawn
            status = "§7" + tr("gui.dmz_ragnarok.core.shadow.status_spawn_cleared");
        }
        else if (x == null || y == null || z == null)
        {
            status = "§c" + tr("gui.dmz_ragnarok.core.shadow.status_spawn_incomplete");
        }
        else
        {
            d.spawnPos = new BlockPos(x, y, z);
            status = "§a" + tr("gui.dmz_ragnarok.core.shadow.status_spawn_set");
        }
        rebuildWidgets();
    }

    private void setViaWorldEdit(ShadowDragonDef d)
    {
        applyFields();
        // Push the current stat edits first (so a WE arena set doesn't discard them), then ask the server to read
        // the selection. The server replies with a bounds-result that re-seeds this screen's arena/spawn.
        NetworkUtils.sendToServer(new PacketSaveShadowDragons(saveAll()));
        NetworkUtils.INSTANCE.sendToServer(new PacketSetShadowDragonBounds(d.index));
    }

    private void setArenaManual(ShadowDragonDef d)
    {
        applyFields();
        Integer a = coordVal(minX), b = coordVal(minY), c = coordVal(minZ);
        Integer e = coordVal(maxX), f = coordVal(maxY), g = coordVal(maxZ);
        if (a == null || b == null || c == null || e == null || f == null || g == null)
        {
            status = "§c" + tr("gui.dmz_ragnarok.core.shadow.status_arena_incomplete");
            rebuildWidgets();
            return;
        }
        ResourceKey<Level> dim = d.arena != null ? d.arena.dimension()
                : this.minecraft.player.level().dimension();
        d.arena = new Region(dim, new BlockPos(a, b, c), new BlockPos(e, f, g));
        status = "§a" + tr("gui.dmz_ragnarok.core.shadow.status_arena_set");
        rebuildWidgets();
    }

    private void save()
    {
        applyFields();
        NetworkUtils.sendToServer(new PacketSaveShadowDragons(saveAll()));
        status = "§a" + tr("gui.dmz_ragnarok.core.shadow.status_saved");
        rebuildWidgets();
    }

    // serialize all seven defs for a save packet (name / entity / stats; the server ignores arena/spawn here)
    private List<CompoundTag> saveAll()
    {
        List<CompoundTag> out = new ArrayList<>();
        for (ShadowDragonDef d : defs)
            out.add(d.save());
        return out;
    }

    /** Every registered entity id, built once per client session. Mirrors {@link NpcConfigEditScreen}'s own list. */
    private static List<String> entityIds()
    {
        if (ENTITY_IDS == null)
        {
            List<String> ids = new ArrayList<>();
            for (var key : ForgeRegistries.ENTITY_TYPES.getKeys())
                ids.add(key.toString());
            ids.sort(String::compareTo);
            ENTITY_IDS = ids;
        }
        return ENTITY_IDS;
    }

    private static List<String> ENTITY_IDS;

    private static Integer coordVal(EditBox box)
    {
        if (box == null)
            return null;
        String v = box.getValue().trim();
        if (v.isEmpty())
            return null;
        try
        {
            return Integer.valueOf(v);
        }
        catch (NumberFormatException ex)
        {
            return null;
        }
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
