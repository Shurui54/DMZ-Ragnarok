package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.zorb.ZOrbConfig;
import net.shurui.shuruisutilities.zorb.ZOrbItemEntry;
import net.shurui.shuruisutilities.zorb.ZOrbShape;
import net.shurui.shuruisutilities.zorb.ZOrbTimeOfDay;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Per-region Z orb editor (private). Edits the parent {@link NpcRegionEditScreen}'s {@code zorbs}
 * {@link ZOrbConfig} directly, in tabbed sections so every field fits: Spawn (shape and scheduling), Rewards (kind
 * weights and payout ranges), Pickup (order and despawn timers) and Items (the weighted item pool, laid out like the
 * airdrop loot rows with a weight column and a min/max count). A Global button opens {@link ZOrbGlobalsScreen} for
 * the server-wide settings. Nothing saves here: the region editor's Save sends the config to the server.
 */
public class NpcRegionZOrbScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.core.zorb.tab.spawn",
            "gui.dmz_ragnarok.core.zorb.tab.rewards",
            "gui.dmz_ragnarok.core.zorb.tab.pickup",
            "gui.dmz_ragnarok.core.zorb.tab.items"
    };

    private static List<String> ITEM_IDS;

    private final NpcRegionEditScreen parent;
    private final ZOrbConfig cfg;
    private int tab = 0;
    private int itemScroll = 0;

    // fields held as text and parsed on applyFields(), like the sibling editors.
    private String chainMin;
    private String chainMax;
    private String spacing;
    private String headingDrift;
    private String intervalMin;
    private String intervalMax;
    private String maxChains;
    private String minDist;
    private String maxDist;
    private String kindWeightTp;
    private String kindWeightZeni;
    private String itemChance;
    private String completionMult;
    private String tpMin;
    private String tpMax;
    private String zeniMin;
    private String zeniMax;
    private String claimLock;
    private String lifetime;
    private String progressTimeout;

    public NpcRegionZOrbScreen(NpcRegionEditScreen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.zorb.title", parent.regionName()), UI_W, UI_H, parent);
        this.parent = parent;
        this.cfg = parent.zorbs;
        pullText();
    }

    private void pullText()
    {
        chainMin = intStr(cfg.chainMin);
        chainMax = intStr(cfg.chainMax);
        spacing = dbl(cfg.spacing);
        headingDrift = dbl(cfg.headingDriftDeg);
        intervalMin = intStr(cfg.intervalMinSec);
        intervalMax = intStr(cfg.intervalMaxSec);
        maxChains = intStr(cfg.maxChains);
        minDist = intStr(cfg.minDist);
        maxDist = intStr(cfg.maxDist);
        kindWeightTp = intStr(cfg.kindWeightTp);
        kindWeightZeni = intStr(cfg.kindWeightZeni);
        itemChance = intStr(cfg.itemChancePct);
        completionMult = dbl(cfg.completionMultiplier);
        tpMin = intStr(cfg.tpMin);
        tpMax = intStr(cfg.tpMax);
        zeniMin = Long.toString(cfg.zeniMin);
        zeniMax = Long.toString(cfg.zeniMax);
        claimLock = intStr(cfg.claimLockSec);
        lifetime = intStr(cfg.lifetimeSec);
        progressTimeout = intStr(cfg.progressTimeoutSec);
    }

    // parse the held text back into the config; called before a tab switch, the Global button and Back.
    private void pushText()
    {
        applyFields();
        cfg.chainMin = parseI(chainMin, cfg.chainMin);
        cfg.chainMax = parseI(chainMax, cfg.chainMax);
        cfg.spacing = parseD(spacing, cfg.spacing);
        cfg.headingDriftDeg = parseD(headingDrift, cfg.headingDriftDeg);
        cfg.intervalMinSec = parseI(intervalMin, cfg.intervalMinSec);
        cfg.intervalMaxSec = parseI(intervalMax, cfg.intervalMaxSec);
        cfg.maxChains = parseI(maxChains, cfg.maxChains);
        cfg.minDist = parseI(minDist, cfg.minDist);
        cfg.maxDist = parseI(maxDist, cfg.maxDist);
        cfg.kindWeightTp = parseI(kindWeightTp, cfg.kindWeightTp);
        cfg.kindWeightZeni = parseI(kindWeightZeni, cfg.kindWeightZeni);
        cfg.itemChancePct = parseI(itemChance, cfg.itemChancePct);
        cfg.completionMultiplier = parseD(completionMult, cfg.completionMultiplier);
        cfg.tpMin = parseI(tpMin, cfg.tpMin);
        cfg.tpMax = parseI(tpMax, cfg.tpMax);
        cfg.zeniMin = parseL(zeniMin, cfg.zeniMin);
        cfg.zeniMax = parseL(zeniMax, cfg.zeniMax);
        cfg.claimLockSec = parseI(claimLock, cfg.claimLockSec);
        cfg.lifetimeSec = parseI(lifetime, cfg.lifetimeSec);
        cfg.progressTimeoutSec = parseI(progressTimeout, cfg.progressTimeoutSec);
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        rowY = buildNamedTabHeader(parent.regionName(), trSections(), tab, i -> {
            pushText();
            tab = i;
            rebuildWidgets();
        });

        switch (tab)
        {
            case 1 -> rewardsTab();
            case 2 -> pickupTab();
            case 3 -> itemsTab();
            default -> spawnTab();
        }

        int by = footerY();
        btn(UI_W / 2 - 154, by, 100, footerBtnHeight(),
                Component.literal("§b").append(Component.translatable("gui.dmz_ragnarok.core.zorb.global")), () -> {
                    pushText();
                    Minecraft.getInstance().setScreen(new ZOrbGlobalsScreen(parent, this));
                });
        btn(UI_W / 2 + 54, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> { pushText(); Minecraft.getInstance().setScreen(parent); });
    }

    private void spawnTab()
    {
        bf(tr("gui.dmz_ragnarok.core.zorb.enabled"), cfg.enabled, () -> cfg.enabled = !cfg.enabled);
        tip(tr("gui.dmz_ragnarok.core.zorb.enabled_tip"));
        df(tr("gui.dmz_ragnarok.core.zorb.shape"), shapeNames(), cfg.shape, v -> { if (!v.isBlank()) cfg.shape = v; });
        tip(tr("gui.dmz_ragnarok.core.zorb.shape_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.chain_min"), chainMin, v -> chainMin = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.chain_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.chain_max"), chainMax, v -> chainMax = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.chain_max_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.spacing"), spacing, v -> spacing = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.spacing_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.heading_drift"), headingDrift, v -> headingDrift = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.heading_drift_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.interval_min"), intervalMin, v -> intervalMin = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.interval_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.interval_max"), intervalMax, v -> intervalMax = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.interval_max_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.max_chains"), maxChains, v -> maxChains = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.max_chains_tip"));
        df(tr("gui.dmz_ragnarok.core.zorb.time_of_day"), timeOfDayNames(), cfg.timeOfDay,
                v -> { if (!v.isBlank()) cfg.timeOfDay = v; });
        tip(tr("gui.dmz_ragnarok.core.zorb.time_of_day_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.min_dist"), minDist, v -> minDist = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.min_dist_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.max_dist"), maxDist, v -> maxDist = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.max_dist_tip"));
    }

    private void rewardsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.weight_tp"), kindWeightTp, v -> kindWeightTp = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.weight_tp_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.weight_zeni"), kindWeightZeni, v -> kindWeightZeni = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.weight_zeni_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.item_chance"), itemChance, v -> itemChance = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.item_chance_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.completion_mult"), completionMult, v -> completionMult = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.completion_mult_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.tp_min"), tpMin, v -> tpMin = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.tp_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.tp_max"), tpMax, v -> tpMax = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.tp_max_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.zeni_min"), zeniMin, v -> zeniMin = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.zeni_min_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.zeni_max"), zeniMax, v -> zeniMax = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.zeni_max_tip"));
        bf(tr("gui.dmz_ragnarok.core.zorb.tp_falloff"), cfg.applyRegionTpFalloff,
                () -> cfg.applyRegionTpFalloff = !cfg.applyRegionTpFalloff);
        tip(tr("gui.dmz_ragnarok.core.zorb.tp_falloff_tip"));
        bf(tr("gui.dmz_ragnarok.core.zorb.share_party"), cfg.shareTpWithParty,
                () -> cfg.shareTpWithParty = !cfg.shareTpWithParty);
        tip(tr("gui.dmz_ragnarok.core.zorb.share_party_tip"));
    }

    private void pickupTab()
    {
        bf(tr("gui.dmz_ragnarok.core.zorb.strict_order"), cfg.strictOrder, () -> cfg.strictOrder = !cfg.strictOrder);
        tip(tr("gui.dmz_ragnarok.core.zorb.strict_order_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.claim_lock"), claimLock, v -> claimLock = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.claim_lock_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.lifetime"), lifetime, v -> lifetime = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.lifetime_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.progress_timeout"), progressTimeout, v -> progressTimeout = v);
        tip(tr("gui.dmz_ragnarok.core.zorb.progress_timeout_tip"));
        // The transient-orb warning: orbs never persist, so a chain in a chunk that unloads is lost with no reward.
        label("§7" + tr("gui.dmz_ragnarok.core.zorb.transient_note"), 14, rowY + 4, 0xFFB0B0B0);
        tooltip(12, rowY, 276, ROW_H, tr("gui.dmz_ragnarok.core.zorb.transient_tip"));
    }

    private void itemsTab()
    {
        label(tr("gui.dmz_ragnarok.core.zorb.pool_header"), 14, rowY);
        rowY += 10;
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_item"), 14, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_min"), 168, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.npc.col_max"), 196, rowY);
        label("§7" + tr("gui.dmz_ragnarok.core.zorb.col_weight"), 224, rowY);
        rowY += 10;

        List<ZOrbItemEntry> pool = cfg.itemPool;
        int listTop = rowY;
        int cap = rowsThatFit(listTop, ROW_H, GuiTheme.BUTTON_HEIGHT + 4);
        int count = pool.size();
        itemScroll = Math.max(0, Math.min(itemScroll, Math.max(0, count - cap)));
        int end = Math.min(count, itemScroll + cap);
        for (int i = itemScroll; i < end; i++)
            itemRow(pool.get(i), i);
        scrollList(12, uiWidth, listTop, ROW_H, cap, count, itemScroll, v -> { itemScroll = v; rebuildWidgets(); });

        int addY = listTop + cap * ROW_H + 2;
        btn(14, addY, 120, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.airdrop.add_item")), () -> {
            applyFields();
            pool.add(new ZOrbItemEntry());
            itemScroll = Math.max(0, pool.size() - cap);
            rebuildWidgets();
        });
        btn(140, addY, 148, GuiTheme.BUTTON_HEIGHT, Component.literal("§b").append(Component.translatable("gui.dmz_ragnarok.core.npc.held_item")), () -> {
            var player = Minecraft.getInstance().player;
            ItemStack held = player == null ? ItemStack.EMPTY : player.getMainHandItem();
            if (held.isEmpty()) return;
            applyFields();
            ZOrbItemEntry e = new ZOrbItemEntry();
            e.itemId = String.valueOf(ForgeRegistries.ITEMS.getKey(held.getItem()));
            e.minCount = e.maxCount = held.getCount();
            e.weight = 1;
            if (held.hasTag()) e.nbt = held.getTag().toString();
            pool.add(e);
            itemScroll = Math.max(0, pool.size() - cap);
            rebuildWidgets();
        });
        tooltip(140, addY, 148, 12, tr("gui.dmz_ragnarok.core.airdrop.held_item_tip"));
    }

    // one pool row: item id, count range, weight, remove. Same column layout as the airdrop loot row.
    private void itemRow(ZOrbItemEntry e, int index)
    {
        int y = rowY;
        dfAt(14, y, 148, itemIds(), e.itemId, v -> e.itemId = v.trim());
        rawField(166, y + 1, 24, intStr(e.minCount), v -> e.minCount = Math.max(0, parseI(v, e.minCount)));
        rawField(194, y + 1, 24, intStr(e.maxCount), v -> e.maxCount = Math.max(0, parseI(v, e.maxCount)));
        rawField(222, y + 1, 28, intStr(e.weight), v -> e.weight = Math.max(0, parseI(v, e.weight)));
        if (e.nbt != null && !e.nbt.isBlank())
            label("§d◆", 252, y + 2);
        btn(260, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("§cX"), () -> {
            applyFields();
            cfg.itemPool.remove(index);
            rebuildWidgets();
        });
        tooltip(14, y, 148, ROW_H, (e.nbt != null && !e.nbt.isBlank()
                ? "§d◆ " + tr("gui.dmz_ragnarok.core.npc.drop_nbt_note") + " "
                : "") + tr("gui.dmz_ragnarok.core.zorb.pool_item_tip"));
        rowY += ROW_H;
    }

    private String[] trSections()
    {
        String[] out = new String[SECTIONS.length];
        for (int i = 0; i < SECTIONS.length; i++)
            out[i] = tr(SECTIONS[i]);
        return out;
    }

    private static List<String> shapeNames()
    {
        List<String> l = new ArrayList<>();
        for (ZOrbShape s : ZOrbShape.values())
            l.add(s.name());
        return l;
    }

    private static List<String> timeOfDayNames()
    {
        List<String> l = new ArrayList<>();
        for (ZOrbTimeOfDay t : ZOrbTimeOfDay.values())
            l.add(t.name());
        return l;
    }

    private static long parseL(String s, long fallback)
    {
        try
        {
            return Long.parseLong(s.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
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
