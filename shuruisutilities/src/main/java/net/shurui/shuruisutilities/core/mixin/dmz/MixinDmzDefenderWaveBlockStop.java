package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.state.BlockState;

import net.shurui.shuruisutilities.combat.MeleeClashService;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

import com.dragonminez.common.init.entities.ki.KiWaveEntity;

/**
 * Stops the planet's answering wave from detonating against the planet it is defending.
 *
 * <h2>The bug this fixes</h2>
 * The planet beam clash never engaged. Five timelines all ended identically: the defending wave reported
 * {@code waveRemoval=DISCARDED} at {@code waveTick=1}, one tick after spawning, while every condition the pairing
 * turns on read correct (both beams MAJOR, clashable and firing, clash headings opposed at dot -1.000, segments
 * overlapping, owner present, alive and at full health). The clash was never failing to pair. Its defending side was
 * being thrown away before the pairing test could ever run.
 *
 * <p>{@code KiWaveEntity.tick()} ends with, in effect:
 * <pre>
 *   if (hit.getType() == BLOCK && state.getExplosionResistance(...) &gt;= 1000.0F) { speed = 0.0F; setKiSpeed(0.0F); }
 *   setBeamLength(length);
 *   damageEntitiesInBeam(...);
 *   if (speed &lt; 0.05F || tickCount &gt; getMaxLife()) explodeAndDie(...);   // explodeAndDie discards the entity
 * </pre>
 *
 * <p>A beam that runs into a block tough enough to stop it has its speed zeroed, and a beam with no speed left
 * detonates and removes itself on that same tick. That rule is right for a player's technique, and fatal here: the
 * defender is placed beside the planet and, by the aiming this clash depends on, its beam points INTO that planet. Its
 * very first raytrace therefore lands on planet blocks well past 1000 resistance, and the wave kills itself
 * immediately, every single time, on the first tick it ever runs.
 *
 * <p>Nothing about max life, cast time, chunk tickets or owner health could have changed that, which is why none of
 * them did.
 *
 * <h2>The interception</h2>
 * A {@link Redirect} on the one {@code getExplosionResistance} INVOKE inside {@code tick}, returning 0 for a wave
 * carrying {@link SpaceKeys#DEFENDER_WAVE_MARKER}. Zero is below the 1000 threshold, so the speed-zeroing branch is
 * skipped, the wave keeps its speed, and it survives to be paired. Once DragonMineZ clash-locks it, tick takes an
 * early-return path that never reaches this code at all, so the redirect only matters for the handful of ticks
 * between spawning and locking.
 *
 * <p>Scoped by the marker, so it can only ever apply to a wave this suite spawned to defend a planet. Any other wave,
 * from any player or NPC, gets the real resistance value and DragonMineZ's behaviour byte for byte.
 *
 * <p>{@code remap = false} on the {@code @Mixin} because the target resolves against DragonMineZ's own names, matching
 * SU's other DMZ mixins; {@code remap = true} on the redirect because {@code tick} is a vanilla override; and
 * {@code remap = false} on the {@code @At} because {@code getExplosionResistance(BlockGetter, BlockPos, Explosion)} is
 * a Forge extension method that keeps its name. {@code require = 0} per the suite rule that a mixin into a DMZ class
 * degrades rather than crashes, and the marker read is wrapped so a drifted accessor falls back to the real value.
 */
@Mixin(targets = "com.dragonminez.common.init.entities.ki.KiWaveEntity", remap = false)
public abstract class MixinDmzDefenderWaveBlockStop
{
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;getExplosionResistance"
                            + "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/Explosion;)F",
                    remap = false),
            require = 0,
            remap = true)
    private float su$defenderWaveIgnoresPlanetBlocks(BlockState state, BlockGetter getter, BlockPos pos,
            Explosion explosion)
    {
        try
        {
            Object self = this;
            if (self instanceof KiWaveEntity wave && isSuPlumbingWave(wave.getTechniqueId()))
            {
                // below the 1000 threshold, so the wave is never stopped by the world around it.
                return 0.0F;
            }
        }
        catch (Throwable ignored)
        {
            // fail open: any drift falls through to the real resistance, so an ordinary beam is never altered.
        }
        return state.getExplosionResistance(getter, pos, explosion);
    }

    /**
     * Whether this is one of the suite's own invisible plumbing waves rather than somebody's technique.
     *
     * <p>Both of them exist only to be paired by DragonMineZ's clash detector and are never meant to interact with the
     * world: the planet defender's answering beam, and the pair spawned behind a melee clash. Neither is visible or
     * audible, so a wave of either kind stopping dead on terrain is never something a player could see coming, it just
     * ends the struggle they were in.
     *
     * <p>The melee pair matters as much as the planet one. Two players clashing beside a wall or close to the ground
     * would have one or both beams stopped on their first tick, which ends the struggle instantly and hands the
     * survivor a win they never fought for, and because the winner's dash cooldown is waived that resolves straight
     * into another dash and another clash. That is the loop behind a melee clash appearing to restart over and over.
     */
    private static boolean isSuPlumbingWave(String techniqueId)
    {
        return SpaceKeys.DEFENDER_WAVE_MARKER.equals(techniqueId)
                || MeleeClashService.CLASH_WAVE_MARKER.equals(techniqueId);
    }
}
