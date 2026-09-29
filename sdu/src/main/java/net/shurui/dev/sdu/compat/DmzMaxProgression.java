package net.shurui.dev.sdu.compat;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.AppearanceSyncS2C;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;
import com.dragonminez.common.stats.skills.Skill;
import com.dragonminez.common.stats.skills.Skills;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.StrikeAttackData;
import com.dragonminez.common.stats.techniques.TechniqueData;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Admin one-shot: max a player's whole DMZ progression (techniques, stats, forms, skills) and resync. Backs
 * {@link net.shurui.dev.sdu.item.MaxProgressionItem}. Each system runs in its own guarded block so one failing
 * (or a DMZ internals change) never aborts the others. Direct DMZ refs (mandatory dep), all wrapped.
 *
 * <p>Numbers verified against dragonminez-2.1.3:
 * <ul>
 *   <li>Techniques: {@code TechniqueData.experience} is a plain int with no cap; we unlock every predefined
 *       ki + strike technique and write {@link #TECHNIQUE_EXPERIENCE} to each.</li>
 *   <li>Stats: the six DMZ stats are Minecraft attribute base values. {@code Stats.setStat} runs through
 *       {@code clampStatValue}: floor 0, and an upper cap ONLY when server config
 *       {@code gameplay.maxLevelValueInsteadOfStats} is false (default true = uncapped), in which case the cap
 *       is {@code gameplay.maxValue} (default 10000, floor 1000). Writing {@link #STAT_TARGET} therefore clamps
 *       SILENTLY to that cap on a capped server; we read back the written value so callers report the real result.</li>
 *   <li>Forms: delegated to {@link DmzForms#grantAllForms(ServerPlayer)}.</li>
 *   <li>Skills: {@code Skill.setLevel} clamps to that skill's own {@code maxLevel} (costs-array size, capped at
 *       50, or 30 for potentialunlock). No separate per-skill "mastery" field. We set every configured skill to
 *       its real max; 100 is unreachable and would clamp anyway.</li>
 * </ul>
 */
public final class DmzMaxProgression {

    /** Experience written to every unlocked technique. Plain int, no DMZ cap. */
    public static final int TECHNIQUE_EXPERIENCE = 1_000_000;
    /** Requested stat value. DMZ clamps this to the server stat cap when that cap is enabled. */
    public static final int STAT_TARGET = 100_000;
    /** Ceiling passed to skill setters; each skill clamps to its own (smaller) max. */
    private static final int SKILL_LEVEL_REQUEST = 100;

    private static final String[] STATS = {"STR", "SKP", "RES", "VIT", "PWR", "ENE"};

    private DmzMaxProgression() {
    }

    /** Outcome of {@link #maxAll}: which systems applied, plus figures for player feedback. */
    public static final class Result {
        public boolean dmzAvailable;
        public boolean techniquesApplied;
        public int techniqueCount;
        public boolean statsApplied;
        /** Value actually stored per stat after DMZ's clamp (equals {@link #STAT_TARGET} unless capped). */
        public int effectiveStatValue;
        public boolean statsCapped;
        public boolean formsApplied;
        public boolean skillsApplied;
        public int skillCount;
        /** True when health, ki or stamina was actually topped up (false if the player was already full). */
        public boolean healed;

        /** True if at least one system mutated player state. */
        public boolean anyApplied() {
            return techniquesApplied || statsApplied || formsApplied || skillsApplied || healed;
        }
    }

    /**
     * Max every DMZ system for {@code player}, then resync. Never throws. If the player has no DMZ stats the
     * returned result has {@code dmzAvailable=false} and nothing is touched.
     */
    public static Result maxAll(ServerPlayer player) {
        Result r = new Result();
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return r; // dmzAvailable stays false
        }
        r.dmzAvailable = true;

        // 1) TECHNIQUES: unlock every predefined ki + strike technique, each at max experience.
        try {
            r.techniqueCount = maxTechniques(stats);
            r.techniquesApplied = r.techniqueCount > 0;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] maxAll: techniques step failed: {}", DmzNpc.MODID, t.toString());
        }

        // 2) STATS: set the six primary stats; read back to detect the server's silent clamp.
        try {
            r.effectiveStatValue = maxStats(stats);
            r.statsApplied = true;
            r.statsCapped = r.effectiveStatValue < STAT_TARGET;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] maxAll: stats step failed: {}", DmzNpc.MODID, t.toString());
        }

        // 3) SKILLS: set every configured skill to its own max (no separate mastery field exists).
        try {
            r.skillCount = maxSkills(stats);
            r.skillsApplied = r.skillCount > 0;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] maxAll: skills step failed: {}", DmzNpc.MODID, t.toString());
        }

        // 4) FORMS: reuse the existing guarded form-unlock + mastery path (self-contained, self-syncing).
        try {
            r.formsApplied = DmzForms.grantAllForms(player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] maxAll: forms step failed: {}", DmzNpc.MODID, t.toString());
        }

        // 5) TOP THE PLAYER UP, like a senzu. Maxing stats raises the MAXIMA without touching current pools, so
        // without this you'd stand there with huge maxima and whatever ki you had, reading as the item failing.
        // Last on purpose: must run after the stats step or it fills the old, smaller bars.
        try {
            r.healed = fullHeal(player, stats);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] maxAll: heal step failed: {}", DmzNpc.MODID, t.toString());
        }

        // Final resync so a partial-failure path still refreshes the client HUD. ProgressionSyncS2C carries
        // Stats + Skills + Techniques; StatsSyncS2C the stat capability; AppearanceSyncS2C the Character (forms
        // + mastery). grantAllForms already sent these; a second send is harmless and covers the early-return case.
        resync(player);
        return r;
    }

    /**
     * Health, ki and stamina all the way up, like a senzu bean's FULL kind. Never lowers anything: each pool is
     * written only when below its maximum, so it can never take from a player already better off.
     *
     * <p>Against DragonMineZ directly, not SU's senzu code: that belongs to the Senzu MODULE, which the keyless
     * and limited tiers tear down, and this item is admin content in a different source tree; reaching across
     * would tie an sdu item to whether an SU module happens to be registered.
     *
     * @return true when anything was actually raised
     */
    private static boolean fullHeal(ServerPlayer player, StatsData stats) {
        boolean any = false;

        // Vanilla health needs no resync: it syncs on its own.
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
            any = true;
        }

        var resources = stats.getResources();
        if (resources == null) {
            return any;
        }
        if (resources.getCurrentEnergy() < stats.getMaxEnergy()) {
            resources.setCurrentEnergy(stats.getMaxEnergy());
            any = true;
        }
        if (resources.getCurrentStamina() < stats.getMaxStamina()) {
            resources.setCurrentStamina(stats.getMaxStamina());
            any = true;
        }
        return any;
    }

    /** Unlock every predefined technique with {@link #TECHNIQUE_EXPERIENCE}. Returns how many were unlocked. */
    private static int maxTechniques(StatsData stats) {
        Map<String, TechniqueData> protos = new LinkedHashMap<>();
        if (PredefinedTechniques.REGISTRY != null) {
            for (Map.Entry<String, KiAttackData> e : PredefinedTechniques.REGISTRY.entrySet()) {
                KiAttackData k = new KiAttackData();
                k.load(e.getValue().save());
                k.setExperience(TECHNIQUE_EXPERIENCE);
                protos.put(e.getKey(), k);
            }
        }
        if (PredefinedTechniques.STRIKE_REGISTRY != null) {
            for (Map.Entry<String, StrikeAttackData> e : PredefinedTechniques.STRIKE_REGISTRY.entrySet()) {
                StrikeAttackData s = new StrikeAttackData();
                s.load(e.getValue().save());
                s.setExperience(TECHNIQUE_EXPERIENCE);
                protos.put(e.getKey(), s);
            }
        }
        int count = 0;
        for (TechniqueData proto : protos.values()) {
            try {
                stats.getTechniques().unlockTechnique(proto);
                count++;
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not unlock technique '{}': {}",
                        DmzNpc.MODID, safeId(proto), t.toString());
            }
        }
        return count;
    }

    /** Set the six primary stats to {@link #STAT_TARGET}; returns the value DMZ actually stored (post-clamp). */
    private static int maxStats(StatsData stats) {
        Stats s = stats.getStats();
        for (String key : STATS) {
            try {
                s.setStat(key, STAT_TARGET);
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not set stat '{}': {}", DmzNpc.MODID, key, t.toString());
            }
        }
        // Read STR back as representative: all six share the same clamp path.
        try {
            return s.getStrength();
        } catch (Throwable t) {
            return STAT_TARGET;
        }
    }

    /** Set every configured skill to its own max level. Returns how many skills were set. */
    private static int maxSkills(StatsData stats) {
        Skills skills = stats.getSkills();
        int count = 0;
        var cfg = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
        if (cfg == null || cfg.getSkills() == null) {
            return 0;
        }
        for (String id : cfg.getSkills().keySet()) {
            try {
                // setSkillLevel registers the skill (with its correct max) if absent, then clamps to that max.
                skills.setSkillLevel(id, SKILL_LEVEL_REQUEST);
                Skill sk = skills.getSkill(id);
                if (sk != null) {
                    sk.setLevel(sk.getMaxLevel()); // guarantee true max even if the request was below it
                }
                count++;
            } catch (Throwable t) {
                DmzNpc.LOGGER.debug("[{}] Could not max skill '{}': {}", DmzNpc.MODID, id, t.toString());
            }
        }
        return count;
    }

    /** Push progression + stats + appearance to the client so the HUD reflects the changes without a relog. */
    private static void resync(ServerPlayer player) {
        try {
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new AppearanceSyncS2C(player), player);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] maxAll resync failed for {}: {}",
                    DmzNpc.MODID, player.getName().getString(), t.toString());
        }
    }

    private static String safeId(TechniqueData d) {
        try {
            return d == null ? "?" : String.valueOf(d.getId());
        } catch (Throwable t) {
            return "?";
        }
    }
}
