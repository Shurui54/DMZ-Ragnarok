package net.shurui.dev.sdu.compat;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.init.MainDamageTypes;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.AppearanceSyncS2C;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.BonusStats;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Stats;
import com.dragonminez.common.stats.extras.FormMasteries;
import com.dragonminez.common.stats.skills.Skills;
import com.dragonminez.common.util.TransformationsHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridge to DMZ's stat/form runtime for form-combat (auto dodge + damage-taken stat buffs). DMZ is a
 * mandatory dep so classes are referenced directly, but every access is guarded: a DMZ internals change
 * or an entity with no stats degrades to a no-op instead of crashing combat.
 */
public final class DmzForms {

    /** tags our form-combat stat buffs so we only add/remove our own */
    public static final String BONUS_SOURCE = "sdu_form_rage";
    /** tags racial-skill stat buffs (distinct from the form-combat buffs) */
    public static final String RACIAL_SOURCE = "sdu_racial";
    private static final String OP_ADD = "+";
    private static final String OP_MUL = "*";

    /** hit category, picks which dodge chance applies */
    public enum DamageCategory {
        PHYSICAL, MELEE_SKILL, ENERGY_SKILL, OTHER
    }

    private DmzForms() {
    }

    /** DMZ stats for a living entity, or {@code null} if it has none / DMZ is unavailable. */
    public static StatsData stats(LivingEntity entity) {
        if (entity == null) {
            return null;
        }
        try {
            return StatsProvider.<StatsData>get(StatsCapability.INSTANCE, entity).resolve().orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The names of the forms currently active on this character (base form and/or stack form). */
    public static List<String> activeFormNames(StatsData stats) {
        List<String> out = new ArrayList<>(2);
        if (stats == null) {
            return out;
        }
        try {
            Character c = stats.getCharacter();
            if (c != null && c.hasActiveForm()) {
                add(out, c.getActiveForm());
            }
            if (c != null && c.hasActiveStackForm()) {
                add(out, c.getActiveStackForm());
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static void add(List<String> list, String v) {
        if (v != null && !v.isBlank() && !list.contains(v)) {
            list.add(v);
        }
    }

    /** Classify an incoming hit so the right dodge chance is used. */
    public static DamageCategory classify(DamageSource source) {
        if (source == null) {
            return DamageCategory.OTHER;
        }
        try {
            if (MainDamageTypes.isKiblastDamage(source)) {
                return DamageCategory.ENERGY_SKILL;
            }
            if (MainDamageTypes.isStrikeAttackDamage(source)) {
                return DamageCategory.MELEE_SKILL;
            }
        } catch (Throwable ignored) {
        }
        // a basic physical hit has an attacker; environmental damage (fall/fire/...) doesn't
        return source.getEntity() instanceof LivingEntity ? DamageCategory.PHYSICAL : DamageCategory.OTHER;
    }

    /** Base (assigned) value of one of DMZ's six primary stats, or 0 if unavailable. */
    public static int baseStat(StatsData stats, String stat) {
        if (stats == null) {
            return 0;
        }
        try {
            Stats s = stats.getStats();
            return switch (stat) {
                case "STR" -> s.getStrength();
                case "SKP" -> s.getStrikePower();
                case "RES" -> s.getResistance();
                case "VIT" -> s.getVitality();
                case "PWR" -> s.getKiPower();
                case "ENE" -> s.getEnergy();
                default -> 0;
            };
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * DMZ {@code BonusStats} key for one of our stat keys. All 1:1 except RES: base is {@code getResistance()}
     * but DMZ 2.1.3 splits resistance across DEF (defense) and STM (stamina), so a bonus written to "RES" is
     * silently ignored by both. RES goes through {@code BonusStats}'s {@code *Split} methods (see
     * {@link #setMultiplier}/{@link #clearBonuses}); this helper handles only the non-RES 1:1 keys.
     */
    public static String bonusKey(String stat) {
        return "RES".equals(stat) ? "DEF" : stat;
    }

    /** Set (replacing any previous) a named additive buff for a stat to {@code amount}. */
    public static void setBonus(StatsData stats, String source, String stat, double amount) {
        if (stats == null) {
            return;
        }
        // Same reasoning as setMultiplier: a non-finite additive bonus is persisted and re-read every hit, so it
        // would poison the stat permanently. Refuse it.
        if (!Double.isFinite(amount)) {
            DmzNpc.LOGGER.warn("[{}] Refused a non-finite {} bonus ({}) for stat {}.",
                    DmzNpc.MODID, source, amount, stat);
            return;
        }
        try {
            BonusStats b = stats.getBonusStats();
            if ("RES".equals(stat)) {
                // RES splits across DEF + STM in DMZ 2.1.3; route through the split methods
                if (amount <= 0) {
                    b.removeBonusSplit("RES", source);
                } else {
                    b.addBonusSplit("RES", source, OP_ADD, amount, false);
                }
            } else {
                String key = bonusKey(stat);
                if (amount <= 0) {
                    b.removeBonus(key, source);
                } else {
                    b.addBonus(key, source, OP_ADD, amount);
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not set {} bonus for {}: {}", DmzNpc.MODID, source, stat, t.toString());
        }
    }

    /** Form-combat convenience: set our form-rage buff for a stat. */
    public static void setBonus(StatsData stats, String stat, double amount) {
        setBonus(stats, BONUS_SOURCE, stat, amount);
    }

    /**
     * Set a named multiplicative buff for a stat. DMZ's damage/defense getters scale the base then add
     * {@code BonusStats}, so an additive {@code "+"} buff is a flat add after scaling (negligible vs big
     * numbers). A {@code "*"} multiplier scales the stat's contribution ({@code base*mult - base}), so 1.5
     * is a real +50%. {@code multiplier} 1.0 = no change; {@code <= 1.0} removes the buff.
     */
    public static void setMultiplier(StatsData stats, String source, String stat, double multiplier) {
        if (stats == null) {
            return;
        }
        // A non-finite multiplier must never reach BonusStats: it is saved to NBT (rides the vault) and read on
        // every hit by getMeleeDamage/getKiDamage/getDefense, so one NaN there turns every attack into NaN damage
        // for good. Refuse it loudly instead of storing it.
        if (!Double.isFinite(multiplier)) {
            DmzNpc.LOGGER.warn("[{}] Refused a non-finite {} multiplier ({}) for stat {}.",
                    DmzNpc.MODID, source, multiplier, stat);
            return;
        }
        try {
            BonusStats b = stats.getBonusStats();
            if ("RES".equals(stat)) {
                // RES splits across DEF + STM in DMZ 2.1.3; split methods scale both derived stats.
                // applyMultipliers=false keeps it in the flat bucket, unamplified by DMZ's form multiplier.
                if (multiplier <= 1.0) {
                    b.removeBonusSplit("RES", source);
                } else {
                    b.addBonusSplit("RES", source, OP_MUL, multiplier, false);
                }
            } else {
                String key = bonusKey(stat);
                if (multiplier <= 1.0) {
                    b.removeBonus(key, source);
                } else {
                    b.addBonus(key, source, OP_MUL, multiplier);
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not set {} multiplier for {}: {}", DmzNpc.MODID, source, stat, t.toString());
        }
    }

    /** Form-combat convenience: set our form-rage stat buff as a multiplier. */
    public static void setMultiplier(StatsData stats, String stat, double multiplier) {
        setMultiplier(stats, BONUS_SOURCE, stat, multiplier);
    }

    /** Remove all buffs from a given source across every stat. */
    public static void clearBonuses(StatsData stats, String source) {
        if (stats == null) {
            return;
        }
        try {
            BonusStats b = stats.getBonusStats();
            for (String stat : net.shurui.dev.sdu.form.FormCombatData.STATS) {
                if ("RES".equals(stat)) {
                    b.removeBonusSplit("RES", source);
                } else {
                    b.removeBonus(bonusKey(stat), source);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Remove all of our form-combat buffs from a character (form ends / login). */
    public static void clearBonuses(StatsData stats) {
        clearBonuses(stats, BONUS_SOURCE);
    }

    /** The racial-skill id of the entity's current DMZ race, or {@code null} if unavailable. */
    public static String racialSkillId(StatsData stats) {
        if (stats == null) {
            return null;
        }
        try {
            String race = stats.getCharacter().getRace();
            if (race == null || race.isBlank()) {
                return null;
            }
            var chars = com.dragonminez.common.config.ConfigManager.getAllRaceCharacters();
            var cfg = chars == null ? null : chars.get(race);
            String racial = cfg == null ? null : cfg.getRacialSkill();
            return racial == null || racial.isBlank() ? null : racial.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Restore a percentage of max health/ki/stamina instantly. */
    public static void restore(Player player, StatsData stats, double healthPct, double kiPct, double staminaPct) {
        if (player == null || stats == null) {
            return;
        }
        try {
            if (healthPct > 0) {
                float max = player.getMaxHealth();
                player.setHealth(Math.min(max, player.getHealth() + max * (float) (healthPct / 100.0)));
            }
            var res = stats.getResources();
            if (kiPct > 0) {
                float max = stats.getMaxEnergy();
                res.setCurrentEnergy(Math.min(max, res.getCurrentEnergy() + max * (float) (kiPct / 100.0)));
            }
            if (staminaPct > 0) {
                float max = stats.getMaxStamina();
                res.setCurrentStamina(Math.min(max, res.getCurrentStamina() + max * (float) (staminaPct / 100.0)));
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Racial restore failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /** The player these stats belong to, or {@code null} if unavailable. */
    public static Player playerOf(StatsData stats) {
        try {
            return stats == null ? null : stats.getResources().getPlayer();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Unlock every DMZ form for {@code player}, max every form mastery, resync. Mirrors DMZ's
     * {@code FormsCommand}/{@code MasteryCommand}.
     *
     * <p>Unlock = form skill level: per group (all races' {@code getAllForms()} + shared
     * {@code getAllStackForms()}) resolve the skill via {@link TransformationsHelper#getSkillNameForType(String)}
     * (SDU's mixin reroutes custom types to their own skill) and set it to
     * {@code max(getMaxSkillLevel(skill), highest getUnlockOnSkillLevel in the group)}; resync
     * {@link ProgressionSyncS2C}. Mastery = {@code getFormMasteries()} + {@code getStackFormMasteries()}, each
     * form set to its {@code getMaxMastery()}; resync {@link StatsSyncS2C} + {@link AppearanceSyncS2C}.
     *
     * <p>All DMZ calls guarded; partial failure logs debug and still returns. True if stats were mutated,
     * false if the player has no DMZ stats.
     */
    public static boolean grantAllForms(ServerPlayer player) {
        StatsData stats = stats(player);
        if (stats == null) {
            return false;
        }
        try {
            Skills skills = stats.getSkills();
            Character character = stats.getCharacter();

            // 1) unlock: raise each group's skill so every form of that type is available
            for (Map<String, FormConfig> raceForms : ConfigManager.getAllForms().values()) {
                unlockGroups(skills, raceForms);
            }
            unlockGroups(skills, ConfigManager.getAllStackForms());

            // 2) max mastery for every per-race + stack-form group
            FormMasteries formMasteries = character.getFormMasteries();
            for (Map<String, FormConfig> raceForms : ConfigManager.getAllForms().values()) {
                maxMasteries(formMasteries, raceForms);
            }
            maxMasteries(character.getStackFormMasteries(), ConfigManager.getAllStackForms());

            // 3) resync like DMZ's FormsCommand (skills) + MasteryCommand (stats/appearance)
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new AppearanceSyncS2C(player), player);
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] grantAllForms failed for {}: {}",
                    DmzNpc.MODID, player.getName().getString(), t.toString());
            return false;
        }
    }

    /** Set each group's form skill high enough to unlock every form in the group. */
    private static void unlockGroups(Skills skills, Map<String, FormConfig> groups) {
        if (groups == null) {
            return;
        }
        for (FormConfig group : groups.values()) {
            if (group == null) {
                continue;
            }
            try {
                String skill = TransformationsHelper.getSkillNameForType(group.getFormType());
                if (skill == null || skill.isBlank()) {
                    continue;
                }
                int level = Math.max(0, skills.getMaxSkillLevel(skill));
                Map<String, FormConfig.FormData> forms = group.getForms();
                if (forms != null) {
                    for (FormConfig.FormData form : forms.values()) {
                        Integer unlock = form == null ? null : form.getUnlockOnSkillLevel();
                        if (unlock != null) {
                            level = Math.max(level, unlock);
                        }
                    }
                }
                if (level > 0) {
                    skills.setSkillLevel(skill, level);
                }
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not unlock form group '{}': {}",
                        DmzNpc.MODID, safeGroupName(group), t.toString());
            }
        }
    }

    /** Set every form in every group to its configured {@code maxMastery}. */
    private static void maxMasteries(FormMasteries masteries, Map<String, FormConfig> groups) {
        if (masteries == null || groups == null) {
            return;
        }
        for (Map.Entry<String, FormConfig> entry : groups.entrySet()) {
            String groupKey = entry.getKey();
            FormConfig group = entry.getValue();
            if (group == null || group.getForms() == null) {
                continue;
            }
            for (Map.Entry<String, FormConfig.FormData> form : group.getForms().entrySet()) {
                try {
                    Double max = form.getValue() == null ? null : form.getValue().getMaxMastery();
                    if (max == null) {
                        continue;
                    }
                    // BY NAME, not by the map key. DMZ stores mastery under FormMasteries.getKey(group, form NAME)
                    // and reads it back the same way, while the JSON map key is only an id. They are equal in a
                    // stock config, so this wrote to the right bucket by luck; rename a form in the SDU editor (which
                    // re-keys it to sanitize(name)) and grant-all would silently fill a bucket nothing ever reads.
                    String formName = form.getValue().getName();
                    masteries.setMastery(groupKey, formName == null || formName.isBlank() ? form.getKey() : formName,
                            max, max);
                } catch (Throwable t) {
                    DmzNpc.LOGGER.debug("[{}] Could not max mastery {}/{}: {}",
                            DmzNpc.MODID, groupKey, form.getKey(), t.toString());
                }
            }
        }
    }

    private static String safeGroupName(FormConfig group) {
        try {
            return group == null ? "?" : String.valueOf(group.getGroupName());
        } catch (Throwable t) {
            return "?";
        }
    }
}
