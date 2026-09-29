package net.shurui.shuruisutilities.core.mixin.entity;

import net.minecraft.world.entity.LivingEntity;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Last-line guard against a Not-a-Number value ever reaching an entity's health pool.
 *
 * <p>THE FAILURE THIS STOPS. A single {@code setHealth(NaN)} bricks an entity permanently. Vanilla's
 * {@code setHealth} clamps through {@code Mth.clamp(health, 0, maxHealth)}, and {@code Mth.clamp} does NOT
 * reject NaN: {@code NaN < 0} and {@code NaN > max} are both false, so the clamp returns the NaN untouched and
 * it is written into {@code DATA_HEALTH_ID}. From then on the entity is stuck, because every health test uses a
 * comparison that NaN loses: {@code isDeadOrDying()} is {@code getHealth() <= 0} (false for NaN, so it never
 * dies), and {@code isAlive()} is {@code getHealth() > 0} (also false). It parks at what a health bar reads as
 * zero and cannot be killed. Worse, the NaN is written to the entity's {@code Health} NBT on save, so a restart
 * does NOT clear a mob that is already in this state.
 *
 * <p>WHY HERE AND NOT AT THE DAMAGE EVENT. {@code setHealth} is the single choke point every route funnels
 * through: the damage pipeline ({@code LivingEntity.actuallyHurt} computes {@code getHealth() - amount} and
 * calls {@code setHealth}), healing, senzu, transform revives, region heal, and every direct write. Guarding
 * one method covers all of them and is independent of event ordering, which the interleaved DMZ / addon
 * {@code LivingDamageEvent} handlers make fragile.
 *
 * <p>WHAT IT DOES. On a non-finite incoming health it substitutes a finite value and logs (throttled), so the
 * next occurrence is still diagnosable rather than silently swallowed:
 * <ul>
 *   <li>if the entity's CURRENT health is a valid positive number, keep it (the bad write becomes a no-op, so a
 *       NaN damage amount neither harms nor bricks, and the entity stays killable by the next real hit);</li>
 *   <li>otherwise the entity was ALREADY bricked (its stored health is itself NaN): repair it to its max health
 *       if that is finite, else to 1.0. This is what un-sticks the mobs already frozen on the live servers: the
 *       first {@code setHealth} on them after this ships (any hit will do) restores real health, so an operator
 *       does not have to hunt them down, and no restart is needed.</li>
 * </ul>
 *
 * <p>Vanilla target, so remapping is on by default and this is an ordinary APPLY-required mixin (not a
 * {@code require = 0} DMZ mixin). The single float argument is captured with {@code argsOnly}; no other
 * parameters are touched.
 */
@Mixin(LivingEntity.class)
public abstract class MixinLivingEntityHealthGuard
{
    private static long su$lastNanHealthLog;

    @ModifyVariable(method = "setHealth(F)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float su$guardNaNHealth(float health)
    {
        if (Float.isFinite(health))
        {
            return health;
        }

        LivingEntity self = (LivingEntity) (Object) this;
        float current = self.getHealth();
        float max = self.getMaxHealth();
        float safe;
        if (Float.isFinite(current) && current > 0.0f)
        {
            safe = current; // ignore the bad write, preserve the last valid health
        }
        else if (Float.isFinite(max) && max > 0.0f)
        {
            safe = max; // already bricked (health itself was NaN): repair to full
        }
        else
        {
            safe = 1.0f; // max is broken too: keep it alive at 1 so it can still be killed
        }

        // Throttle so a bricked entity taking a hit every tick cannot spam the log, but still surface it.
        long now = System.currentTimeMillis();
        if (now - su$lastNanHealthLog > 2000L)
        {
            su$lastNanHealthLog = now;
            LoggingHandler.sulog.warn(
                    "[health-guard] Blocked a non-finite health write ({}) on {} at {} (was {}, max {}); substituted {}. "
                            + "Something upstream produced NaN/Infinity damage or health; see the stack.",
                    health, self.getType(), self.blockPosition(), current, max, safe,
                    new Throwable("non-finite setHealth"));
        }
        return safe;
    }
}
