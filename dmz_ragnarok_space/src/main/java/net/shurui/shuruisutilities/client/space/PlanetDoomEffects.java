package net.shurui.shuruisutilities.client.space;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.space.PacketPlanetDoom;

/**
 * CLIENT-ONLY holder for the in-progress planet DOOM SEQUENCES, the visual half of a planet bust. The server drives the
 * timing and announces each phase START with a single {@link PacketPlanetDoom}; this class records that phase and runs a
 * purely LOCAL timer from the moment of receipt, so the animation costs no per-tick network traffic. {@link
 * SpaceBodyRenderer} reads this state every frame: during the RAMP it tints the still-drawn planet toward red, and during
 * the SHATTER (after the server has destroyed the planet and every client has stopped drawing it) it draws flying cube
 * shards from the snapshot kept here.
 *
 * <p>Never loaded on a dedicated server: it is reached only through {@link PacketPlanetDoom}'s DistExecutor client hand
 * off and from the client renderer, both {@link net.minecraftforge.api.distmarker.Dist#CLIENT} only.
 *
 * <p>THREADING. {@link #accept} runs on the client main thread (the packet handler enqueues its work there), and the
 * renderer reads on the client render thread; the map is a {@link ConcurrentHashMap} so the two never trip over a resize,
 * exactly like {@link SpaceBodyRenderer}'s resolved-texture cache. The stored start tick is read from the client level's
 * game time, the SAME clock the renderer measures progress against, so a phase's local timer and the draw agree.
 */
public final class PlanetDoomEffects
{
    private PlanetDoomEffects()
    {
    }

    // one running phase for one planet: the phase and its duration, the client game tick it began at (so progress is
    // (now - start) / duration), and the planet's position/radius/tint snapshot so the SHATTER can still place, size and
    // colour its debris after the planet itself is gone from the draw list.
    private record DoomState(Vec3 pos, float radius, int tint, byte phase, int durationTicks, double startTick)
    {
    }

    // keyed by planet id: a SHATTER for an id simply replaces that id's RAMP, so a planet is only ever in one phase.
    private static final Map<String, DoomState> ACTIVE = new ConcurrentHashMap<>();

    // distance, in blocks, at which the detonation sound reaches its quiet floor. Set to the name/destruction range so a
    // planet you were close enough to destroy is one you clearly hear die.
    private static final double DETONATION_FALLOFF_RANGE = 600.0;
    // the floor the detonation volume eases down to. Never zero: a planet coming apart should still be audible from far
    // enough away to see it happen.
    private static final double DETONATION_MIN_VOLUME = 0.35;

    // CLIENT: record a phase start. Reads the current client game tick as the phase's start so the renderer, which
    // measures against gameTime + partialTick, shares the exact clock. If there is no client level yet there is nothing
    // to animate against, so the phase is dropped (a joining client that misses a sequence is acceptable by design).
    public static void accept(String planetId, Vec3 pos, float radius, int tint, byte phase, int durationTicks)
    {
        if (planetId == null || planetId.isEmpty())
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }
        double start = mc.level.getGameTime();
        ACTIVE.put(planetId, new DoomState(pos, radius, tint, phase, durationTicks, start));

