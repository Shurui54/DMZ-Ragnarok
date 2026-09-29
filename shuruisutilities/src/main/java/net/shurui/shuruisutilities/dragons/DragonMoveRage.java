package net.shurui.shuruisutilities.dragons;

import java.util.List;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.compat.dmz.DragonDamage;
import net.shurui.shuruisutilities.compat.dmz.DragonHurt;

/**
 * Rage Shenron (5 stars): Dragon Thunder. Strikes everyone within 10 blocks with lightning, slowing them and ticking
 * electric damage for the duration.
 *
 * <h2>The lightning is visual only</h2>
 * Each bolt is spawned with {@code setVisualOnly(true)}, so vanilla neither sets fires nor applies its own lightning
 * damage. Both would be wrong here: fire in an arena is griefing, and vanilla's flat damage would land on top of the
 * move's own figure and break the "(melee + strike + ki) / 2 total" rule. The bolt is there for the strike and the
 * thunderclap; every point of damage comes from {@link DragonHurt}.
 *
 * <h2>Sound</h2>
 * DMZ ships no lightning sound (its 151 sounds have no thunder entry); {@code ki_sparks} is its electric one and is
 * what plays here for the ticking shock. The bolts themselves bring vanilla's thunderclap with them.
 */
public final class DragonMoveRage
{
    private DragonMoveRage() {}

    /** How far the storm reaches. */
    public static final double RADIUS = 10.0;

    /** Total duration of the shock, in ticks. */
    public static final int DURATION_TICKS = 160;

    /**
     * Rage's yellow, matching the technique's own colours.
     *
     * <p>It was storm blue, which read as Eis's ice from any distance; two of the seven dragons cannot both own the
     * same colour. Yellow is also the one every lightning effect in the game already uses.
     */
    private static final int STORM_MAIN = 0xFFF6A8;
    private static final int STORM_BORDER = 0xFFD21A;
    private static final int STORM_OUTLINE = 0xB8860B;

    /** Ticks between damage applications; with the duration above, 5 damage ticks in total. */
    public static final int DAMAGE_INTERVAL_TICKS = 20;

    public static final int DAMAGE_TICKS = DURATION_TICKS / DAMAGE_INTERVAL_TICKS;

    /** DMZ's electric sound. Resolved by id so a missing sound simply goes quiet rather than failing the cast. */
    private static final ResourceLocation SPARKS = new ResourceLocation("dragonminez", "ki_sparks");

    static boolean cast(LivingEntity caster, ServerLevel level)
    {
        // Storm-yellow ball swelling out of the caster to the edge of the strike zone, and holding there for the
        // whole shock.
        DragonOrbSwell.start(caster, level, RADIUS, STORM_MAIN, STORM_BORDER, STORM_OUTLINE, DURATION_TICKS);
        // Always shows itself, even with nothing in range - see the note in DragonMoveEis.
        List<LivingEntity> victims = DragonMoveEffects.targets(caster, level, RADIUS);

        // The storm sounds and strikes whether or not it catches anyone.
        SoundEvent sparks = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(SPARKS);
        if (sparks != null)
            level.playSound(null, caster.blockPosition(), sparks, SoundSource.PLAYERS, 1.4f, 0.9f);
        strikeAround(level, caster);

        float perTick = DragonDamage.perTick(caster, DAMAGE_TICKS);
        for (LivingEntity victim : victims)
        {
            strike(level, victim);
            DragonEffectTicker.add(new Shocked(caster, victim, level, perTick));
        }
        return true;
    }

    /** A scatter of visual bolts across the radius, so the storm is visible with nobody standing in it. */
    private static void strikeAround(ServerLevel level, LivingEntity caster)
    {
        var random = level.getRandom();
        for (int i = 0; i < 6; i++)
        {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = random.nextDouble() * RADIUS;
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt == null)
                continue;
            bolt.moveTo(caster.getX() + Math.cos(angle) * dist,
                    caster.getY(), caster.getZ() + Math.sin(angle) * dist);
            bolt.setVisualOnly(true);
            level.addFreshEntity(bolt);
        }
    }

    /** One visual-only bolt on a victim: the strike and the thunderclap, none of vanilla's fire or damage. */
    private static void strike(ServerLevel level, LivingEntity victim)
    {
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
        if (bolt == null)
            return;
        bolt.moveTo(victim.getX(), victim.getY(), victim.getZ());
        bolt.setVisualOnly(true);
        level.addFreshEntity(bolt);
    }

    /** One shocked target: slowed, and ticking electric damage until the effect runs out. */
    static final class Shocked implements DragonEffectTicker.ActiveEffect
    {
        private final LivingEntity caster;
        private final LivingEntity victim;
        private final ServerLevel level;
        private final float damagePerTick;
        private int ticksLeft = DURATION_TICKS;

        Shocked(LivingEntity caster, LivingEntity victim, ServerLevel level, float damagePerTick)
        {
            this.caster = caster;
            this.victim = victim;
            this.level = level;
            this.damagePerTick = damagePerTick;
        }

        @Override
        public boolean tick()
        {
            if (!victim.isAlive() || ticksLeft <= 0)
                return false;
            ticksLeft--;

            victim.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 40, 2, false, true, true));

            if (ticksLeft % DAMAGE_INTERVAL_TICKS == 0)
            {
                if (damagePerTick > 0.0f)
                    DragonHurt.hurt(caster, victim, damagePerTick);
                SoundEvent sparks = net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.get(SPARKS);
                if (sparks != null)
                    level.playSound(null, victim.blockPosition(), sparks, SoundSource.PLAYERS, 1.0f, 1.0f);
            }
            return ticksLeft > 0;
        }
    }
}
