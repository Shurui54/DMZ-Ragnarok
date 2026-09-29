package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.npcregion.CustomDrop;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

// per-NPC editor: tabbed customization (General/Stats/Ki/Spawn/Rewards/Drops) for one NpcSpawnConfig in a
// region. edits the config in place; the parent NpcRegionEditScreen owns the Y bounds + save round trip, and
// this returns to it via Back (nothing sent until its Save). ki/behaviour/scale/model apply to sdu's NPC entity
// via NBT keys; stat fields apply to any entity via vanilla attributes; TP/balance ranges go to the killer.
public class NpcConfigEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] TAB_KEYS = {
            "gui.dmz_ragnarok.core.npc.tab_general", "gui.dmz_ragnarok.core.npc.tab_stats",
            "gui.dmz_ragnarok.core.npc.tab_ki", "gui.dmz_ragnarok.core.npc.tab_spawn",
            "gui.dmz_ragnarok.core.npc.tab_rewards", "gui.dmz_ragnarok.core.npc.tab_drops"};
    // Display-label keys only. The selected index (ordinal) is persisted, not the string, and the ORDER MUST
    // still match sdu's DmzMoveset / NpcAiTier / behavior enums, so these arrays keep the same order and count.
    private static final String[] MOVESET_KEYS = {
            "gui.dmz_ragnarok.core.npc.moveset_none", "gui.dmz_ragnarok.core.npc.moveset_saibamen",
            "gui.dmz_ragnarok.core.npc.moveset_frieza_soldier", "gui.dmz_ragnarok.core.npc.moveset_cui",
            "gui.dmz_ragnarok.core.npc.moveset_dodoria", "gui.dmz_ragnarok.core.npc.moveset_zarbon",
            "gui.dmz_ragnarok.core.npc.moveset_ginyu", "gui.dmz_ragnarok.core.npc.moveset_frieza",
            "gui.dmz_ragnarok.core.npc.moveset_androids", "gui.dmz_ragnarok.core.npc.moveset_cell",
            "gui.dmz_ragnarok.core.npc.moveset_babidi", "gui.dmz_ragnarok.core.npc.moveset_buu"};
    private static final String[] AI_TIER_KEYS = {
            "gui.dmz_ragnarok.core.npc.tier_simple", "gui.dmz_ragnarok.core.npc.tier_tactical",
            "gui.dmz_ragnarok.core.npc.tier_advanced"};
    private static final String[] BEHAVIOR_KEYS = {
            "gui.dmz_ragnarok.core.npc.behavior_passive", "gui.dmz_ragnarok.core.npc.behavior_wander",
            "gui.dmz_ragnarok.core.npc.behavior_dialogue", "gui.dmz_ragnarok.core.npc.behavior_follower",
            "gui.dmz_ragnarok.core.npc.behavior_guard", "gui.dmz_ragnarok.core.npc.behavior_hostile",
            "gui.dmz_ragnarok.core.npc.behavior_fighter"};

    private static List<String> ENTITY_IDS;
    private static List<String> ITEM_IDS;

    private final NpcRegionEditScreen parent;
    private final NpcSpawnConfig config;
    private final int npcIndex;
    private int tab = 0;
    private int dropScroll = 0;

    private DmzDropdown movesetDropdown;
    private DmzDropdown aiTierDropdown;
    private DmzDropdown behaviorDropdown;

    public NpcConfigEditScreen(NpcRegionEditScreen parent, NpcSpawnConfig config, int npcIndex)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.npcregions"), UI_W, UI_H, null);
        this.parent = parent;
        this.config = config;
        this.npcIndex = npcIndex;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        movesetDropdown = aiTierDropdown = behaviorDropdown = null;

        String heading = npcIndex < 0 ? "§6" + tr("gui.dmz_ragnarok.core.npc.crate_boss")
                : "§e" + tr("gui.dmz_ragnarok.core.npc.npc_num", npcIndex + 1);
        rowY = buildNamedTabHeader(heading + " §7(" + config.entityTypeId + ")", trAll(TAB_KEYS), tab, this::selectSection);
        switch (tab)
        {
            case 0 -> generalTab();
            case 1 -> statsTab();
            case 2 -> kiTab();
            case 3 -> spawnTab();
            case 4 -> rewardsTab();
            case 5 -> dropsTab();
            default -> { }
        }

        int by = footerY();
        btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npc.done")), this::done);
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::done);
    }

    private void selectSection(int index)
    {
        if (tab != index)
        {
            applyFields();
            tab = index;
            rebuildWidgets();
        }
    }

    private void generalTab()
    {
        // The ragnarok characters are IN this list. They all share one entity type, so the registry alone shows
        // them as a single "rgnpc" row; RgNpcPicker adds each character and splits the pick back into the two
        // fields the config stores.
        df(tr("gui.dmz_ragnarok.core.npc.entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(config.entityTypeId, config.rgModelId),
                v -> {
                    if (v.isBlank()) return;
                    config.entityTypeId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    config.rgModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                });
        tip(tr("gui.dmz_ragnarok.core.npc.entity_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.display_name"), config.displayName, v -> config.displayName = v);
        tip(tr("gui.dmz_ragnarok.core.npc.display_name_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.model_id"), config.modelId, v -> config.modelId = v.trim());
        tip(tr("gui.dmz_ragnarok.core.npc.model_id_tip"));
    }

    private void statsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.npc.max_health"), fmt(config.maxHealth), v -> config.maxHealth = parseFloat(v, config.maxHealth));
        tip(tr("gui.dmz_ragnarok.core.npc.max_health_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.melee_damage"), fmt(config.attackDamage), v -> config.attackDamage = parseFloat(v, config.attackDamage));
        tip(tr("gui.dmz_ragnarok.core.npc.melee_damage_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.speed"), fmt(config.moveSpeed), v -> config.moveSpeed = parseFloat(v, config.moveSpeed));
        tip(tr("gui.dmz_ragnarok.core.npc.speed_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.defense"), fmt(config.defense), v -> config.defense = parseFloat(v, config.defense));
        tip(tr("gui.dmz_ragnarok.core.npc.defense_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.scale"), fmt(config.scale), v -> config.scale = parseFloat(v, config.scale));
        tip(tr("gui.dmz_ragnarok.core.npc.scale_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.aggro_range"), fmt(config.aggroRange), v -> config.aggroRange = Math.max(0.0, parseDouble(v, config.aggroRange)));
        tip(tr("gui.dmz_ragnarok.core.npc.aggro_range_tip"));
    }

    private void kiTab()
    {
        tf(tr("gui.dmz_ragnarok.core.npc.ki_power"), fmt(config.kiPower), v -> config.kiPower = parseFloat(v, config.kiPower));
        tip(tr("gui.dmz_ragnarok.core.npc.ki_power_tip"));
        label(tr("gui.dmz_ragnarok.core.npc.moveset"), 14, rowY + 5);
        movesetDropdown = dropdown(150, rowY, 132, options(trAll(MOVESET_KEYS)), clampIndex(config.moveset, MOVESET_KEYS));
        rowY += ROW_H;
        tip(tr("gui.dmz_ragnarok.core.npc.moveset_tip"));
        label(tr("gui.dmz_ragnarok.core.npc.ai_tier"), 14, rowY + 5);
        aiTierDropdown = dropdown(150, rowY, 132, options(trAll(AI_TIER_KEYS)), clampIndex(config.aiTier, AI_TIER_KEYS));
        rowY += ROW_H;
        tip(tr("gui.dmz_ragnarok.core.npc.ai_tier_tip"));
        label(tr("gui.dmz_ragnarok.core.npc.ki_attacks"), 14, rowY + 4);
        btn(150, rowY, 132, GuiTheme.BUTTON_HEIGHT, Component.literal(config.kiEnabled ? "§a[x] " : "§7[ ] ").append(Component.translatable("gui.dmz_ragnarok.core.npc.enabled")),
                () -> { config.kiEnabled = !config.kiEnabled; applyFields(); rebuildWidgets(); });
        rowY += ROW_H;
        tip(tr("gui.dmz_ragnarok.core.npc.ki_attacks_tip"));
        label(tr("gui.dmz_ragnarok.core.npc.behavior"), 14, rowY + 5);
        behaviorDropdown = dropdown(150, rowY, 132, options(trAll(BEHAVIOR_KEYS)), clampIndex(config.behavior, BEHAVIOR_KEYS));
        rowY += ROW_H;
        tip(tr("gui.dmz_ragnarok.core.npc.behavior_tip"));
    }

    private void spawnTab()
    {
        tf(tr("gui.dmz_ragnarok.core.npc.spawn_amount"), intStr(config.maxSpawns), v -> config.maxSpawns = Math.max(1, parseI(v, config.maxSpawns)));
        tip(tr("gui.dmz_ragnarok.core.npc.spawn_amount_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.cooldown"), intStr(config.cooldownTicks), v -> config.cooldownTicks = Math.max(1, parseI(v, config.cooldownTicks)));
        tip(tr("gui.dmz_ragnarok.core.npc.cooldown_tip"));
    }

    private void rewardsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.npc.tp_min"), intStr(config.tpMin), v -> config.tpMin = Math.max(0, parseI(v, config.tpMin)));
        tip(tr("gui.dmz_ragnarok.core.npc.tp_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.tp_max"), intStr(config.tpMax), v -> config.tpMax = Math.max(0, parseI(v, config.tpMax)));
        tip(tr("gui.dmz_ragnarok.core.npc.tp_max_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.bal_min"), intStr(config.balMin), v -> config.balMin = Math.max(0, parseI(v, config.balMin)));
        tip(tr("gui.dmz_ragnarok.core.npc.bal_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.bal_max"), intStr(config.balMax), v -> config.balMax = Math.max(0, parseI(v, config.balMax)));
        tip(tr("gui.dmz_ragnarok.core.npc.bal_max_tip"));
        tf(tr("gui.dmz_ragnarok.core.npc.kill_commands"), config.killCommands, v -> config.killCommands = v);
        tip(tr("gui.dmz_ragnarok.core.npc.kill_commands_tip"));
    }

    private void dropsTab()
    {
        bf(tr("gui.dmz_ragnarok.core.npc.vanilla_drops"), config.vanillaDrops, () -> config.vanillaDrops = !config.vanillaDrops);
        tip(tr("gui.dmz_ragnarok.core.npc.vanilla_drops_tip"));
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_item"), 14, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_min"), 168, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_max"), 196, rowY);
        // "%" is the chance-column symbol, not translatable prose; left as a literal so it never routes through I18n
        label("§7%", 226, rowY);
        rowY += 10;

        int listTop = rowY;
        // the Add drop / Held item button row sits at listTop + cap*ROW_H + 2, so reserve its gap + button height
        // (BUTTON_HEIGHT) plus a small margin so those buttons stay on the panel as the list grows.
        int cap = rowsThatFit(listTop, ROW_H, 18);
        int count = config.drops.size();
        dropScroll = Math.max(0, Math.min(dropScroll, Math.max(0, count - cap)));
        int end = Math.min(count, dropScroll + cap);
        for (int i = dropScroll; i < end; i++)
            dropRow(config.drops.get(i), i);
        scrollList(12, uiWidth, listTop, ROW_H, cap, count, dropScroll, v -> { dropScroll = v; rebuildWidgets(); });

        int addY = listTop + cap * ROW_H + 2;
        btn(14, addY, 120, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npc.add_drop")), () -> {
            applyFields();
            config.drops.add(new CustomDrop());
            dropScroll = Math.max(0, config.drops.size() - cap);
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
            config.drops.add(d);
            dropScroll = Math.max(0, config.drops.size() - cap);
            rebuildWidgets();
        });
        tooltip(140, addY, 148, 12, tr("gui.dmz_ragnarok.core.npc.held_item_tip"));
    }

    // one drop row: item id, count range, % chance, remove button
    private void dropRow(CustomDrop d, int index)
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
            config.drops.remove(index);
            rebuildWidgets();
        });
        tooltip(14, y, 148, ROW_H, (d.nbt != null && !d.nbt.isBlank()
                ? "§d◆ " + tr("gui.dmz_ragnarok.core.npc.drop_nbt_note") + " "
                : "") + tr("gui.dmz_ragnarok.core.npc.drop_item_tip"));
        rowY += ROW_H;
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row)
    {
        if (dropdown == movesetDropdown)
            config.moveset = row;
        else if (dropdown == aiTierDropdown)
            config.aiTier = row;
        else if (dropdown == behaviorDropdown)
            config.behavior = row;
    }

    // commit visible fields into the config + return to the region overview (nothing sent yet)
    private void done()
    {
        applyFields();
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    private static int clampIndex(int i, String[] arr)
    {
        return i < 0 || i >= arr.length ? 0 : i;
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

    private static double parseDouble(String s, double fallback)
    {
        try
        {
            return Double.parseDouble(s.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static String fmt(double v)
    {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }

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
}
