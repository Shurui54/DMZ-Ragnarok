package net.shurui.shuruisutilities.space.mixin.dmz;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.space.PodSpeedBoost;
import net.shurui.shuruisutilities.world.space.SpaceKeys;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Job 1 (vertical half): scales DMZ's HARDCODED +/-0.35 vertical climb on a ridden space pod up to the SAME 2x
 * max-flight-speed magnitude the horizontal boost uses, but only while the pod is in the SU space dimension.
 *
 * <p>Why a mixin at all: {@code SpacePodEntity.travel()} reads the FLYING_SPEED attribute for HORIZONTAL speed (which
 * {@link PodSpeedBoost} sets server-side, synced to clients, no mixin) but uses a literal +/-0.35 for VERTICAL that
 * ignores the attribute entirely. The vertical value is computed client-side from the jump/descend keybinds, so the
 * only way to boost it is to intercept the value on the client. We intercept the {@code setDeltaMovement(x, y, z)}
 * call in the passenger branch (ordinal 0) and rewrite the {@code y} arg.
 *
 * <p>We rewrite it to {@code copySign(getAttributeValue(FLYING_SPEED) * 0.45, y)}: that magnitude is exactly the
 * boosted horizontal speed (= 2x the pilot's max flight speed, since we set FLYING_SPEED to make it so), and
 * {@code copySign} preserves DMZ's up(+)/down(-)/none(0) direction from the keybind. So all three axes move at one
 * consistent speed and a pod flown OUTSIDE space keeps DMZ's stock +/-0.35.
 *
 * <p>{@code remap = false} at class level (the {@code @Mixin} target is DMZ's own class name, matching SU's other DMZ
 * mixins); the {@code @ModifyArg} carries {@code remap = true} so the refmap maps the vanilla method names
 * {@code travel} / {@code setDeltaMovement} to their SRG forms in the production DMZ jar, while the DMZ owner class in
 * the INVOKE target (the real constant-pool owner of that call) is left alone. {@code require = 0} is MANDATORY per
 * the standing rule for mixins into DMZ classes: a DMZ change makes this a silent no-op (horizontal boost keeps
 * working via the attribute) instead of crashing the client. Because require = 0 fails SILENTLY, the handler logs
 * once when it first runs; the ABSENCE of that line in the log is how we know the injector did not bind.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.SpacePodEntity", remap = false)
public abstract class MixinDmzSpacePodVerticalSpeed
{
    private static final AtomicBoolean SU_VERTICAL_BIND_LOGGED = new AtomicBoolean(false);

    @ModifyArg(
        method = "travel",
        at = @At(
            value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/SpacePodEntity;setDeltaMovement(DDD)V",
            ordinal = 0
        ),
        index = 1,
        require = 0,
        remap = true
    )
    private double su$boostVerticalInSpace(double vertical)
    {
        // log-once the first time this weaves. require = 0 fails SILENTLY, so this line appearing in the log is the
        // proof the injector bound; its ABSENCE is the proof it did not (a DMZ change killed the vertical boost while
        // the attribute-driven horizontal boost quietly kept working). Guarded + latched so it prints exactly once
        // per JVM and a logging hiccup can never touch the movement value below.
        if (SU_VERTICAL_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info("[SpacePod] vertical-speed boost mixin bound");
            }
            catch (Throwable ignored)
            {
            }
        }
        // no vertical input (neither jump nor descend held), or running server-side (DMZ leaves vertical 0 off the
        // client): leave it exactly as DMZ set it.
        if (vertical == 0.0D)
        {
            return vertical;
        }
        try
        {
            Level level = ((LivingEntity) (Object) this).level();
            // only the SU space dimension is boosted; a pod flown anywhere else keeps DMZ's stock +/-0.35 climb.
            if (!SpaceKeys.isSpace(level))
            {
                return vertical;
            }
            // match the boosted HORIZONTAL magnitude so all three axes move at the same 2x speed. copySign keeps
            // DMZ's up(+)/down(-) direction.
            double horizontal =
                ((LivingEntity) (Object) this).getAttributeValue(Attributes.FLYING_SPEED) * PodSpeedBoost.POD_HORIZONTAL_FACTOR;
            if (horizontal <= 0.0D)
            {
                return vertical;
            }
            return Math.copySign(horizontal, vertical);
        }
        catch (Throwable t)
        {
            // DMZ/vanilla shape shift: fall back to DMZ's own vertical rather than risk a bad value.
            return vertical;
        }
    }
}
