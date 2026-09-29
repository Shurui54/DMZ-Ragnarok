package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.npcregion.CustomDrop;
import net.shurui.shuruisutilities.npcregion.NpcRegion;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

// airdrop screen of the NPC-region editor: this region's airdrop behaviour. scheduling controls up top drive a
// per-region drop timer. below: the airdrop boss spawns to guard a chest when a player closes within 25 blocks
// (default = region apex NPC at 2x stats, or a custom config), and the airdrop loot list overrides the global
// list for chests landing here (empty = global). changes sent with the region editor's Save.
public class NpcRegionAirdropScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final NpcRegionEditScreen parent;
    private int lootScroll = 0;

    private static List<String> ITEM_IDS;

    public NpcRegionAirdropScreen(NpcRegionEditScreen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.airdrop.title"), UI_W, UI_H, parent);
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        headerSubtitle = tr("gui.dmz_ragnarok.core.airdrop.subtitle");
        rowY = 26;

        bf(tr("gui.dmz_ragnarok.core.airdrop.enabled"), parent.airdropEnabled, () -> parent.airdropEnabled = !parent.airdropEnabled);
        tip(tr("gui.dmz_ragnarok.core.airdrop.enabled_tip"));
        tf(tr("gui.dmz_ragnarok.core.airdrop.min_interval"), intStr(parent.airdropMinIntervalMinutes),
                v -> parent.airdropMinIntervalMinutes = Math.max(1, parseI(v, parent.airdropMinIntervalMinutes)));
        tip(tr("gui.dmz_ragnarok.core.airdrop.min_interval_tip"));
        tf(tr("gui.dmz_ragnarok.core.airdrop.max_interval"), intStr(parent.airdropMaxIntervalMinutes),
                v -> parent.airdropMaxIntervalMinutes = Math.max(1, parseI(v, parent.airdropMaxIntervalMinutes)));
        tip(tr("gui.dmz_ragnarok.core.airdrop.max_interval_tip"));
        tf(tr("gui.dmz_ragnarok.core.airdrop.announcement"), parent.airdropAnnouncement, v -> parent.airdropAnnouncement = v);
        tip(tr("gui.dmz_ragnarok.core.airdrop.announcement_tip"));
        tf(tr("gui.dmz_ragnarok.core.airdrop.sound"), parent.airdropSound, v -> parent.airdropSound = v);
        tip(tr("gui.dmz_ragnarok.core.airdrop.sound_tip"));

        bf(tr("gui.dmz_ragnarok.core.airdrop.boss"), parent.crateBossEnabled, () -> parent.crateBossEnabled = !parent.crateBossEnabled);
        tip(tr("gui.dmz_ragnarok.core.airdrop.boss_tip"));

        boolean custom = parent.crateBoss != null;
        label(custom ? tr("gui.dmz_ragnarok.core.airdrop.guard_custom", npcLabel(parent.crateBoss))
                     : tr("gui.dmz_ragnarok.core.airdrop.guard_auto"), 14, rowY + 2);
        rowY += 13;

        btn(14, rowY, 130, GuiTheme.BUTTON_HEIGHT, Component.literal("§6").append(Component.translatable("gui.dmz_ragnarok.core.airdrop.customize")), () -> {
            applyFields();
            if (parent.crateBoss == null)
            {
                // Start from what the automatic rule would spawn, so admins tweak instead of rebuild.
                NpcSpawnConfig base = NpcRegion.defaultCrateBoss(parent.npcs());
                parent.crateBoss = base != null ? base : new NpcSpawnConfig();
            }
            Minecraft.getInstance().setScreen(new NpcConfigEditScreen(parent, parent.crateBoss, -1));
        });
        tooltip(14, rowY, 130, 13, tr("gui.dmz_ragnarok.core.airdrop.customize_tip"));
        btn(150, rowY, 130, GuiTheme.BUTTON_HEIGHT, Component.literal(custom ? "§c" : "§8").append(Component.translatable("gui.dmz_ragnarok.core.airdrop.use_apex")), () -> {
            parent.crateBoss = null;
            rebuildWidgets();
        });
        tooltip(150, rowY, 130, 13, tr("gui.dmz_ragnarok.core.airdrop.use_apex_tip"));
        rowY += 16;

        label(tr("gui.dmz_ragnarok.core.airdrop.loot_header"), 14, rowY);
        rowY += 10;
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_item"), 14, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_min"), 168, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_max"), 196, rowY);
        // "%" is the chance-column symbol, not translatable prose; left as a literal so it never routes through I18n
        label("§7%", 226, rowY);
        rowY += 10;

        int listTop = rowY;
        // Reserve holds the Add item / held-item button row (drawn at listTop + cap*ROW_H + 2) clear of the list.
        int cap = rowsThatFit(listTop, ROW_H, GuiTheme.BUTTON_HEIGHT + 4);
        int count = parent.crateLoot.size();
        lootScroll = Math.max(0, Math.min(lootScroll, Math.max(0, count - cap)));
        int end = Math.min(count, lootScroll + cap);
        for (int i = lootScroll; i < end; i++)
            lootRow(parent.crateLoot.get(i), i);
        scrollList(12, uiWidth, listTop, ROW_H, cap, count, lootScroll, v -> { lootScroll = v; rebuildWidgets(); });

        int addY = listTop + cap * ROW_H + 2;
        btn(14, addY, 120, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.airdrop.add_item")), () -> {
            applyFields();
            parent.crateLoot.add(new CustomDrop());
            lootScroll = Math.max(0, parent.crateLoot.size() - cap);
            rebuildWidgets();
        });
        // copy the main-hand item including ALL its NBT (enchants, names, modded data)
        btn(140, addY, 148, GuiTheme.BUTTON_HEIGHT, Component.literal("§b").append(Component.translatable("gui.dmz_ragnarok.core.npc.held_item")), () -> {
            var player = Minecraft.getInstance().player;
            ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
            if (held.isEmpty()) return;
            applyFields();
            CustomDrop d = new CustomDrop();
            d.itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem()));
            d.minCount = d.maxCount = held.getCount();
            d.chance = 100.0f;
            if (held.hasTag()) d.nbt = held.getTag().toString();
            parent.crateLoot.add(d);
            lootScroll = Math.max(0, parent.crateLoot.size() - cap);
            rebuildWidgets();
        });
        tooltip(140, addY, 148, 12, tr("gui.dmz_ragnarok.core.airdrop.held_item_tip"));

        int by = footerY();
        btn(UI_W / 2 - 50, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> { applyFields(); Minecraft.getInstance().setScreen(parent); });
    }

    // one loot row: item id, count range, % chance, remove button
    private void lootRow(CustomDrop d, int index)
    {
        int y = rowY;
        dfAt(14, y, 148, itemIds(), d.itemId, v -> d.itemId = v.trim());
        rawField(166, y + 1, 24, intStr(d.minCount), v -> d.minCount = Math.max(0, parseI(v, d.minCount)));
        rawField(194, y + 1, 24, intStr(d.maxCount), v -> d.maxCount = Math.max(0, parseI(v, d.maxCount)));
        rawField(222, y + 1, 30, fmt(d.chance), v -> d.chance = clampChance(parseFloat(v, d.chance)));
        if (d.nbt != null && !d.nbt.isBlank())
            label("§d◆", 248, y + 2);
        btn(256, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("§cX"), () -> {
            applyFields();
            parent.crateLoot.remove(index);
            rebuildWidgets();
        });
        tooltip(14, y, 148, ROW_H, (d.nbt != null && !d.nbt.isBlank()
                ? "§d◆ " + tr("gui.dmz_ragnarok.core.npc.drop_nbt_note") + " "
                : "") + tr("gui.dmz_ragnarok.core.airdrop.loot_item_tip"));
        rowY += ROW_H;
    }

    private static String npcLabel(NpcSpawnConfig c)
    {
        if (c.displayName != null && !c.displayName.isBlank())
            return c.displayName;
        String id = c.entityTypeId == null ? "" : c.entityTypeId;
        int i = id.indexOf(':');
        return i >= 0 ? id.substring(i + 1) : id;
    }

    private static float clampChance(float v)
    {
        return Math.max(0.0f, Math.min(100.0f, v));
    }

    private static float parseFloat(String s, float fallback)
    {
        try
        {
            return Float.parseFloat(s.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static String fmt(float v)
    {
        return v == Math.rint(v) ? Long.toString((long) v) : Float.toString(v);
    }

    private static List<String> itemIds()
    {
        if (ITEM_IDS == null)
        {
            List<String> ids = new ArrayList<>();
            for (var key : ForgeRegistries.ITEMS.getKeys())
                ids.add(key.toString());
            ids.sort(String::compareTo);
            ITEM_IDS = ids;
        }
        return ITEM_IDS;
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
