package net.shurui.dev.shuruis_raid_bosses.mixin;

import com.dragonminez.client.util.TextUtil;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.BonusStats;
import com.dragonminez.common.stats.character.Stats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.client.gui.ZSoulAddButton;
import net.shurui.dev.shuruis_raid_bosses.dmz.DmzHooks;
import net.shurui.dev.shuruis_raid_bosses.dmz.ZSoulManager;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulTier;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Adds a Z-Soul "add" button left of each DMZ stat add-button, only for stats the player has a soul for.
 * Buys beyond-cap points at DMZ's own TP cost (server-validated via
 * {@link net.shurui.dev.shuruis_raid_bosses.network.ZSoulInvestC2S}).
 *
 * <p>Extends {@link Screen} only so the mixin can call the inherited protected {@code addRenderableWidget};
 * the fake constructor is never executed (Mixin strips it). Everything is guarded, so a DMZ change can only
 * make the extra buttons not appear, never break the screen.
 */
@Mixin(targets = "com.dragonminez.client.gui.character.CharacterStatsScreen", remap = false)
public abstract class ZSoulStatsScreenMixin extends Screen {

    private ZSoulStatsScreenMixin() {
        super(null); // never called; present only so extends Screen compiles
    }

    @Shadow private int tpMultiplier;
    @Shadow private StatsData statsData;

