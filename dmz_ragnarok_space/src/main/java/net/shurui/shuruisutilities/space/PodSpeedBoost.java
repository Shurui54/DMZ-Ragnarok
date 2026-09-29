package net.shurui.shuruisutilities.space;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

import com.dragonminez.common.config.CombatConfig;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

/**
 * Job 1: makes a ridden space pod fly at 2x the pilot's own max FLIGHT speed while inside the SU space dimension.
 *
 * <p>WHY server-side and attribute-driven: {@code SpacePodEntity.travel()} derives the pod's HORIZONTAL speed as
 * {@code getAttributeValue(Attributes.FLYING_SPEED) * 0.45}, and entity attributes sync to tracking clients
 * automatically, so setting the pod's FLYING_SPEED here (no mixin) makes the client's own {@code travel()} compute
 * the boosted horizontal speed. The VERTICAL component is a hardcoded +/-0.35 in DMZ that ignores the attribute, so
 * it is boosted separately by {@code space.mixin.dmz.MixinDmzSpacePodVerticalSpeed} (which reads back this same
 * FLYING_SPEED so all three axes end up at the identical 2x magnitude).
 *
 * <p>The max-flight-speed derivation is a server-side re-implementation of DMZ 2.1.3's own on-foot handler
 * {@code com.dragonminez.client.flight.CombatFlightHandler} (verified against the jar, not the note):
 * <pre>
 *   maxFlightSpeed = combatFlySprintSpeed * (1 + 0.2 * flyLevel) * speedScale
 *   speedScale     = flyAttr <= 0 ? 1.0 : clamp(flyAttr / 0.35, 0.25, 4.0)
 * </pre>
 * where {@code combatFlySprintSpeed} is {@code ConfigManager.getCombatConfig().getCombatFlySprintSpeed()}
 * (DMZ default 0.5), {@code flyLevel} is {@code StatsData.getSkills().getSkillLevel("fly")}, and {@code flyAttr}
 * is the DMZ attribute {@code EntityAttributes.FLY_SPEED} (default 0.35). Every DMZ touch point is Throwable-guarded
 * per the workspace rule for reaching into DMZ 2.1.3 internals: a shape shift degrades to "pod keeps stock speed",
 * never a crash.
 */
public final class PodSpeedBoost
{
    private PodSpeedBoost()
    {
    }

    // DMZ's SpacePodEntity.travel() horizontal factor: delta = FLYING_SPEED * 0.45. Kept in one place so the derived
    // attribute and the vertical mixin agree on the same 0.45.
    public static final double POD_HORIZONTAL_FACTOR = 0.45D;

    // SpacePodEntity.createAttributes() registers FLYING_SPEED = 2.4 (stock horizontal 1.08). This is the value we
    // reset to when a pod leaves the boosted context, i.e. genuine stock.
    private static final double DEFAULT_POD_FLYING_SPEED = 2.4D;

    // The FLOOR used inside space is TWICE the stock pod FLYING_SPEED, not equal to it. This matters: an ordinary
    // pilot (fly level 0, speed scale 1.0) has a max on-foot flight speed of only 0.5, so "2x max" resolves to a
    // FLYING_SPEED target of 2 * 0.5 / 0.45 = 2.22, which is BELOW stock 2.4. A floor equal to stock (2.4) would
    // therefore clamp every ordinary pilot right back to the stock pod and hand them zero boost, silently killing
    // the whole feature (this was the exact shipped bug). Flooring at 2x stock (4.8 -> horizontal 4.8 * 0.45 = 2.16
    // blocks/tick, double the stock 1.08) guarantees even a no-investment pilot always feels a clear speed-up, while
    // a strongly invested flyer still exceeds the floor through the 2x-max term. Do NOT lower this back to 1x stock.
    private static final double MIN_POD_FLYING_SPEED = 2.0D * DEFAULT_POD_FLYING_SPEED;

    // DMZ's default fly attribute and the divisor used in its speedScale clamp (CombatFlightHandler.getFlySpeedScale).
    private static final double DMZ_DEFAULT_FLY_ATTR = 0.35D;

    /**
     * Set the pod's FLYING_SPEED so its horizontal cruise equals 2x the pilot's max on-foot flight speed. Because
     * {@code travel()} multiplies FLYING_SPEED by 0.45, the target attribute is {@code (2 * maxFlightSpeed) / 0.45}.
     * Floored at 2x the stock pod value (see {@link #MIN_POD_FLYING_SPEED}) so even a no-investment pilot always
     * flies clearly faster than a stock pod. No-op / stock speed on any DMZ error.
     */
    public static void apply(ServerPlayer player, SpacePodEntity pod)
    {
        try
        {
            AttributeInstance inst = pod.getAttribute(Attributes.FLYING_SPEED);
            if (inst == null)
            {
                return;
            }
            double target = (2.0D * computeMaxFlightSpeed(player)) / POD_HORIZONTAL_FACTOR;
            inst.setBaseValue(Math.max(target, MIN_POD_FLYING_SPEED));
        }
        catch (Throwable t)
        {
            // DMZ internals shifted: leave whatever FLYING_SPEED the pod already had rather than crash the caller.
        }
    }

    /**
     * Put the pod's FLYING_SPEED back to DMZ's stock value. Called when a pod is boarded OUTSIDE space, so a pod that
     * was once boosted (or a future edge where a boosted pod is somehow ridden off-space) flies at normal speed there.
     */
    public static void reset(SpacePodEntity pod)
    {
        try
        {
            AttributeInstance inst = pod.getAttribute(Attributes.FLYING_SPEED);
            if (inst != null)
            {
                inst.setBaseValue(DEFAULT_POD_FLYING_SPEED);
            }
        }
        catch (Throwable t)
        {
            // ignore: a pod we could not reset simply keeps its current speed.
        }
    }

    /**
     * The pilot's current max on-foot flight speed, mirroring DMZ's CombatFlightHandler. Returns a sane stock-ish
     * fallback on any error so {@link #apply} still floors the pod to a normal speed.
     */
    public static double computeMaxFlightSpeed(ServerPlayer player)
    {
        try
        {
            CombatConfig cfg = ConfigManager.getCombatConfig();
            double sprint = cfg.getCombatFlySprintSpeed();     // DMZ default 0.5
            int flyLevel = flyLevel(player);
            double levelFactor = 1.0D + 0.2D * flyLevel;
            return sprint * levelFactor * flySpeedScale(player);
        }
        catch (Throwable t)
        {
            // DMZ config/stats shape shift: a stock-ish max so the pod floors to DEFAULT_POD_FLYING_SPEED.
            return 0.5D;
        }
    }

    // DMZ fly-skill level, 0 if the capability/skills are unavailable.
    private static int flyLevel(ServerPlayer player)
    {
        try
        {
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            return data == null ? 0 : data.getSkills().getSkillLevel("fly");
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    // server-side re-implementation of CombatFlightHandler.getFlySpeedScale: 1.0 if the fly attribute is absent or
    // non-positive, else clamp(flyAttr / 0.35, 0.25, 4.0).
    private static double flySpeedScale(ServerPlayer player)
    {
        try
        {
            AttributeInstance inst = player.getAttribute(EntityAttributes.FLY_SPEED.get());
            double flyAttr = inst == null ? 0.0D : inst.getValue();
            if (flyAttr <= 0.0D)
            {
                return 1.0D;
            }
            return Mth.clamp(flyAttr / DMZ_DEFAULT_FLY_ATTR, 0.25D, 4.0D);
        }
        catch (Throwable t)
        {
            return 1.0D;
        }
    }
}
