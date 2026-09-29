package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.init.entities.ki.AbstractKiProjectile;

/**
 * Makes DMZ's big offensive ki BALLS first-class beam-clash participants, so a planet-buster shot can be contested.
 *
 * <p>(The class is still named "GiantBall" for historical reasons and to keep its {@code mixins.shuruisutilities.json}
 * binding stable; it now promotes SEVERAL ball render types, not just the giant ball. Renaming the class would mean
 * editing the mixin config entry too, and the safe course for a DMZ-targeting mixin is to leave a working binding
 * alone, so the name stays and this note stands in for it.)</p>
 *
 * <p>DMZ's {@code BeamClashManager} (FORGE bus, onLevelTick END) only keeps projectiles whose
 * {@code getClashRole() == MAJOR && isClashableBeam() && !isClashLocked()}, then pairs any two whose headings oppose
 * (direction dot below -0.3) and whose segments overlap. KiWave is always MAJOR and KiLaser is MAJOR at render type 1,
 * but {@code KiBlastEntity.getClashRole()} hard-codes {@code renderType == 0 ? MINOR : NONE}, so every offensive ball
 * reports NONE and the detector drops it. We flip the offensive-ball render types to MAJOR at the HEAD of the override
 * so a fired ball qualifies.</p>
 *
 * <p><b>Which render types.</b> The KiBlastEntity render-type table is: 0 small, 1 normal, 2 large blast, 3 inverted,
 * 4 souls, 5 SPIRIT BOMB (genki), 6 supernova, 7 death ball, 8 sokidan, 9 volley, 10 air volley, 11 fake moon. We
 * promote exactly {@link #SU_MAJOR_RENDER_TYPES} = 2 (large blast), 5 (spirit bomb), 6 (supernova) and 7 (death ball):
 * these are the single-projectile world-enders a player fires at a planet. We deliberately do NOT promote 0 (that is
 * the MINOR small blast DMZ already handles), nor 8/9/10/11 (sokidan, the two volleys and the fake moon, which are not
 * clash attacks). This is why the planet buster never triggered on a SPIRIT BOMB (render type 5): the old code promoted
 * only render type 2, so a fired genki was never MAJOR, never clashable, and the manager never paired it.</p>
 *
 * <p>Nothing else needs overriding. {@code isClashableBeam()} (MAJOR and firing) then goes true on its own once the
 * ball is fired, and it stays NONE while charging. The geometry works with a ball's zero beam length because
 * {@code beamsClash} clamps the segment to {@code Math.max(0.1f, length)} and its overlap threshold is
 * {@code (sizeA + sizeB) * 1.0 + 1.5}, which a large ball's size dominates. The ball's heading is meaningful once
 * fired (shoot() sets yaw/pitch from the trajectory), so the opposition dot test resolves correctly.</p>
 *
 * <h2>Holding a clash-locked ball still, without self-detonating it</h2>
 *
 * <p>DMZ's clash system was written for BEAMS, which are anchored to their caster. {@code BeamClash.tick()} freezes
 * each {@code Mob} owner and drives the timing meter, but it never zeroes the PROJECTILE's velocity, and
 * {@code AbstractKiProjectile.m_8119_()} unconditionally advances position by {@code getDeltaMovement()} every tick. A
 * beam does not care; a ball does: it keeps the forward momentum it was fired with for the whole clash (up to 600
 * ticks), so two locked balls drift toward and through each other instead of holding the struggle where they met. We
 * fix that here for every promoted render type.</p>
 *
 * <p><b>The self-destruct trap.</b> {@code KiBlastEntity.onKiTick} contains, for render types 5 and 6 only, a check on
 * a 20-tick cadence: if {@code getDeltaMovement().lengthSqr() < 0.01} it calls {@code explodeAndDie()} and returns. So
 * a firing spirit bomb or supernova whose speed drops below 0.1 blocks/tick SELF-DESTRUCTS within 20 ticks. Zeroing
 * such a ball's velocity to hold it (what the old code did for the giant ball) would blow it up before or during the
 * clash. So the hold is now SPLIT by whether the type self-destructs (see {@link #SU_SELF_DESTRUCT_RENDER_TYPES}):</p>
 * <ul>
 *   <li><b>Types 5 and 6 (spirit bomb, supernova):</b> we do NOT zero the velocity. On the first locked tick we
 *   capture an ANCHOR position, then every locked tick (at {@code tick()} HEAD, before {@code super.tick()} applies the
 *   delta) we {@code setPos(anchor)} and set a small non-zero velocity ({@link #SU_SELF_DESTRUCT_HOLD_SPEED}) along the
 *   ball's preserved clash heading. {@code super.tick()} then advances the ball by that tiny delta, but the NEXT tick
 *   re-pins it to the anchor, so it visually holds station while its speed never falls below the self-destruct
 *   threshold. {@code lengthSqr} is {@code 0.15 * 0.15 = 0.0225}, comfortably above the {@code 0.01} floor.</li>
 *   <li><b>Types 2 and 7 (large blast, death ball):</b> these have NO low-speed self-destruct path, so the simplest
 *   hold is best: zero the velocity. A zero delta means {@code super.tick()} moves the ball nowhere, so it holds
 *   exactly in place with no anchor bookkeeping.</li>
 * </ul>
 *
 * <p>A zero (or tiny) velocity does not re-accelerate or re-aim the ball: {@code applyHomingSteering()} early-returns
 * below 1.0E-4 speed, and {@code ProjectileUtil.rotateTowardsMovement} only rewrites the LIVE yaw/pitch, never the
 * stored {@code getClashYaw()/getClashPitch()} we release from, so the fired heading survives the freeze intact.</p>
 *
 * <p><b>Restoring the WINNER.</b> {@code BeamClash.resolve()} kills the loser and, on the winner, calls
 * {@code clearClashLock()} and {@code setMaxLife(now + 60)} so it has a short breakthrough window. On the falling edge
 * (was locked last tick, no longer locked, still firing and not removed) we clear the anchor and rebuild the velocity
 * from the preserved heading, {@code directionFromRotation(pitch, yaw)} scaled by {@code getKiSpeed()}, exactly as
 * {@code shoot()} first fired it, for ALL promoted types. The ball resumes normal travel toward its target.
 * {@code dissolve()} clears both locks without a kill, and both balls resume travel through the same edge, which is
 * correct. (The planet-buster module then keeps re-extending the winner's life every server tick, so the 60-tick
 * window is never the limiting factor for a slow 0.5-speed spirit bomb.)</p>
 *
 * <p>Ball-versus-beam is now a possible pairing and needs no special handling: this mixin only targets
 * {@code KiBlastEntity} and only acts on the promoted ball render types, so a beam (a separate {@code KiWaveEntity} /
 * {@code KiLaserEntity}, owner-anchored anyway) is untouched, while the ball holds and then breaks through on its own.
 * Ordinary blasts (types 0, 1, 3, 4 and the un-promoted 8..11) are never affected.</p>
 *
 * <p>{@code remap = false}: the {@code @Mixin} target and {@code getClashRole} resolve against DMZ's own (non-Mojmap)
 * names, matching SU's other DMZ mixins. The {@code m_8119_} injection carries {@code remap = true} because
 * {@code tick()} is a vanilla override whose name differs between dev (Mojmap) and prod (SRG); the refmap maps it.
 * {@code require = 0} on both injections: degrade to a no-op if DMZ renames or reshapes either method in a future
 * version, so a mapping drift can never crash the client the way a hard-required DMZ mixin would.</p>
 */
