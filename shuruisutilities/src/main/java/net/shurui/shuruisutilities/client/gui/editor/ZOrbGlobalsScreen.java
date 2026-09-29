package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.zorb.ZOrbConfig;
import net.shurui.shuruisutilities.zorb.ZOrbGlobals;
import net.shurui.shuruisutilities.zorb.ZOrbItemEntry;
import net.shurui.shuruisutilities.zorb.ZOrbRewardMath;
import net.shurui.shuruisutilities.zorb.ZOrbShape;
import net.shurui.shuruisutilities.zorb.network.PacketSaveZOrbConfig;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The server-wide Z orb editor (private): the ONE menu for the world spawner. Tabs Spawn / Chains / Rewards / Items
 * plus the older Limits / World. Reached from the NPC-region editor (carried on its Save), or region-independently by
 * {@code /zorbs edit} (the standalone constructor, which Saves the globals itself). Built on the shared GuiTheme
 * widgets (tabs, buttons, fields, dropdowns), no home-made chrome.
 */
public class ZOrbGlobalsScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] SECTIONS = {
            "gui.dmz_ragnarok.core.zorb.g.tab.spawn",
            "gui.dmz_ragnarok.core.zorb.g.tab.chains",
            "gui.dmz_ragnarok.core.zorb.g.tab.rewards",
            "gui.dmz_ragnarok.core.zorb.g.tab.items",
            "gui.dmz_ragnarok.core.zorb.g.tab.limits",
            "gui.dmz_ragnarok.core.zorb.g.tab.world"
    };

    private final NpcRegionEditScreen parent;
    private final NpcRegionZOrbScreen back;
    private final ZOrbGlobals g;
    private final boolean standalone;
    private int tab = 0;
    private int blScroll = 0;
    private int poolScroll = 0;

    /** Region-editor path: edits the parent's carried globals, saved with the region. */
    public ZOrbGlobalsScreen(NpcRegionEditScreen parent, NpcRegionZOrbScreen back)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.zorb.global"), UI_W, UI_H, back);
        this.parent = parent;
        this.back = back;
        this.g = parent.zorbsGlobals;
        this.standalone = false;
    }

    /** Region-independent path (/zorbs edit): edits and Saves the globals directly. */
    public ZOrbGlobalsScreen(ZOrbGlobals globals)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.zorb.global"), UI_W, UI_H, null);
        this.parent = null;
        this.back = null;
        this.g = globals == null ? new ZOrbGlobals() : globals;
        this.standalone = true;
    }

    private void pushText()
    {
        applyFields();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        rowY = buildNamedTabHeader(tr("gui.dmz_ragnarok.core.zorb.global"), trSections(), tab, i -> {
            pushText();
            tab = i;
            rebuildWidgets();
        });

        switch (tab)
        {
            case 0 -> spawnTab();
            case 1 -> chainsTab();
            case 2 -> rewardsTab();
            case 3 -> itemsTab();
            case 4 -> limitsTab();
            default -> worldTab();
        }

        int by = footerY();
        if (standalone)
        {
            btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"),
                    () -> { pushText(); g.sanitize();
                        NetworkUtils.sendToServer(new PacketSaveZOrbConfig("", new ZOrbConfig(), g));
                        Minecraft.getInstance().setScreen(null); });
            btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                    () -> Minecraft.getInstance().setScreen(null));
        }
        else
        {
            btn(UI_W / 2 - 50, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                    () -> { pushText(); Minecraft.getInstance().setScreen(back); });
        }
    }

    private void spawnTab()
    {
        bf(tr("gui.dmz_ragnarok.core.zorb.g.world_enabled"), g.worldSpawnerEnabled, () -> g.worldSpawnerEnabled = !g.worldSpawnerEnabled);
        tf(tr("gui.dmz_ragnarok.core.zorb.g.maxtrails_dim"), intStr(g.maxTrailsPerDimension), v -> g.maxTrailsPerDimension = parseI(v, g.maxTrailsPerDimension));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.maxtrails_server"), intStr(g.maxTrailsServer), v -> g.maxTrailsServer = parseI(v, g.maxTrailsServer));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.lifetime"), intStr(g.trailLifetimeSec), v -> g.trailLifetimeSec = parseI(v, g.trailLifetimeSec));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.tombstone"), intStr(g.tombstoneGraceSec), v -> g.tombstoneGraceSec = parseI(v, g.tombstoneGraceSec));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.separation"), dbl(g.trailMinSeparation), v -> g.trailMinSeparation = parseD(v, g.trailMinSeparation));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.seed_ring"), intStr(g.seedRingMin), v -> g.seedRingMin = parseI(v, g.seedRingMin));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.maint_ticks"), intStr(g.maintCycleTicks), v -> g.maintCycleTicks = parseI(v, g.maintCycleTicks));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.maint_per_cycle"), intStr(g.maintPlacementsPerCycle), v -> g.maintPlacementsPerCycle = parseI(v, g.maintPlacementsPerCycle));
        label(tr("gui.dmz_ragnarok.core.zorb.g.materialize_range"), 14, rowY + 2, 0xFF9AA0A6);
        rowY += ROW_H;
    }

    private void chainsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chain_min"), intStr(g.chainMin), v -> g.chainMin = parseI(v, g.chainMin));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chain_max"), intStr(g.chainMax), v -> g.chainMax = parseI(v, g.chainMax));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.spacing"), dbl(g.spacing), v -> g.spacing = parseD(v, g.spacing));
        df(tr("gui.dmz_ragnarok.core.zorb.g.shape"), shapeNames(), g.shape, v -> g.shape = (v == null || v.isBlank()) ? g.shape : v);
        tf(tr("gui.dmz_ragnarok.core.zorb.g.drift"), dbl(g.headingDriftDeg), v -> g.headingDriftDeg = parseD(v, g.headingDriftDeg));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.air_fraction"), dbl(g.airChainFraction), v -> g.airChainFraction = parseD(v, g.airChainFraction));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.air_min"), intStr(g.airHeightMin), v -> g.airHeightMin = parseI(v, g.airHeightMin));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.air_max"), intStr(g.airHeightMax), v -> g.airHeightMax = parseI(v, g.airHeightMax));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.ground_hover"), dbl(g.groundOrbHover), v -> g.groundOrbHover = parseD(v, g.groundOrbHover));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.clearance"), dbl(g.orbClearance), v -> g.orbClearance = parseD(v, g.orbClearance));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.max_lift"), intStr(g.maxClearanceLift), v -> g.maxClearanceLift = parseI(v, g.maxClearanceLift));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.weight_tp"), intStr(g.kindWeightTp), v -> g.kindWeightTp = parseI(v, g.kindWeightTp));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.weight_zeni"), intStr(g.kindWeightZeni), v -> g.kindWeightZeni = parseI(v, g.kindWeightZeni));
        bf(tr("gui.dmz_ragnarok.core.zorb.g.strict_order"), g.strictOrder, () -> g.strictOrder = !g.strictOrder);
        tf(tr("gui.dmz_ragnarok.core.zorb.g.progress_timeout"), intStr(g.progressTimeoutSec), v -> g.progressTimeoutSec = parseI(v, g.progressTimeoutSec));
    }

    private void rewardsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.g.tp_l1"), intStr(g.tpAtLevel1), v -> g.tpAtLevel1 = parseI(v, g.tpAtLevel1));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.tp_lmax"), intStr(g.tpAtMaxLevel), v -> g.tpAtMaxLevel = parseI(v, g.tpAtMaxLevel));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.zeni_l1"), Long.toString(g.zeniAtLevel1), v -> g.zeniAtLevel1 = parseL(v, g.zeniAtLevel1));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.zeni_lmax"), Long.toString(g.zeniAtMaxLevel), v -> g.zeniAtMaxLevel = parseL(v, g.zeniAtMaxLevel));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.scale_max"), intStr(g.scaleMaxLevel), v -> g.scaleMaxLevel = parseI(v, g.scaleMaxLevel));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.completion"), dbl(g.completionMultiplier), v -> g.completionMultiplier = parseD(v, g.completionMultiplier));
        String tpReadout = "TP  L1=" + ZOrbRewardMath.tpForLevel(g, 1) + "  L50000=" + ZOrbRewardMath.tpForLevel(g, 50000)
                + "  L" + g.scaleMaxLevel + "=" + ZOrbRewardMath.tpForLevel(g, g.scaleMaxLevel);
        String zReadout = "Zeni L1=" + ZOrbRewardMath.zeniForLevel(g, 1) + "  L50000=" + ZOrbRewardMath.zeniForLevel(g, 50000)
                + "  L" + g.scaleMaxLevel + "=" + ZOrbRewardMath.zeniForLevel(g, g.scaleMaxLevel);
        label(tpReadout, 14, rowY + 2, 0xFF9AA0A6);
        rowY += 11;
        label(zReadout, 14, rowY + 2, 0xFF9AA0A6);
        rowY += ROW_H;
    }

    private void itemsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.g.item_chance"), intStr(g.worldItemChancePct), v -> g.worldItemChancePct = parseI(v, g.worldItemChancePct));
        label(tr("gui.dmz_ragnarok.core.zorb.g.item_pool"), 14, rowY);
        rowY += 10;

        List<ZOrbItemEntry> pool = g.worldItemPool;
        int listTop = rowY;
        int cap = rowsThatFit(listTop, ROW_H, GuiTheme.BUTTON_HEIGHT + 4);
        int count = pool.size();
        poolScroll = Math.max(0, Math.min(poolScroll, Math.max(0, count - cap)));
        int endIdx = Math.min(count, poolScroll + cap);
        for (int i = poolScroll; i < endIdx; i++)
        {
            final int index = i;
            ZOrbItemEntry e = pool.get(i);
            int y = listTop + (i - poolScroll) * ROW_H;
            rawField(14, y + 1, 138, e.itemId, v -> e.itemId = v.trim());
            rawField(156, y + 1, 26, intStr(e.minCount), v -> e.minCount = parseI(v, e.minCount));
            rawField(186, y + 1, 26, intStr(e.maxCount), v -> e.maxCount = parseI(v, e.maxCount));
            rawField(216, y + 1, 32, intStr(e.weight), v -> e.weight = parseI(v, e.weight));
            btn(258, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("§cX"), () -> {
                applyFields();
                if (index >= 0 && index < g.worldItemPool.size())
                    g.worldItemPool.remove(index);
                rebuildWidgets();
            });
        }
        scrollList(12, uiWidth, listTop, ROW_H, cap, count, poolScroll, v -> { poolScroll = v; rebuildWidgets(); });

        int addY = listTop + cap * ROW_H + 2;
        btn(14, addY, 130, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.zorb.g.item_add")), () -> {
            applyFields();
            g.worldItemPool.add(new ZOrbItemEntry("minecraft:diamond", 1, 1, 1));
            poolScroll = Math.max(0, g.worldItemPool.size() - cap);
            rebuildWidgets();
        });
    }

    private void limitsTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chains_per_player"), intStr(g.maxChainsPerPlayer), v -> g.maxChainsPerPlayer = parseI(v, g.maxChainsPerPlayer));
        tip(tr("gui.dmz_ragnarok.core.zorb.g.chains_per_player_tip"));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chains_server"), intStr(g.maxChainsServer), v -> g.maxChainsServer = parseI(v, g.maxChainsServer));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chains_per_hour"), intStr(g.maxChainsPerHour), v -> g.maxChainsPerHour = parseI(v, g.maxChainsPerHour));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.tp_per_day"), intStr(g.maxTpPerDay), v -> g.maxTpPerDay = parseI(v, g.maxTpPerDay));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.zeni_per_day"), Long.toString(g.maxZeniPerDay), v -> g.maxZeniPerDay = parseL(v, g.maxZeniPerDay));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.item_orbs_per_day"), intStr(g.maxItemOrbsPerDay), v -> g.maxItemOrbsPerDay = parseI(v, g.maxItemOrbsPerDay));
        tf(tr("gui.dmz_ragnarok.core.zorb.g.afk_sec"), intStr(g.afkSec), v -> g.afkSec = parseI(v, g.afkSec));
    }

    private void worldTab()
    {
        tf(tr("gui.dmz_ragnarok.core.zorb.g.chime_sound"), g.chimeSound, v -> g.chimeSound = v.trim());
        tf(tr("gui.dmz_ragnarok.core.zorb.g.completion_sound"), g.completionSound, v -> g.completionSound = v.trim());

        label(tr("gui.dmz_ragnarok.core.zorb.g.open_dims"), 14, rowY);
        rowY += 10;
        dimList(g.openWorldDimensions);
    }

    private void dimList(List<String> dims)
    {
        int listTop = rowY;
        int cap = rowsThatFit(listTop, ROW_H, GuiTheme.BUTTON_HEIGHT + 4);
        int count = dims.size();
        blScroll = Math.max(0, Math.min(blScroll, Math.max(0, count - cap)));
        int endIdx = Math.min(count, blScroll + cap);
        for (int i = blScroll; i < endIdx; i++)
        {
            final int index = i;
            int y = listTop + (i - blScroll) * ROW_H;
            rawField(14, y + 1, 240, dims.get(i), v -> dims.set(index, v.trim()));
            btn(258, y, GuiTheme.ICON_BUTTON_SIZE, GuiTheme.ICON_BUTTON_SIZE, Component.literal("§cX"), () -> {
                applyFields();
                if (index >= 0 && index < dims.size())
                    dims.remove(index);
                rebuildWidgets();
            });
        }
        scrollList(12, uiWidth, listTop, ROW_H, cap, count, blScroll, v -> { blScroll = v; rebuildWidgets(); });

        int addY = listTop + cap * ROW_H + 2;
        btn(14, addY, 130, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.zorb.g.dim_add")), () -> {
            applyFields();
            dims.add("");
            blScroll = Math.max(0, dims.size() - cap);
            rebuildWidgets();
        });
    }

    private static List<String> shapeNames()
    {
        List<String> out = new ArrayList<>();
        for (ZOrbShape s : ZOrbShape.values())
            out.add(s.name());
        return out;
    }

    private String[] trSections()
    {
        String[] out = new String[SECTIONS.length];
        for (int i = 0; i < SECTIONS.length; i++)
            out[i] = tr(SECTIONS[i]);
        return out;
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

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
