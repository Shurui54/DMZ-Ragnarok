package net.shurui.shuruisutilities.npcregion;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.guilds.integration.DmzBridge;

/**
 * Level-scaled stats for NPC region NPCs and advanced spawner mobs (owner 2026-10): max health, melee damage, the DMZ
 * NPC defence curve value and ki blast damage each have a value at level 1 (the configured stat, unchanged) and one
 * at the config's max level, and the mob gets the straight-line value for the level it is scaled for
 * ({@link LevelScale}).
 *
 * <h2>Which level</h2>
 * At spawn the mob is scaled for the LOWEST DMZ level among the players near the spawn point (the spawner picks
 * them), so a fresh spawn never one-shots the weakest player around. When a player of a HIGHER level attacks it, it
 * is raised to that player's level, keeping the fraction of health it had, so a high-level player gets a real fight
 * and cannot be handed a weakened mob by a low-level friend tagging it first. It is never lowered. One compound tag
 * read on an attack, so it costs nothing for every other entity.
 *
 * <h2>What it leaves alone</h2>
 * Only mobs a spawner stamped with {@link #TAG}. Floor bosses, raid bosses, the airdrop crate boss, the spawner's boss
 * roll and story or quest NPCs are never stamped. Speed and aggro range never scale.
 *
 * <h2>DragonMineZ's kill TP</h2>
 * DMZ pays a kill {@code maxHealth * tpHealthRatio} on top of any configured range (except where the advanced
 * spawner's range replaces it, 619ed91). Scaled health would scale that share as well and stack a second level curve
 * on the configured one, so for the kill grant the mob's max health is put back to its level 1 value at the very start
 * of the death event: DMZ's health share stays exactly what it paid before scaling existed.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class LevelScaledStats
{
    /** Persistent-data compound on a scaled mob. Keys below. */
    public static final String TAG = "dmzr_lvl_stats";

    private static final String MAX = "max", LV = "lv";
    private static final String HP1 = "hp1", HPM = "hpM", DMG1 = "dmg1", DMGM = "dmgM";
    private static final String DEF1 = "def1", DEFM = "defM", KI1 = "ki1", KIM = "kiM";
    private static final String HEALTH_OVERRIDE_KEY = "sdu_health_override";
    private static final String NPC_DEFENSE_KEY = "dmz_npc_defense";

    private LevelScaledStats() {}

    /**
     * Stamp the anchors on a freshly built mob (before it joins the world) and scale it for {@code level}.
     * {@code ki1 / kiM} may both be 0 (no ki), {@code def1 / defM} likewise.
     */
    public static void stampAndApply(LivingEntity e, int maxLevel, double hp1, double hpM, double dmg1, double dmgM,
                                     double def1, double defM, double ki1, double kiM, int level)
    {
        // Ki blast damage: the configured value only ever reached OUR fighters (sdu reads the spawn NBT key), while a
        // DragonMineZ NPC kept its native blast damage. Keep that difference: a DMZ NPC scales its OWN native value on
        // the melee line (same ratio), so at level 1 it is untouched and nothing is configured onto it.
        if (e instanceof DBSagasEntity saga && !(e instanceof net.shurui.dev.sdu.entity.SduDmzFighter))
        {
            double nativeKi = saga.getKiBlastDamage();
            ki1 = nativeKi;
            kiM = nativeKi * (dmg1 > 0 ? dmgM / dmg1 : LevelScale.DEFAULT_MULTIPLE);
        }
        CompoundTag t = new CompoundTag();
        t.putInt(MAX, Math.max(2, maxLevel));
        t.putDouble(HP1, hp1);
        t.putDouble(HPM, hpM);
        t.putDouble(DMG1, dmg1);
        t.putDouble(DMGM, dmgM);
        t.putDouble(DEF1, def1);
        t.putDouble(DEFM, defM);
        t.putDouble(KI1, ki1);
        t.putDouble(KIM, kiM);
        t.putInt(LV, 0);
        e.getPersistentData().put(TAG, t);
        apply(e, Math.max(1, level));
    }

    /** The level this mob is currently scaled for, or 0 when it is not a scaled mob. */
    public static int scaledLevel(LivingEntity e)
    {
        CompoundTag pd = e.getPersistentData();
        return pd.contains(TAG) ? pd.getCompound(TAG).getInt(LV) : 0;
    }

    /** Scale a stamped mob for {@code level}, keeping its current health fraction. No-op on an unstamped mob. */
    public static void apply(LivingEntity e, int level)
    {
        CompoundTag pd = e.getPersistentData();
        if (!pd.contains(TAG))
            return;
        CompoundTag t = pd.getCompound(TAG);
        int max = t.getInt(MAX);
        double hp = Math.max(1.0, LevelScale.lerp(t.getDouble(HP1), t.getDouble(HPM), level, max));
        double dmg = Math.max(0.0, LevelScale.lerp(t.getDouble(DMG1), t.getDouble(DMGM), level, max));
        double def = Math.max(0.0, LevelScale.lerp(t.getDouble(DEF1), t.getDouble(DEFM), level, max));
        double ki = Math.max(0.0, LevelScale.lerp(t.getDouble(KI1), t.getDouble(KIM), level, max));

        float frac = e.getMaxHealth() > 0 ? e.getHealth() / e.getMaxHealth() : 1.0f;
        if (!(frac > 0f))
            frac = 1.0f;
        setBase(e, Attributes.MAX_HEALTH, hp);
        // sdu's fighter re-pins its health to this key on its first tick; keep it in step when the spawner set it
        if (pd.contains(HEALTH_OVERRIDE_KEY))
            pd.putDouble(HEALTH_OVERRIDE_KEY, hp);
        e.setHealth((float) Math.max(1.0, Math.min(e.getMaxHealth(), e.getMaxHealth() * frac)));
        setBase(e, Attributes.ATTACK_DAMAGE, dmg);
        if (t.getDouble(DEF1) > 0 || t.getDouble(DEFM) > 0)
            pd.putDouble(NPC_DEFENSE_KEY, def);
        if ((t.getDouble(KI1) > 0 || t.getDouble(KIM) > 0) && e instanceof DBSagasEntity saga)
            saga.setKiBlastDamage((float) ki);
        t.putInt(LV, level);
    }

    private static void setBase(LivingEntity e, net.minecraft.world.entity.ai.attributes.Attribute a, double v)
    {
        AttributeInstance inst = e.getAttribute(a);
        if (inst != null)
            inst.setBaseValue(v);
    }

    /** Raise a scaled mob to a higher-level attacker before the hit lands (never lowered). */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttack(LivingAttackEvent event)
    {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || !victim.getPersistentData().contains(TAG))
            return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker))
            return;
        try
        {
            int level = DmzBridge.level(attacker);
            if (level > scaledLevel(victim))
                apply(victim, level);
        }
        catch (Throwable t)
        {
            // a scaling failure must never cancel or break a hit
        }
    }

    /**
     * DMZ's health-based kill TP reads the dying mob's max health at NORMAL priority; give it the level 1 value so the
     * scaled health never inflates that share (see the class notes). The mob is dead, nothing else needs its scaled
     * health back, except a cancelled death, which {@link #onDeathCancelled} restores.
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onDeath(LivingDeathEvent event)
    {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || !e.getPersistentData().contains(TAG))
            return;
        CompoundTag t = e.getPersistentData().getCompound(TAG);
        double hp1 = t.getDouble(HP1);
        if (hp1 > 0)
            setBase(e, Attributes.MAX_HEALTH, hp1);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onDeathCancelled(LivingDeathEvent event)
    {
        LivingEntity e = event.getEntity();
        if (!event.isCanceled() || e.level().isClientSide || !e.getPersistentData().contains(TAG))
            return;
        int lv = scaledLevel(e);
        if (lv > 0)
            apply(e, lv); // it survived (a revive): back to its scaled health, fraction kept
    }
}