@Mixin(targets = "com.dragonminez.common.init.entities.ki.KiBlastEntity", remap = false)
public abstract class MixinDmzGiantBallClash
{
    // Render types promoted to MAJOR so BeamClashManager will pair them: 2 large blast, 5 spirit bomb (genki),
    // 6 supernova, 7 death ball. NOT 0 (the MINOR small blast DMZ already classifies), NOT 8/9/10/11 (sokidan, the two
    // volleys, the fake moon). These are the single-shot world-enders a player fires at a planet.
    @Unique
    private static final Set<Integer> SU_MAJOR_RENDER_TYPES = Set.of(2, 5, 6, 7);

    // Render types whose KiBlastEntity.onKiTick SELF-DESTRUCTS the blast on a 20-tick cadence if its speed drops below
    // 0.1 (getDeltaMovement().lengthSqr() < 0.01): 5 spirit bomb and 6 supernova. A locked ball of these types must be
    // held WITHOUT zeroing its velocity, or it detonates itself mid-clash. Every other promoted type may be held by
    // the simple zero-velocity freeze.
    @Unique
    private static final Set<Integer> SU_SELF_DESTRUCT_RENDER_TYPES = Set.of(5, 6);

    // The tiny non-zero speed, in blocks per tick, we hold a self-destruct-type ball at while it is clash-locked. It is
    // above the self-destruct floor of sqrt(0.01) = 0.1 with margin (0.15^2 = 0.0225 >= 0.01), and small enough that
    // the per-tick re-pin to the anchor keeps the ball visually on station.
    @Unique
    private static final double SU_SELF_DESTRUCT_HOLD_SPEED = 0.15;

