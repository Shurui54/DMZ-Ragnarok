package net.shurui.dev.sdu.event;

import com.dragonminez.common.stats.StatsData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.compat.DmzForms;
import net.shurui.dev.sdu.race.RacialSkillConfig;
import net.shurui.dev.sdu.race.RacialSkillData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime for the addon's JSON-editable racial skills ({@link RacialSkillConfig}). Each server player
 * whose DMZ race has configured racial behaviour gets its effects applied according to the racial's
 * {@link RacialSkillData.Trigger}: always-on, while below an HP threshold (Zenkai-style), for a
 * window after taking a hit or getting a kill, or on the racial keybind (with a cooldown).
 *
 * <p>While active a racial applies percentage stat buffs (via DMZ {@code BonusStats}, tagged
 * {@link DmzForms#RACIAL_SOURCE}), health/ki/stamina regen, potion effects and incoming-damage
 * resistance; instant restores fire once when it triggers. Buffs are removed when the racial is no
 * longer active and on logout, mirroring the form-combat system.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class RacialSkillHandler {

    private static final class State {
        long activeUntil = Long.MIN_VALUE;
        long cooldownUntil = Long.MIN_VALUE;
        boolean bonusesApplied = false;
        /**
         * stat -> the multiplier we last handed to {@link DmzForms#setMultiplier}. An always-on racial re-runs
         * {@link #applyActive} once a second, and {@code setMultiplier} REPLACES a stat's bonus (remove then add):
         * writing the same value every second churns the BonusStats list, so any {@code StatsSyncS2C} that races a
         * pass (a stat purchase, a transform) can capture the half-applied moment, which the player sees as the
         * buff flickering off and back on. We track what we last wrote and only touch BonusStats when the value
         * actually changes, so a steady always-on buff is written once and left alone. Same shape as
         * {@code FormCombatHandler.Applied}.
         */
        final Map<String, Double> appliedMultipliers = new java.util.HashMap<>();
    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private RacialSkillHandler() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide()) {
            return;
        }
        Player player = event.player;
        if (player.tickCount % 20 != 0) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        RacialSkillData data = dataFor(stats);
        State st = STATES.get(player.getUUID());
        if (data == null) {
            if (st != null) {
                clear(player, stats);
            }
            return;
        }
        if (st == null) {
            st = new State();
            STATES.put(player.getUUID(), st);
        }
        long now = player.level().getGameTime();

        // ON_LOW_HP re-triggers (for its duration, then cooldown) whenever the player is below the
        // threshold, off cooldown and not already active.
        if (data.trigger == RacialSkillData.Trigger.ON_LOW_HP
                && hpPercent(player) < data.lowHpThreshold
                && now >= st.cooldownUntil && now >= st.activeUntil) {
            trigger(player, stats, data, st, now);
        }

        if (isActive(data, st, now)) {
            applyActive(player, stats, data, st);
        } else if (st.bonusesApplied) {
            DmzForms.clearBonuses(stats, DmzForms.RACIAL_SOURCE);
            st.bonusesApplied = false;
            // Forget what we wrote so a later re-trigger applies the buff fresh instead of skipping it as unchanged.
            st.appliedMultipliers.clear();
        }
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        RacialSkillData data = dataFor(stats);
        if (data == null) {
            return;
        }
        State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        long now = player.level().getGameTime();

        // Damage resistance while active (always-on racials are always active).
        if (data.damageResistPct > 0 && isActive(data, st, now)) {
            float reduced = event.getAmount() * (float) (1.0 - data.damageResistPct / 100.0);
            event.setAmount(Math.max(0f, reduced));
        }
        // ON_HIT triggers the racial for its duration, respecting cooldown.
        if (data.trigger == RacialSkillData.Trigger.ON_HIT && now >= st.cooldownUntil) {
            trigger(player, stats, data, st, now);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide()
                || !(event.getSource().getEntity() instanceof Player player)) {
            return;
        }
        StatsData stats = DmzForms.stats(player);
        RacialSkillData data = dataFor(stats);
        if (data == null || data.trigger != RacialSkillData.Trigger.ON_KILL) {
            return;
        }
        State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        long now = player.level().getGameTime();
        if (now >= st.cooldownUntil) {
            trigger(player, stats, data, st, now);
        }
    }

    /** Called from the racial-activation packet: activate an ACTIVE racial if off cooldown. */
    public static void activateFromKey(ServerPlayer player) {
        StatsData stats = DmzForms.stats(player);
        RacialSkillData data = dataFor(stats);
        if (data == null || data.trigger != RacialSkillData.Trigger.ACTIVE) {
            return;
        }
        State st = STATES.computeIfAbsent(player.getUUID(), k -> new State());
        long now = player.level().getGameTime();
        if (now < st.cooldownUntil) {
            long secs = (st.cooldownUntil - now) / 20;
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.npc.racial.cooldown", net.shurui.dev.sdu.util.StringUtil.formatDuration(secs)), true);
            return;
        }
        trigger(player, stats, data, st, now);
        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("message.dmz_ragnarok.npc.racial.activated"), true);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        clear(event.getEntity(), DmzForms.stats(event.getEntity()));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        // Purge any racial buffs DMZ may have persisted to NBT; state starts fresh.
        if (event.getEntity() instanceof ServerPlayer player) {
            DmzForms.clearBonuses(DmzForms.stats(player), DmzForms.RACIAL_SOURCE);
            STATES.remove(player.getUUID());
        }
    }

    /** Start a timed racial (setting its duration + cooldown) and fire its instant effects. */
    private static void trigger(Player player, StatsData stats, RacialSkillData data, State st, long now) {
        st.activeUntil = now + (long) (data.durationSeconds * 20);
        st.cooldownUntil = now + (long) (data.cooldownSeconds * 20);
        DmzForms.restore(player, stats, data.instantHealPct, data.instantKiPct, data.instantStaminaPct);
        applyActive(player, stats, data, st);
    }

    /** Always-on racials are permanently active; every other trigger is active only for its window. */
    private static boolean isActive(RacialSkillData data, State st, long now) {
        return data.trigger == RacialSkillData.Trigger.ALWAYS_ON || now < st.activeUntil;
    }

    /** Apply the racial's per-tick effects: stat buffs, one second of regen, and potion effects. */
    private static void applyActive(Player player, StatsData stats, RacialSkillData data, State st) {
        for (String stat : RacialSkillData.STATS) {
            double pct = data.stat(stat);
            // Apply as a multiplier (not a flat add) so it actually scales DMZ's damage/defense:
            // pct% boost -> a (1 + pct/100) multiplier; 0 removes the buff. (See DmzForms.setMultiplier.)
            double mult = pct == 0 ? 1.0 : 1.0 + pct / 100.0;
            // Only touch BonusStats when the value actually differs from what we last wrote. DMZ carries our
            // RACIAL_SOURCE bonus across death/respawn/dimension (BonusStats.copyFrom), and calculateBonus recomputes
            // a "*" bonus from the CURRENT base every read, so a value written once stays correct as stats grow: an
            // unconditional per-second rewrite only churns the list and lets a racing sync show a flicker (bug 726).
            Double last = st.appliedMultipliers.get(stat);
            if (last == null || last != mult) {
                DmzForms.setMultiplier(stats, DmzForms.RACIAL_SOURCE, stat, mult);
                st.appliedMultipliers.put(stat, mult);
            }
        }
        st.bonusesApplied = true;
        // Regen is called from the once-per-second maintenance tick, so these are per-second amounts.
        DmzForms.restore(player, stats, data.healthRegenPct, data.kiRegenPct, data.staminaRegenPct);
        applyPotions(player, data);
    }

    private static void applyPotions(Player player, RacialSkillData data) {
        for (String spec : data.potionEffects) {
            MobEffectInstance inst = parsePotion(spec);
            if (inst != null) {
                player.addEffect(inst);
            }
        }
    }

    /** Parse {@code "namespace:path:amplifier"} into a 3-second refreshable effect instance. */
    private static MobEffectInstance parsePotion(String spec) {
        try {
            String[] parts = spec.split(":");
            if (parts.length < 2) {
                return null;
            }
            ResourceLocation id = new ResourceLocation(parts[0], parts[1]);
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.get(id);
            if (effect == null) {
                return null;
            }
            int amp = parts.length >= 3 ? Integer.parseInt(parts[2].trim()) : 0;
            return new MobEffectInstance(effect, 60, Math.max(0, amp), false, true);
        } catch (Exception e) {
            return null;
        }
    }

    private static void clear(Player player, StatsData stats) {
        DmzForms.clearBonuses(stats, DmzForms.RACIAL_SOURCE);
        STATES.remove(player.getUUID());
    }

    private static float hpPercent(Player player) {
        float max = player.getMaxHealth();
        return max <= 0 ? 100f : player.getHealth() / max * 100f;
    }

    /** The configured racial behaviour for the player's current race, or {@code null} if none. */
    private static RacialSkillData dataFor(StatsData stats) {
        if (stats == null) {
            return null;
        }
        String id = DmzForms.racialSkillId(stats);
        return id != null && RacialSkillConfig.has(id) ? RacialSkillConfig.get(id) : null;
    }
}