        if (phase == PacketPlanetDoom.PHASE_SHATTER)
        {
            playDetonation(mc, pos);
        }
    }

    // the bang that goes with the shatter. Played LOCALLY at the listener rather than positionally at the planet, because
    // a vanilla positional sound attenuates to silence within roughly sixteen blocks per unit of volume and these bodies
    // sit hundreds of blocks away, so a sound placed at the planet would simply never be heard. Instead we play it at the
    // player and scale the VOLUME by how far the planet is, which keeps the distance cue (a far world sounds fainter)
    // while staying audible at the ranges this actually happens at. Client side only, so no packet and no server sound
    // budget is involved.
    private static void playDetonation(Minecraft mc, Vec3 planetPos)
    {
        if (mc.player == null)
        {
            return;
        }
        double distance = mc.player.position().distanceTo(planetPos);
        // full volume close up, easing down to a floor so even a distant bust is still heard rather than vanishing.
        double falloff = 1.0 - Math.min(1.0, distance / DETONATION_FALLOFF_RANGE);
        float volume = (float) (DETONATION_MIN_VOLUME + (1.0 - DETONATION_MIN_VOLUME) * falloff);
        // a slightly low pitch so it reads as something enormous coming apart, not a creeper going off next to you.
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                SoundEvents.GENERIC_EXPLODE, SoundSource.BLOCKS, volume, 0.55F, false);
    }

    // the eased 0..1 RAMP factor for a planet id, or 0 if it has no active ramp. 0 means "draw the normal colour", so a
    // caller can multiply/blend by this with no branch. The raw progress is cubed so the ramp starts gently and the LAST
    // portion is by far the most intense: the planet only reads as critically over-charged in the final moments before it
    // blows, rather than fading up linearly. An expired ramp (progress >= 1) is pruned and reads as 0 until its SHATTER
    // arrives (or the sequence is dropped).
    public static double rampFactor(String planetId, double nowTick)
    {
        if (planetId == null)
        {
            return 0.0;
        }
        DoomState state = ACTIVE.get(planetId);
        if (state == null || state.phase != PacketPlanetDoom.PHASE_RAMP)
        {
            return 0.0;
        }
        double progress = (nowTick - state.startTick) / Math.max(1, state.durationTicks);
        if (progress <= 0.0)
        {
            return 0.0;
        }
        if (progress >= 1.0)
        {
            // the ramp has run out but the destroy/shatter has not landed yet this frame: hold at full red rather than
            // snapping back to the base colour, and leave the entry for the SHATTER to overwrite.
            return 1.0;
        }
        // ease-in (cubic): slow build, then a steep final surge so the instant before detonation is the reddest.
        return progress * progress * progress;
    }

    // a live SHATTER for a planet id ready to draw, or null. Carries the fields the renderer needs and the current 0..1
    // progress. An expired shatter (progress >= 1) is pruned here and returns null, so the renderer stops drawing it.
    public static Shatter shatterFor(String planetId, double nowTick)
    {
        DoomState state = ACTIVE.get(planetId);
        if (state == null || state.phase != PacketPlanetDoom.PHASE_SHATTER)
        {
            return null;
        }
        double progress = (nowTick - state.startTick) / Math.max(1, state.durationTicks);
        if (progress >= 1.0)
        {
            ACTIVE.remove(planetId);
            return null;
        }
        double p = Math.max(0.0, progress);
        return new Shatter(planetId, state.pos, state.radius, state.tint, p);
    }

    // every planet id currently in the SHATTER phase, so the renderer can draw shards for bodies that are no longer in
    // its derived draw list (the destroy already removed them). Also prunes any ramp that has run out with no shatter
    // following it (the sequence was dropped), so the map never leaks. Called once per frame.
    public static List<Shatter> activeShatters(double nowTick)
    {
        List<Shatter> out = new ArrayList<>();
        for (Iterator<Map.Entry<String, DoomState>> it = ACTIVE.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<String, DoomState> e = it.next();
            DoomState state = e.getValue();
            double progress = (nowTick - state.startTick) / Math.max(1, state.durationTicks);
            if (state.phase == PacketPlanetDoom.PHASE_SHATTER)
            {
                if (progress >= 1.0)
                {
                    it.remove();
                    continue;
                }
                out.add(new Shatter(e.getKey(), state.pos, state.radius, state.tint, Math.max(0.0, progress)));
            }
            else if (progress >= 2.0)
            {
                // a RAMP that has outlived twice its duration with no SHATTER to replace it: the sequence was dropped
                // (e.g. the destroy was refused by a race). Prune it so a stale ramp can never tint a respawned planet.
                it.remove();
            }
        }
        return out;
    }

    // clear all doom state, e.g. on leaving the space dimension, so nothing carries into a fresh visit. Cheap and safe
    // to call every frame from the renderer's dimension gate.
    public static void clear()
    {
        if (!ACTIVE.isEmpty())
        {
            ACTIVE.clear();
        }
    }

    /** A snapshot of a planet in the SHATTER phase for the renderer: where it was, its size and tint, and 0..1 progress. */
    public record Shatter(String planetId, Vec3 pos, float radius, int tint, double progress)
    {
    }
}