    // DIAGNOSTIC latch (JOB 2, part 1): flipped true the first time this mixin actually promotes a ball to MAJOR at
    // runtime. This is the single most decisive signal for the "the planet says it is defending itself and then nothing
    // happens" report: if the confirmation line below NEVER appears in the log, this mixin is not applying to DMZ's
    // KiBlastEntity at all (a mapping/refmap drift, or the config missing from the mixin list on this jar), and that is
    // the whole answer, because a ball that is never promoted to MAJOR is never a clash participant and can never pair.
    // Static so it latches once for the whole client run across every ball, and read/written atomically so two threads
    // classifying balls in the same instant still log exactly once.
    @Unique
    private static final AtomicBoolean SU_PROMOTION_LOGGED = new AtomicBoolean(false);

    // Tracks whether this ball was clash-locked on the previous tick, so we can detect the release edge and hand the
    // winner (or both dissolved balls) back their travel velocity exactly once.
    @Unique
    private boolean su$wasClashLocked = false;

    // The position a self-destruct-type ball is pinned to while it is clash-locked, captured on the first locked tick
    // and cleared on release. Null when not holding a self-destruct-type ball.
    @Unique
    private Vec3 su$clashAnchor = null;

    @Inject(method = "getClashRole", at = @At("HEAD"), cancellable = true, require = 0)
    private void su$giantBallIsMajor(CallbackInfoReturnable<AbstractKiProjectile.ClashRole> cir)
    {
        try
        {
            AbstractKiProjectile self = (AbstractKiProjectile) (Object) this;
            int renderType = self.getKiRenderType();

            // GOD OF DESTRUCTION BYPASS. A god who spends their entire destruction bar makes this shot
            // uncontestable: reporting NONE keeps it out of the clash detector altogether, so nothing can ever be
            // paired against it and it reaches the planet unopposed. Checked before the MAJOR promotion below,
            // because the promotion is exactly what would otherwise make it clashable.
            if (SU_MAJOR_RENDER_TYPES.contains(renderType)
                    && net.shurui.shuruisutilities.api.key.RoleHooks.get().clashBypass(self, self.getOwner()))
            {
                cir.setReturnValue(AbstractKiProjectile.ClashRole.NONE);
                return;
            }

            if (SU_MAJOR_RENDER_TYPES.contains(renderType))
            {
                // DIAGNOSTIC (JOB 2, part 1): confirm ONCE, at INFO, that the promotion really fired at runtime. This is
                // the proof the mixin is bound; its absence from the log is the proof it is not. Guarded on its own so a
                // logging hiccup can never stop the promotion itself, and latched so it prints exactly once per run.
                if (SU_PROMOTION_LOGGED.compareAndSet(false, true))
                {
                    try
                    {
                        LoggingHandler.sulog.info("[PlanetBuster] clash promotion active for render type {}", renderType);
                    }
                    catch (Throwable ignored)
                    {
                        // never let the confirmation log stop the promotion below; the latch is already set so we will
                        // not retry, which is fine: the promotion (the load-bearing part) still runs.
                    }
                }
                // Report MAJOR so the detector keeps it. isClashableBeam() still gates on isFiring(), so a ball that is
                // only charging does not clash; it becomes eligible the moment it is fired.
                cir.setReturnValue(AbstractKiProjectile.ClashRole.MAJOR);
            }
            // Any other render type: fall through to DMZ's own body (type 0 -> MINOR, else NONE).
        }
        catch (Throwable ignored)
        {
            // Never let this override break DMZ's clash-role resolution; on any error, keep DMZ's answer.
        }
    }