    /**
     * DMZ's scaled-panel height (from ScaledScreen, a grandparent of the target); its stat buttons lay out
     * relative to {@code getUiHeight()/2}. Reflective, not {@code @Shadow}, because the method is inherited
     * (not declared on the target), which mixin can't reliably bind.
     */
    private int zsoul$uiHeight() {
        try {
            java.lang.reflect.Method m = null;
            for (Class<?> c = getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("getUiHeight");
                } catch (NoSuchMethodException ignored) {
                }
            }
            if (m != null) {
                m.setAccessible(true);
                return (int) m.invoke(this);
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /**
     * Colour of a stat number that has a Z-Soul equipped. Same blue hue as {@link ZSoulAddButton} but much
     * lighter so it stays legible: DMZ draws these numbers with a BLACK border (border arg 0), so a light
     * fill is the safe direction.
     */
    private static final int ZSOUL_NUMBER_COLOR = 0xA8C8FF;
    /** DMZ's own colour for the six stat-value numbers in renderStatsInfo (used to spot them for recolouring). */
    private static final int DMZ_STAT_VALUE_COLOR = 14095410;
    /** DMZ stat rows, top-to-bottom, mapped to the Z-Soul that raises them (RES row = Stamina soul). */
    private static final ZStat[] ZSOUL_STAT_ORDER = {
            ZStat.STRENGTH, ZStat.STRIKE_POWER, ZStat.STAMINA, ZStat.VITALITY, ZStat.KI_POWER, ZStat.ENERGY
    };
    /** DMZ stat keys (for getMaxAllowedIncreaseForStat), same row order as {@link #ZSOUL_STAT_ORDER}. */
    private static final String[] ZSOUL_DMZ_KEYS = { "STR", "SKP", "RES", "VIT", "PWR", "ENE" };
    // DMZ lays out each stat's +button (in ScaledScreen panel space) at x=27, y=getUiHeight()/2 - 15 + off,
    // size 14x11. We copy that exactly so our button lands and clicks where DMZ's would. DMZ REMOVES a
    // stat's button once maxed, so we drop the Z-Soul button into that freed slot, which is exactly when
    // beyond-cap becomes usable. Row offsets (STR..ENE), matching ZSOUL_STAT_ORDER:
    private static final int STAT_BTN_X = 27;
    private static final int[] STAT_BTN_Y_OFFSET = { 11, 23, 35, 47, 59, 71 };
    private static final int STAT_BTN_W = 14;
    private static final int STAT_BTN_H = 11;

    /** The Z-Soul buttons we've added, so we can remove them when DMZ rebuilds its stat buttons (no duplicates). */
    private final java.util.List<ZSoulAddButton> zsoul$buttons = new java.util.ArrayList<>();

    /** Which stats currently have a soul worn (recomputed at the top of each renderStatsInfo). */
    private final Set<ZStat> zsoul$soulStats = EnumSet.noneOf(ZStat.class);
    /** Counts stat-value draws within one renderStatsInfo pass so the recolour knows which row it is on. */
    private int zsoul$valueIdx;

    // DMZ (re)builds its six stat +buttons in initStatButtons (from init() and refreshStatButtons() after
    // every point spent), omitting a button once that stat is maxed. Hooking the same method re-evaluates
    // our button on every rebuild and drops a Z-Soul "+" into a slot only when the base stat is maxed,
    // matching the invest gate and fixing the button vanishing after a point / never showing on a maxed
    // character. remap=false: this is DMZ's own method, not vanilla.
    @Inject(method = "initStatButtons", at = @At("TAIL"), remap = false, require = 0)
    private void zsoul$addButtons(CallbackInfo ci) {
        try {
            // Clear the previous pass's buttons so a rebuild leaves no duplicates.
            for (ZSoulAddButton b : zsoul$buttons) {
                this.removeWidget(b);
            }
            zsoul$buttons.clear();

            // Client-side gate: read the config toggle and the CLIENT'S copy of the server key state (ClientGate),
            // NOT ZSoulManager.enabled(), which asks the server-side key gate. On a keyed server that server-side
            // gate answers false on the client (the key mod is not present client-side), which would wrongly hide
            // the buttons the server does honour. ClientGate is fed by the login key sync.
            if (!(Config.ZSOUL_ENABLE.get() && ClientGate.key()) || this.statsData == null) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return;
            }
            EnumMap<ZStat, ZSoulTier> worn = ZSoulManager.wornTiers(mc.player);
            if (worn.isEmpty()) {
                return;
            }
            int uiHeight = zsoul$uiHeight();
            if (uiHeight <= 0) {
                return; // couldn't read DMZ's panel height, better no button than a misplaced one
            }
            int base = uiHeight / 2 - 15; // DMZ's own vertical anchor for the stat-button column
            for (int i = 0; i < ZSOUL_STAT_ORDER.length; i++) {
                ZStat stat = ZSOUL_STAT_ORDER[i];
                if (!worn.containsKey(stat)) {
                    continue; // no soul for this stat
                }
                // Show ONLY when the base stat is at its cap, the exact condition invest() gates on.
                // (getMaxAllowedIncreaseForStat<=0 was wrong: it also reads 0 when merely out of assignable
                // points below the cap, so the button appeared but the server rejected the invest.)
                if (this.statsData.getCurrentStatValue(ZSOUL_DMZ_KEYS[i]) < this.statsData.getConfiguredMaxValue()) {
                    continue;
                }
                int y = base + STAT_BTN_Y_OFFSET[i];
                final ZStat fStat = stat;
                ZSoulAddButton b = new ZSoulAddButton(STAT_BTN_X, y, STAT_BTN_W, STAT_BTN_H, stat,
                        () -> this.tpMultiplier, () -> zsoul$costTooltip(fStat));
                this.addRenderableWidget(b);
                zsoul$buttons.add(b);
            }
        } catch (Throwable ignored) {
            // never break DMZ's screen for our extra buttons
        }
    }

    /**
     * Slide the Z-Soul buttons with the panel. DMZ's stats panel ANIMATES: {@code updatePanelWidgetOffsets}
     * re-sets every stat button's X to {@code 27 + panelX} each pass, while ours were placed once in
     * {@code initStatButtons}, so they drifted out of the row and snapped back when the animation settled.
     * Applying the same offset to the same base X ({@link #STAT_BTN_X}, DMZ's own 27) fixes it.
     *
     * <p>TAIL so DMZ places its widgets first and ours land in the same frame. {@code require = 0} keeps a
     * DMZ rename from breaking the screen: worst case the buttons stop following, a cosmetic fault, not a crash.
     */
    @Inject(method = "updatePanelWidgetOffsets(II)V", at = @At("TAIL"), require = 0, remap = false)
    private void zsoul$followPanelSlide(int panelOffsetX, int rightOffsetX, CallbackInfo ci) {
        try {
            for (ZSoulAddButton b : zsoul$buttons) {
                if (b != null) {
                    b.setX(STAT_BTN_X + panelOffsetX);
                }
            }
        } catch (Throwable ignored) {
            // never break DMZ's panel animation over our extra buttons
        }
    }

    /** At the top of each stats-panel render, refresh which stats have a soul and reset the row counter. */
    @Inject(method = "renderStatsInfo", at = @At("HEAD"), require = 0)
    private void zsoul$beginStatsInfo(CallbackInfo ci) {
        zsoul$valueIdx = 0;
        zsoul$soulStats.clear();
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && Config.ZSOUL_ENABLE.get() && ClientGate.key()) {
                zsoul$soulStats.addAll(ZSoulManager.wornTiers(mc.player).keySet());
            }
        } catch (Throwable ignored) {
        }
    }

    // DMZ builds the displayed stat values from these six getters, so redirecting them to add our banked
    // beyond-cap points makes the Z-Soul bonus appear in the real number.
    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getStrength()I"), require = 0)
    private int zsoul$str(Stats stats) { return stats.getStrength() + zsoul$bonus(ZStat.STRENGTH); }

    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getStrikePower()I"), require = 0)
    private int zsoul$skp(Stats stats) { return stats.getStrikePower() + zsoul$bonus(ZStat.STRIKE_POWER); }

    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getResistance()I"), require = 0)
    private int zsoul$res(Stats stats) { return stats.getResistance() + zsoul$bonus(ZStat.STAMINA); }

    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getVitality()I"), require = 0)
    private int zsoul$vit(Stats stats) { return stats.getVitality() + zsoul$bonus(ZStat.VITALITY); }

    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getKiPower()I"), require = 0)
    private int zsoul$pwr(Stats stats) { return stats.getKiPower() + zsoul$bonus(ZStat.KI_POWER); }

    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE", target = "Lcom/dragonminez/common/stats/character/Stats;getEnergy()I"), require = 0)
    private int zsoul$ene(Stats stats) { return stats.getEnergy() + zsoul$bonus(ZStat.ENERGY); }

    /**
     * Recolour the six stat-value numbers when that stat has a Z-Soul worn. Redirects every stat-panel
     * {@code drawStringWithBorder} but only touches draws whose colour is DMZ's stat-value colour (labels
     * and headers pass through). Optional ({@code require = 0}).
     */
    @Redirect(method = "renderStatsInfo",
            at = @At(value = "INVOKE",
                    target = "Lcom/dragonminez/client/util/TextUtil;drawStringWithBorder(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"),
            slice = @Slice(from = @At(value = "CONSTANT", args = "stringValue=gui.dragonminez.character_stats.stats")),
            require = 0)
    private void zsoul$recolorStatValue(GuiGraphics g, Font font, Component text, int x, int y, int color, int border) {
        int useColor = color;
        if (color == DMZ_STAT_VALUE_COLOR) {
            int i = zsoul$valueIdx++;
            if (i >= 0 && i < ZSOUL_STAT_ORDER.length && zsoul$soulStats.contains(ZSOUL_STAT_ORDER[i])) {
                useColor = ZSOUL_NUMBER_COLOR;
            }
        }
        TextUtil.drawStringWithBorder(g, font, text, x, y, useColor, border);
    }

    /** The banked beyond-cap Z-Soul bonus currently applied to a stat, read from DMZ's synced BonusStats. */
    private int zsoul$bonus(ZStat stat) {
        try {
            StatsData sd = this.statsData;
            if (sd == null) {
                return 0;
            }
            BonusStats bonus = sd.getBonusStats();
            if (bonus == null) {
                return 0;
            }
            List<BonusStats.StatBonus> list = bonus.getBonuses(stat.bonusKey);
            if (list == null) {
                return 0;
            }
            double sum = 0;
            for (BonusStats.StatBonus b : list) {
                if (b != null && "+".equals(b.operation) && DmzHooks.ZSOUL_BONUS_SOURCE.equals(b.name)) {
                    sum += b.value;
                }
            }
            return (int) sum;
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Tooltip for a Z-Soul "+" button: the real TP cost of the next {@code tpMultiplier} beyond-cap points,
     * computed with the SAME formula invest() uses (DMZ's escalating {@code getSingleStatCost} past the
     * total-stat cap). DMZ's own on-screen cost reads 0 at the cap; this shows the correct number.
     */
    private Component zsoul$costTooltip(ZStat stat) {
        try {
            StatsData sd = this.statsData;
            if (sd == null) {
                return Component.translatable("gui.dmz_ragnarok.raid.zsoul.label");
            }
            int mult = Math.max(1, this.tpMultiplier);
            int total = sd.getStats().getTotalStats();
            int banked = zsoul$bonus(stat);
            long cost = 0;
            for (int i = 0; i < mult; i++) {
                int c = sd.getSingleStatCost(total + banked + i);
                if (c <= 0 || c == Integer.MAX_VALUE) {
                    return Component.translatable("gui.dmz_ragnarok.raid.zsoul.cost_off", mult);
                }
                cost += c;
            }
            return Component.translatable("gui.dmz_ragnarok.raid.zsoul.cost", mult, cost);
        } catch (Throwable t) {
            return Component.translatable("gui.dmz_ragnarok.raid.zsoul.label");
        }
    }
}
