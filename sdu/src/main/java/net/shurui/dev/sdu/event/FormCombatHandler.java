package net.shurui.dev.sdu.event;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.form.FormCombatConfig;
import net.shurui.dev.sdu.form.FormCombatData;

import com.dragonminez.common.stats.StatsData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.shurui.dev.sdu.KeyGate;
import net.shurui.dev.sdu.api.key.CoreGateHooks;

/**
 * Runtime for the addon's per-form combat system (config in {@link FormCombatConfig}). While a player
 * is in a form that has combat settings:
 * <ol>
 *   <li><b>Auto dodge</b> is rolled on {@link LivingAttackEvent} (the very start of the hit) so a
 *       successful dodge cancels the attack outright - no damage, and no hurt sound, flinch or
 *       knockback either (those are applied later in {@code hurt()});</li>
 *   <li>on a hit that lands, damage is mitigated by the configured percentage. This runs on
 *       {@link LivingDamageEvent} at {@code LOWEST}, registered from {@code DmzNpc.commonSetup} (not via
 *       {@code @SubscribeEvent}) so it fires AFTER DMZ's own {@code LOWEST} recompute
 *       ({@code CombatEvent.overrideVanillaArmorReduction}), which rebuilds the final amount from a
 *       raw-damage snapshot and would otherwise discard anything we subtract on {@code LivingHurtEvent};</li>
 *   <li>and, every tick, each stat is buffed by a percentage of the player's <em>base</em> stat
 *       (snapshotted on the transform edge), summed across the active form layers and clamped to a cap.</li>
 * </ol>
 * The stat buff is recomputed statelessly from the currently-active form layers every tick (mirroring
 * {@code RacialSkillHandler.applyActive} and DMZ's own replace-by-source model), so dropping a stacked
 * layer immediately removes only that layer's contribution. Buffs and the base snapshot are cleared
 * when the player is no longer in any configured form (and on login, to purge any DMZ persisted to NBT).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class FormCombatHandler {

    /** Per-player snapshot of each stat's BASE value, taken on the transform edge (empty -> in-form). */
    private static final Map<UUID, Map<String, Double>> BASE_SNAPSHOT = new ConcurrentHashMap<>();
    /** Per-player set of configured-form layers active as of the previous tick (for edge detection). */
    private static final Map<UUID, Set<String>> PREV_LAYERS = new ConcurrentHashMap<>();
    /** Per-player record of the buff we last WROTE and the inputs it was computed from (see {@link Applied}). */
    private static final Map<UUID, Applied> APPLIED = new ConcurrentHashMap<>();

    private FormCombatHandler() {
    }

    /**
     * True when the form dodge is switched off entirely: whenever Shurui's Key is installed.
     *
     * <p>WHY THE KEY DECIDES THIS. The dodge chances stack as a UNION across every active form
     * ({@link #aggregate}), so a player deep in a stacked chain reaches a hit-avoidance rate that no
     * amount of the opponent's investment can answer - it is not a damage advantage that better stats
     * beat, it is a coin flip that ignores them. On a keyed server, where the full form ladder is
     * unlocked, that is reliably reachable and the fights stop being decided by stats at all. Servers
     * without the key never get deep enough into the ladder for it to matter, so they keep the feature.
     *
     * <p>{@link KeyGate#present()} rather than {@code unlocked()}: presence is the real condition, and
     * {@code unlocked()} is also true in singleplayer/LAN, which would switch the dodge off for anyone
     * playing solo - the exact case where it is harmless.
     *
     * <p>Only the DODGE is gated. Mitigation is a percentage reduction that scales against damage rather
     * than erasing a hit outright, so it stays on either way.
     *
     * <p>Asked through {@link CoreGateHooks}, which only the real Ragnarok Key installs (it answers the same
     * {@code present()}). The keyless default keeps the dodge on, so a jar that merely carries the key's mod id
     * cannot switch a public behaviour off.
     */
    private static boolean dodgeDisabled() {
        return CoreGateHooks.get().formDodgeDisabled();
    }

    /**
     * Auto dodge, rolled at the earliest point of a hit. Canceling here (rather than on
     * {@link LivingHurtEvent}) means a dodged attack never plays the hurt sound / flinch animation /
     * knockback, and deals no damage - a clean dodge.
     */
    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        if (dodgeDisabled()) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return;
        }
        List<String> forms = DmzForms.activeFormNames(stats);
        if (forms.isEmpty()) {
            return;
        }
        Aggregate agg = aggregate(forms);
        if (agg == null) {
            return;
        }
        DmzForms.DamageCategory cat = DmzForms.classify(event.getSource());
        double dodge = switch (cat) {
            case PHYSICAL -> agg.dodgePhysical;
            case MELEE_SKILL -> agg.dodgeMelee;
            case ENERGY_SKILL -> agg.dodgeEnergy;
            case OTHER -> 0.0;
        };
        if (dodge > 0 && player.getRandom().nextDouble() * 100.0 < dodge) {
            event.setCanceled(true);
            // Cosmetic flourish: twist the upper body to a random side. Rolled here (server) so every
            // viewer twists the same way.
            net.shurui.dev.sdu.network.DmzNet.sendDodgeAnimation(player, player.getRandom().nextBoolean());
            // Whoosh, heard by everyone nearby including the dodger (null player = broadcast to all,
            // so it plays even in first person where the body twist isn't visible).
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.9f,
                    1.3f + player.getRandom().nextFloat() * 0.2f);
        }
    }

    /**
     * Damage mitigation. NOT a {@code @SubscribeEvent}: {@code DmzNpc.commonSetup} registers this on
     * {@link LivingDamageEvent} at {@code LOWEST}, which is added to the bus after DMZ's auto-subscribed
     * {@code LOWEST} recompute (see the class javadoc). We therefore scale the already-final amount, so a
     * configured percentage survives verbatim instead of being thrown away by DMZ's raw-damage rebuild.
     *
     * <p>Mitigation is independent of dodge (dodge cancels the whole hit on {@link LivingAttackEvent}) and
     * applies to every damage type that reaches this event, including the sources DMZ excludes from its own
     * mitigation (fall, magic, wither, ...). {@code receiveCancelled} is false at registration, so a hit DMZ
     * fully negated (0 damage) is skipped.
     */
    public static void onLivingDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        float amount = event.getAmount();
        if (amount <= 0f) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        if (stats == null) {
            return;
        }
        List<String> forms = DmzForms.activeFormNames(stats);
        if (forms.isEmpty()) {
            return;
        }
        Aggregate agg = aggregate(forms);
        if (agg == null || agg.mitigation <= 0) {
            return;
        }
        event.setAmount(Math.max(0f, amount * (float) (1.0 - agg.mitigation / 100.0)));
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        Player player = event.player;
        UUID id = player.getUUID();
        StatsData stats = DmzForms.stats(player);

        // The configured-form layers active right now (base + stack), in a stable set for edge diffing.
        Set<String> current = new HashSet<>();
        if (stats != null) {
            for (String name : DmzForms.activeFormNames(stats)) {
                if (FormCombatConfig.has(name)) {
                    current.add(name);
                }
            }
        }

        if (current.isEmpty()) {
            // No configured form: drop the buff and all cached state (idempotent if already cleared).
            if (BASE_SNAPSHOT.containsKey(id) || PREV_LAYERS.containsKey(id) || APPLIED.containsKey(id)) {
                clear(player, stats);
            }
            return;
        }

        Set<String> prev = PREV_LAYERS.get(id);
        // Transform edge: empty -> non-empty. Snapshot each stat's BASE now and keep it until detransform.
        boolean edge = prev == null || prev.isEmpty();
        if (edge) {
            Map<String, Double> snap = new HashMap<>();
            for (String stat : FormCombatData.STATS) {
                snap.put(stat, (double) DmzForms.baseStat(stats, stat));
            }
            BASE_SNAPSHOT.put(id, snap);
        }
        PREV_LAYERS.put(id, current);

        // The written multipliers are a pure function of (active form set, config, base snapshot): the base
        // snapshot only changes on the transform edge, so once those inputs are stable the six values we would
        // write are identical every tick. DMZ carries our BonusStats source across death/respawn/dimension
        // (StatsData.copyFrom -> BonusStats.copyFrom), so a value we wrote once stays applied without a per-tick
        // rewrite. Recompute (and re-aggregate, which deep-copies config) ONLY when an input changed: the edge,
        // a form-set change, or a config reload/edit. Steady state does no allocation and no BonusStats writes.
        Applied applied = APPLIED.get(id);
        int cfgRevision = FormCombatConfig.revision();
        if (!edge && applied != null && applied.configRevision == cfgRevision && applied.forms.equals(current)) {
            return;
        }

        // Recompute the buff statelessly from the CURRENT active layers. Because BonusStats replaces by source,
        // this immediately reflects a dropped stack layer (BUG B fix).
        Aggregate agg = aggregate(new ArrayList<>(current));
        Map<String, Double> base = BASE_SNAPSHOT.get(id);
        if (agg == null || base == null) {
            return;
        }
        boolean firstApply = applied == null;
        Applied next = firstApply ? new Applied() : applied;
        next.forms = current;
        next.configRevision = cfgRevision;
        for (String stat : FormCombatData.STATS) {
            double totalPct = agg.gain.getOrDefault(stat, 0.0);
            // Clamp the summed percentage to the aggregate cap (max across active layers, as before).
            if (totalPct > agg.maxBonusPercent) {
                totalPct = agg.maxBonusPercent;
            }
            double baseVal = base.getOrDefault(stat, 0.0);
            // No contribution for this stat -> multiplier of 1.0 (removes any lingering buff).
            double mult = (totalPct <= 0 || baseVal <= 0) ? 1.0 : 1.0 + totalPct / 100.0;
            // Only touch BonusStats when the value actually differs from what we last wrote (on the first apply
            // for a player we always write, matching the old unconditional pass).
            Double last = next.multipliers.get(stat);
            if (firstApply || last == null || last != mult) {
                DmzForms.setMultiplier(stats, stat, mult);
                next.multipliers.put(stat, mult);
            }
        }
        APPLIED.put(id, next);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // Purge any of our buffs DMZ may have persisted to NBT; cached state re-snapshots on next transform.
        if (event.getEntity() instanceof ServerPlayer player) {
            clear(player, DmzForms.stats(player));
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity(), DmzForms.stats(event.getEntity()));
    }

    private static void clear(Player player, StatsData stats) {
        UUID id = player.getUUID();
        BASE_SNAPSHOT.remove(id);
        PREV_LAYERS.remove(id);
        APPLIED.remove(id);
        DmzForms.clearBonuses(stats);
    }

    private static Aggregate aggregate(List<String> forms) {
        Aggregate a = null;
        for (String name : forms) {
            if (!FormCombatConfig.has(name)) {
                continue;
            }
            FormCombatData d = FormCombatConfig.get(name);
            if (a == null) {
                a = new Aggregate();
            }
            // Dodge / mitigation combine as independent probabilities (union).
            a.dodgePhysical = union(a.dodgePhysical, d.dodgePhysical);
            a.dodgeMelee = union(a.dodgeMelee, d.dodgeMeleeSkill);
            a.dodgeEnergy = union(a.dodgeEnergy, d.dodgeEnergySkill);
            a.mitigation = union(a.mitigation, d.damageMitigation);
            a.maxBonusPercent = Math.max(a.maxBonusPercent, d.maxBonusPercent);
            for (String stat : FormCombatData.STATS) {
                double g = d.gain(stat);
                if (g != 0) {
                    a.gain.merge(stat, g, Double::sum);
                    a.anyGain = true;
                }
            }
        }
        return a;
    }

    /** Combine two independent percentages (0-100) as P(A or B). */
    private static double union(double a, double b) {
        return (1.0 - (1.0 - a / 100.0) * (1.0 - b / 100.0)) * 100.0;
    }

    /** What we last wrote to a player's BonusStats, and the inputs that produced it, for change detection. */
    private static final class Applied {
        /** The configured-form layers the current buff was computed from. */
        Set<String> forms;
        /** The {@link FormCombatConfig#revision()} the current buff was computed under. */
        int configRevision;
        /** stat -> the multiplier we last handed to {@link DmzForms#setMultiplier}. */
        final Map<String, Double> multipliers = new HashMap<>();
    }

    private static final class Aggregate {
        double dodgePhysical;
        double dodgeMelee;
        double dodgeEnergy;
        double mitigation;
        double maxBonusPercent;
        boolean anyGain;
        final Map<String, Double> gain = new HashMap<>();
    }
}