    @Inject(method = "tick", at = @At("HEAD"), require = 0, remap = true)
    private void su$holdClashLockedBall(CallbackInfo ci)
    {
        try
        {
            AbstractKiProjectile self = (AbstractKiProjectile) (Object) this;
            int renderType = self.getKiRenderType();
            if (!SU_MAJOR_RENDER_TYPES.contains(renderType))
            {
                // Not a promoted ball. Leave beams and ordinary blasts exactly as DMZ ticks them.
                return;
            }

            if (self.isClashLocked())
            {
                // In a clash: hold the ball where it met its opponent before super.tick() applies its delta, so two
                // locked balls hold the struggle instead of coasting through each other.
                if (SU_SELF_DESTRUCT_RENDER_TYPES.contains(renderType))
                {
                    // Spirit bomb / supernova: zeroing the velocity would trip the type 5/6 low-speed self-destruct.
                    // Pin to a captured anchor and leave a small non-zero velocity along the fired heading; the next
                    // tick re-pins, so it holds station while never dropping below the self-destruct speed floor.
                    if (this.su$clashAnchor == null)
                    {
                        this.su$clashAnchor = self.position();
                    }
                    self.setPos(this.su$clashAnchor.x, this.su$clashAnchor.y, this.su$clashAnchor.z);
                    Vec3 heading = Vec3.directionFromRotation(self.getClashPitch(), self.getClashYaw());
                    self.setDeltaMovement(heading.scale(SU_SELF_DESTRUCT_HOLD_SPEED));
                }
                else
                {
                    // Large blast / death ball: no low-speed self-destruct, so a plain zero-velocity freeze holds it
                    // exactly in place (a zero delta means super.tick() moves it nowhere).
                    self.setDeltaMovement(Vec3.ZERO);
                }
                this.su$wasClashLocked = true;
                return;
            }

            if (this.su$wasClashLocked)
            {
                // Lock just cleared this tick. resolve() kills the loser (it never ticks again), so a ball that reaches
                // here is a winner or a dissolved survivor. Drop the anchor and rebuild its fired velocity from the
                // heading the freeze preserved, so the planet-buster resumes toward its target rather than hanging.
                this.su$wasClashLocked = false;
                this.su$clashAnchor = null;
                if (self.isFiring() && !self.isRemoved())
                {
                    Vec3 heading = Vec3.directionFromRotation(self.getClashPitch(), self.getClashYaw());
                    self.setDeltaMovement(heading.scale(self.getKiSpeed()));
                }
            }
        }
        catch (Throwable ignored)
        {
            // Never let the hold logic break DMZ's ki tick; on any error, leave DMZ's motion untouched.
        }
    }
}
