package net.shurui.dev.sdu.client.gui.race;

import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzPotions;
import net.shurui.dev.sdu.client.gui.DmzDropdown;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.race.RacialSkillData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Create/edit a race's <em>racial ability</em>: its id ({@code racialSkill}), the display name and
 * description shown in-game (client lang overlay, held on {@link RaceEditScreen}), and its
 * JSON-editable gameplay behaviour ({@link RacialSkillData}, stored separately by
 * {@code RacialSkillConfig}). Fields are split into sections chosen from a dropdown.
 *
 * <p>Unlike DMZ's hardcoded racial powers, the behaviour here is fully data-driven: a trigger plus
 * stat buffs, regen/restore, damage resistance and potion effects - enough to author DMZ-style
 * racials (e.g. a Zenkai-like "below X% HP → +stats").
 */
public class RacialAbilityScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTIONS =
            {"gui.dmz_ragnarok.npc.racialability.tab.info", "gui.dmz_ragnarok.npc.racialability.tab.trigger", "gui.dmz_ragnarok.npc.racialability.tab.stats", "gui.dmz_ragnarok.npc.racialability.tab.regen", "gui.dmz_ragnarok.npc.racialability.tab.defense", "gui.dmz_ragnarok.npc.racialability.tab.ocarina"};
    private static final RacialSkillData.Trigger[] TRIGGERS = RacialSkillData.Trigger.values();

    private final RaceEditScreen owner;
    private final RaceData race;
    private DmzDropdown triggerDropdown;
    private int section;

    public RacialAbilityScreen(RaceEditScreen owner, RaceData race) {
        super(Component.translatable("gui.dmz_ragnarok.npc.racialability.racial_ability"), UI_W, UI_H, owner);
        this.owner = owner;
        this.race = race;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        String[] sections = sections();
        if (section >= sections.length) {
            section = 0;
        }
        int belowTabs = buildTabHeader(tr("gui.dmz_ragnarok.npc.racialability.subtitle"), trAll(sections), section, this::selectSection);

        // The sections are wrapped in a scroll band because the Ocarina one does not come close to fitting: it is
        // three songs' worth of rows (radius, cooldown, score, then Mending, Valour and Discord with their own
        // levels, durations, per-level scaling and effect pickers) against a 250-tall panel, so its lower half was
        // simply drawn past the bottom edge and could not be reached. The band is opened for EVERY section rather
        // than just that one: the others fit today, but a screen that scrolls only sometimes is a worse thing to
        // discover than one that always does, and finishScrollBand draws no bar at all when the content fits.
        int contentTop = belowTabs;
        // derived from the footer row rather than a hand-picked constant, so the band can never run under the Done
        // button whatever the runtime canvas height works out to be.
        int contentBottom = footerY() - 4;
        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        buildSection();
        finishScrollBand(contentTop, contentBottom, rowY);

        // Commit on the way out; a rejected numeric entry keeps the admin on this screen with the banner shown.
        // Built AFTER the band is closed, so it is never clipped or hidden along with the scrolled content.
        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.racialability.done"), () -> {
            applyFields();
            if (hasFieldWarning()) {
                rebuildWidgets();
                return;
            }
            back();
        });
    }

    /**
     * The tabs to show. The Ocarina tab (always last) is a Ragnarok Key feature: against a server without it the
     * tab is hidden. The stored ocarina settings are untouched either way, so a keyed server still reads them.
     */
    private static String[] sections() {
        if (net.shurui.dev.sdu.api.ClientGate.feature("ocarina")) {
            return SECTIONS;
        }
        return java.util.Arrays.copyOf(SECTIONS, SECTIONS.length - 1);
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        // back to the top of the new tab: carrying the Ocarina tab's scroll into the two-row Info tab would open it
        // on blank space below its own content.
        scroll = 0;
        rebuildWidgets();
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row) {
        if (dropdown == triggerDropdown) {
            applyFields();
            race.racial.trigger = TRIGGERS[Math.max(0, Math.min(row, TRIGGERS.length - 1))];
            rebuildWidgets();
        }
    }

    private void buildSection() {
        RacialSkillData r = race.racial;
        switch (section) {
            case 0 -> {
                tf( tr("gui.dmz_ragnarok.npc.racialability.ability_id"), race.racialSkill, v -> race.racialSkill = net.shurui.dev.sdu.util.SduIds.sanitize(v));
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_internal_id_lowercas"));
                tf( tr("gui.dmz_ragnarok.npc.racialability.display_name"), owner.racialName, v -> owner.racialName = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_the_racial_ability_s"));
                tf( tr("gui.dmz_ragnarok.npc.racialability.description"), owner.racialDesc, v -> owner.racialDesc = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_what_this_racial_abi"));
            }
            case 1 -> {
                label( tr("gui.dmz_ragnarok.npc.racialability.trigger"), 14, rowY + 5);
                triggerDropdown = dropdown(150, rowY, 132, triggerOptions(), triggerIndex());
                rowY += ROW_H;
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_when_the_racial_s_ef"));
                pctField(tr("gui.dmz_ragnarok.npc.racialability.low_hp_on_low_hp"), r.lowHpThreshold, v -> r.lowHpThreshold = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_for_the_on_low_hp_tr"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.duration_s_timed"), r.durationSeconds, v -> r.durationSeconds = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_for_on_low_hp_on_hit"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.cooldown_s"), r.cooldownSeconds, v -> r.cooldownSeconds = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_cooldown_before_this"));
            }
            case 2 -> {
                label( tr("gui.dmz_ragnarok.npc.racialability.buff_each_stat_by_a_of_i"), 14, rowY);
                rowY += ROW_H;
                for (String stat : RacialSkillData.STATS) {
                    String friendly = statLabel(stat);
                    String lbl = tr("gui.dmz_ragnarok.npc.racialability.stat_boost", friendly, stat);
                    tf(lbl, dbl(r.stat(stat)), v -> r.setStat(stat, parseDoubleField(lbl, v, r.stat(stat))));
                    tip(tr("gui.dmz_ragnarok.npc.racialability.stat_boost_tip", friendly, stat));
                }
            }
            case 3 -> {
                label( tr("gui.dmz_ragnarok.npc.racialability.regen_per_second_while_a"), 14, rowY);
                rowY += ROW_H;
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.health_regen_s"), r.healthRegenPct, v -> r.healthRegenPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_healt"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ki_regen_s"), r.kiRegenPct, v -> r.kiRegenPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_ki_en"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.stamina_regen_s"), r.staminaRegenPct, v -> r.staminaRegenPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_stami"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.instant_heal"), r.instantHealPct, v -> r.instantHealPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_healt_2"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.instant_ki"), r.instantKiPct, v -> r.instantKiPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_ki_re"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.instant_stamina"), r.instantStaminaPct, v -> r.instantStaminaPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_max_stami_2"));
            }
            case 4 -> {
                pctField(tr("gui.dmz_ragnarok.npc.racialability.damage_resist"), r.damageResistPct, v -> r.damageResistPct = v);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_percent_of_incoming"));
                dfMulti( tr("gui.dmz_ragnarok.npc.racialability.potion_effects"), DmzPotions.effectIds(), potionIds(), this::setPotionsFromIds);
                tip( tr("gui.dmz_ragnarok.npc.racialability.t_vanilla_mod_effects"));
            }
            case 5 -> {
                net.shurui.dev.sdu.race.OcarinaData o = r.ocarina;
                bf(tr("gui.dmz_ragnarok.npc.racialability.ocarina_enabled"), o.enabled, () -> o.enabled = !o.enabled);
                tip(tr("gui.dmz_ragnarok.npc.racialability.t_ocarina_enabled"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_radius"), o.radius, v -> o.radius = v);
                tip(tr("gui.dmz_ragnarok.npc.racialability.t_ocarina_radius"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_cooldown"), o.cooldownSeconds,
                        v -> o.cooldownSeconds = v);
                pctField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_min_score"), o.minScorePercent,
                        v -> o.minScorePercent = v);
                tip(tr("gui.dmz_ragnarok.npc.racialability.t_ocarina_min_score"));

                label(tr("gui.dmz_ragnarok.npc.racialability.ocarina_mending"), 14, rowY);
                rowY += ROW_H;
                pctField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_heal"), o.healPercent, v -> o.healPercent = v);
                pctField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_heal_ki"), o.healKiPercent,
                        v -> o.healKiPercent = v);
                pctField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_heal_stamina"), o.healStaminaPercent,
                        v -> o.healStaminaPercent = v);

                label(tr("gui.dmz_ragnarok.npc.racialability.ocarina_valour"), 14, rowY);
                rowY += ROW_H;
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_valour_level"), o.valourLevel,
                        v -> o.valourLevel = (int) Math.round(v));
                tip(tr("gui.dmz_ragnarok.npc.racialability.t_ocarina_unlock_level"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_buff_seconds"), o.buffSeconds,
                        v -> o.buffSeconds = v);
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_buff_amp_level"), o.buffAmplifierPerLevel,
                        v -> o.buffAmplifierPerLevel = v);
                tip(tr("gui.dmz_ragnarok.npc.racialability.t_ocarina_amp_per_level"));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_buff_secs_level"), o.buffSecondsPerLevel,
                        v -> o.buffSecondsPerLevel = v);
                dfMulti(tr("gui.dmz_ragnarok.npc.racialability.ocarina_buff_effects"), DmzPotions.effectIds(),
                        idsOf(o.buffEffects), ids -> replaceEffects(o.buffEffects, ids));

                label(tr("gui.dmz_ragnarok.npc.racialability.ocarina_discord"), 14, rowY);
                rowY += ROW_H;
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_discord_level"), o.discordLevel,
                        v -> o.discordLevel = (int) Math.round(v));
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_debuff_seconds"), o.debuffSeconds,
                        v -> o.debuffSeconds = v);
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_debuff_amp_level"),
                        o.debuffAmplifierPerLevel, v -> o.debuffAmplifierPerLevel = v);
                nonNegField(tr("gui.dmz_ragnarok.npc.racialability.ocarina_debuff_secs_level"),
                        o.debuffSecondsPerLevel, v -> o.debuffSecondsPerLevel = v);
                dfMulti(tr("gui.dmz_ragnarok.npc.racialability.ocarina_debuff_effects"), DmzPotions.effectIds(),
                        idsOf(o.debuffEffects), ids -> replaceEffects(o.debuffEffects, ids));
            }
            default -> { }
        }
    }

    /** The effect-id half of each spec, for a dropdown that only picks ids. */
    private static List<String> idsOf(List<String> specs) {
        List<String> out = new ArrayList<>();
        for (String s : specs) {
            out.add(idPart(s));
        }
        return out;
    }

    /** Rebuild an effect list from picked ids, keeping any amplifier already written against that id. */
    private static void replaceEffects(List<String> specs, List<String> ids) {
        Map<String, String> ampById = new HashMap<>();
        for (String s : specs) {
            ampById.put(idPart(s), s);
        }
        specs.clear();
        for (String id : ids) {
            specs.add(ampById.getOrDefault(id, id + ":0"));
        }
    }

    /** The effect-id portion of each stored potion spec (drops any amplifier), for the dropdown. */
    private List<String> potionIds() {
        List<String> out = new ArrayList<>();
        for (String s : race.racial.potionEffects) {
            out.add(idPart(s));
        }
        return out;
    }

    /** Rebuild the potion list from the selected effect ids, preserving any amplifier already set. */
    private void setPotionsFromIds(List<String> ids) {
        Map<String, String> ampById = new HashMap<>();
        for (String s : race.racial.potionEffects) {
            ampById.put(idPart(s), ampPart(s));
        }
        race.racial.potionEffects.clear();
        for (String id : ids) {
            String amp = ampById.get(id);
            race.racial.potionEffects.add(amp == null || amp.isEmpty() ? id : id + ":" + amp);
        }
    }

    private static String idPart(String spec) {
        String[] p = spec.split(":");
        return p.length >= 2 ? p[0] + ":" + p[1] : spec;
    }

    private static String ampPart(String spec) {
        String[] p = spec.split(":");
        return p.length >= 3 ? p[2] : "";
    }

    private List<Component> triggerOptions() {
        List<Component> opts = new ArrayList<>();
        for (RacialSkillData.Trigger t : TRIGGERS) {
            opts.add(Component.literal(t.name()));
        }
        return opts;
    }

    private int triggerIndex() {
        for (int i = 0; i < TRIGGERS.length; i++) {
            if (TRIGGERS[i] == race.racial.trigger) {
                return i;
            }
        }
        return 0;
    }

    /**
     * A percentage row clamped to 0..100. The visible parser arms the banner on a non-number; a parseable
     * value that had to be clamped is reported too, so the 0-100 bound stated in the tips is not invisible.
     */
    private void pctField(String label, double current, java.util.function.DoubleConsumer setter) {
        tf(label, dbl(current), v -> {
            double parsed = parseDoubleField(label, v, current);
            double clamped = Math.max(0.0, Math.min(100.0, parsed));
            if (clamped != parsed) {
                warnClamped(label, dbl(parsed), dbl(clamped));
            }
            setter.accept(clamped);
        });
    }

    /** A non-negative decimal row (durations, cooldowns, regen/restore %); floors at 0 and reports the floor. */
    private void nonNegField(String label, double current, java.util.function.DoubleConsumer setter) {
        tf(label, dbl(current), v -> {
            double parsed = parseDoubleField(label, v, current);
            if (parsed < 0) {
                warnClamped(label, dbl(parsed), dbl(0));
                parsed = 0;
            }
            setter.accept(parsed);
        });
    }

    /**
     * Friendly name for a stat code (STR/SKP/RES/VIT/PWR/ENE), so rows read "Strength (STR) boost %"
     * instead of a bare code. Names mirror the RaceClassStats tips (RES = Resistance, VIT = Vitality).
     */
    private static String statLabel(String code) {
        return tr("gui.dmz_ragnarok.npc.racialability.stat_name." + code.toLowerCase(java.util.Locale.ROOT));
    }
}
