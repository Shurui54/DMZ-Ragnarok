package net.shurui.shuruisutilities.client.cosmetics.magic;

import java.util.Locale;
import java.util.Map;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEffect;
import net.shurui.shuruisutilities.particle.RgParticles;
import net.shurui.shuruisutilities.particle.TintedParticleOptions;

/**
 * The Java half of a MAGIC effect: it turns one {@link CosmeticEffect}'s data into particles at an anchor, every
 * client tick, for one worn slot on one player. Entirely client side; nothing here runs on a server.
 *
 * <h2>The slot owns the presentation, the effect owns the look</h2>
 * This is the presenter the effect record's own note describes. It reads the effect's authored
 * {@link CosmeticEffect#params} (the {@code halo.*}, {@code trail.*}, {@code motion*}, {@code particle2/3} and
 * {@code spread} vocabulary) through {@link CosmeticEffect#effectiveParams(String, boolean)}, so a per-slot override
 * and the crowd variant are already folded in before this reads a single value. Two presentations are built out of
 * that one vocabulary:
 * <ul>
 *   <li>a HALO: {@code halo.rate} particles a second orbiting {@code halo.radius} around the anchor, at
 *       {@code halo.orbitTicks} per revolution (0 emits in place, negative reverses), with an optional
 *       counter-rotating second ring. This is the classic unusual look for a hat, a back piece or a hand item,</li>
 *   <li>a TRAIL: one particle every {@code trail.spacing} blocks the anchor actually travels, so a sprinter and a
 *       walker leave a comparable line. This is what reads on a mount or a moving wearer.</li>
 * </ul>
 * Both run for every slot; a standing wearer shows the halo, a moving one also lays a trail, and the effect's own
 * numbers decide how much of each. The colours, particle ids, rates and spacings are all DATA, so an admin builds a
 * new look in the editor with no build.
 *
 * <h2>Where our tinted particles come in</h2>
 * A {@code minecraft:dust} reference is drawn through {@link RgParticles#dust} and a
 * {@code minecraft:dust_color_transition} through {@link RgParticles#glow}, so the authored hex renders exactly,
 * with the scale and lifetime kept independent (see {@link TintedParticleOptions}). Every other particle id is a
 * plain vanilla type drawn as itself, because {@code flame} is meant to be orange and {@code portal} purple: that is
 * what keeps the eighteen shipped effects looking like eighteen different things rather than eighteen coloured dots.
 *
 * <h2>Budget</h2>
 * Every spawn is checked against a shared {@link Budget} first, so a screen full of wearers cannot outrun the
 * per-tick particle cap the caller sets. When the budget is spent this simply stops emitting; it never throws.
 */
public final class MagicEffectPresenter
{
    /** A soft, small default when an effect names no scale. */
    private static final float DEFAULT_LIFETIME = 20.0F;

    /** The speed a converging particle drifts inward, blocks per tick. Small: it should read as a gather, not a shot. */
    private static final float CONVERGE_SPEED = 0.06F;

    /** Most particles a single trail may lay in one tick, so a teleport or a lag spike cannot dump a wall of them. */
    private static final int MAX_TRAIL_STEPS = 4;

    private MagicEffectPresenter()
    {
    }

    /** A per-tick particle allowance shared across every wearer the caller draws. */
    public static final class Budget
    {
        private int remaining;

        public Budget(int perTick)
        {
            this.remaining = Math.max(0, perTick);
        }

        /** Claim one particle. False when the allowance is spent, which stops the caller emitting. */
        public boolean take()
        {
            if (remaining <= 0)
                return false;
            remaining--;
            return true;
        }
    }

    /** Per (player, slot) emission state: the rate accumulators and the last trail position. Lives in the caller. */
    public static final class State
    {
        double haloAccum;
        double ring2Accum;
        int primaryCount;
        Vec3 lastFeet;
    }

    /**
     * Emit one tick of this effect at the given anchors.
     *
     * @param haloAnchor where the halo orbits (head top, behind the back, at the hand, or the mount centre)
     * @param trailFeet  the ground point the trail is laid from (the wearer's feet, or the mount's base)
     * @param crowded    whether to read the effect's reduced-density crowd variant
     */
    public static void emit(ClientLevel level, CosmeticEffect fx, String slotKey, boolean crowded, Vec3 haloAnchor,
            Vec3 trailFeet, long gameTime, State st, Budget budget, RandomSource rng)
    {
        if (level == null || fx == null || st == null || budget == null || !fx.valid())
            return;

        Map<String, String> p = fx.effectiveParams(slotKey, crowded);
        float scale = fx.effectiveScale(slotKey, crowded);
        int lifetime = fx.lifetimeTicks > 0 ? fx.lifetimeTicks : (int) DEFAULT_LIFETIME;
        float density = fx.density <= 0.0F ? 1.0F : fx.density;

        ParticleOptions primary = primaryOptions(fx, scale, lifetime);
        boolean note = pathOf(fx.particle).equals("note");
        boolean converge = "converge".equalsIgnoreCase(str(p, "motionMode", ""));

        float spread = f(p, "spread", 0.08F);
        float yOff = f(p, "halo.yOffset", 0.15F);
        float radius = f(p, "halo.radius", 0.30F);
        int orbitTicks = (int) f(p, "halo.orbitTicks", 0.0F);

        // ---- halo (and optional second ring) ----
        float haloRate = f(p, "halo.rate", 4.0F) * density;
        st.haloAccum += haloRate / 20.0;
        while (st.haloAccum >= 1.0)
        {
            st.haloAccum -= 1.0;
            if (!budget.take())
            {
                st.haloAccum = 0.0;
                break;
            }
            spawnRing(level, fx, p, primary, haloAnchor, radius, orbitTicks, yOff, spread, gameTime, note, converge,
                    scale, lifetime, rng);
        }

        float ring2Radius = f(p, "halo.ring2Radius", 0.0F);
        if (ring2Radius > 0.0F)
        {
            int ring2Orbit = (int) f(p, "halo.ring2OrbitTicks", 0.0F);
            st.ring2Accum += (haloRate * 0.5F) / 20.0;
            while (st.ring2Accum >= 1.0)
            {
                st.ring2Accum -= 1.0;
                if (!budget.take())
                {
                    st.ring2Accum = 0.0;
                    break;
                }
                spawnRing(level, fx, p, primary, haloAnchor, ring2Radius, ring2Orbit, yOff, spread, gameTime, note,
                        false, scale, lifetime, rng);
            }
        }

        // ---- trail: one particle per trail.spacing blocks actually travelled ----
        if (st.lastFeet == null)
        {
            st.lastFeet = trailFeet;
        }
        else
        {
            float spacing = f(p, "trail.spacing", 0.70F);
            if (spacing <= 0.05F)
                spacing = 0.70F;
            float trailY = f(p, "trail.yOffset", 1.0F);
            float jitter = f(p, "trail.jitter", 0.15F);
            double dx = trailFeet.x - st.lastFeet.x;
            double dz = trailFeet.z - st.lastFeet.z;
            double moved = Math.sqrt(dx * dx + dz * dz);
            int steps = (int) Math.min(MAX_TRAIL_STEPS, Math.floor(moved / spacing));
            for (int i = 1; i <= steps; i++)
            {
                if (!budget.take())
                    break;
                double t = (double) i / (double) (steps + 1);
                double x = st.lastFeet.x + dx * t + (rng.nextDouble() - 0.5) * jitter;
                double y = trailFeet.y + trailY + (rng.nextDouble() - 0.5) * jitter;
                double z = st.lastFeet.z + dz * t + (rng.nextDouble() - 0.5) * jitter;
                double[] v = velocity(p, note, gameTime);
                level.addParticle(primary, x, y, z, v[0], v[1], v[2]);
                accents(level, fx, p, scale, lifetime, x, y, z, ++st.primaryCount, rng);
            }
            if (steps > 0)
                st.lastFeet = trailFeet;
            // Do not let a stationary wearer's lastFeet drift; only advance it when a full step was laid.
            else if (moved > spacing * (MAX_TRAIL_STEPS + 1))
                st.lastFeet = trailFeet; // a teleport: resync rather than spool up a wall on the next tick
        }
    }

    /** One halo particle on the ring at the current orbit angle, or a converging one when the effect asks. */
    private static void spawnRing(ClientLevel level, CosmeticEffect fx, Map<String, String> p, ParticleOptions primary,
            Vec3 anchor, float radius, int orbitTicks, float yOff, float spread, long gameTime, boolean note,
            boolean converge, float scale, int lifetime, RandomSource rng)
    {
        double x;
        double y;
        double z;
        double vx;
        double vy;
        double vz;
        if (converge)
        {
            float cr = f(p, "convergeRadius", 0.90F);
            double ax = rng.nextDouble() * 2.0 - 1.0;
            double ay = rng.nextDouble() * 2.0 - 1.0;
            double az = rng.nextDouble() * 2.0 - 1.0;
            double len = Math.sqrt(ax * ax + ay * ay + az * az);
            if (len < 1.0E-4)
            {
                ax = 1.0;
                ay = 0.0;
                az = 0.0;
                len = 1.0;
            }
            ax /= len;
            ay /= len;
            az /= len;
            x = anchor.x + ax * cr;
            y = anchor.y + yOff + ay * cr;
            z = anchor.z + az * cr;
            vx = -ax * CONVERGE_SPEED;
            vy = -ay * CONVERGE_SPEED;
            vz = -az * CONVERGE_SPEED;
        }
        else
        {
            double theta = orbitTicks != 0 ? (gameTime * (2.0 * Math.PI) / orbitTicks) : rng.nextDouble() * 2.0
                    * Math.PI;
            x = anchor.x + radius * Math.cos(theta) + (rng.nextDouble() - 0.5) * spread;
            y = anchor.y + yOff + (rng.nextDouble() - 0.5) * spread;
            z = anchor.z + radius * Math.sin(theta) + (rng.nextDouble() - 0.5) * spread;
            double[] v = velocity(p, note, gameTime);
            vx = v[0];
            vy = v[1];
            vz = v[2];
        }
        level.addParticle(primary, x, y, z, vx, vy, vz);
    }

    /** The three doubles handed to the spawn call: a note hue in the first, else the authored velocity. */
    private static double[] velocity(Map<String, String> p, boolean note, long gameTime)
    {
        if (note)
        {
            float sweep = f(p, "hue.sweepTicks", 120.0F);
            if (sweep < 1.0F)
                sweep = 120.0F;
            double hue = (gameTime % (long) sweep) / (double) sweep;
            return new double[] { hue, 0.0, 0.0 };
        }
        return new double[] { f(p, "motionX", 0.0F), f(p, "motionY", 0.0F), f(p, "motionZ", 0.0F) };
    }

    /** Emit the accent particles ({@code particle2} / {@code particle3}) at their cadence, at the given point. */
    private static void accents(ClientLevel level, CosmeticEffect fx, Map<String, String> p, float scale, int lifetime,
            double x, double y, double z, int primaryCount, RandomSource rng)
    {
        accent(level, fx, p, "particle2", "particle2Every", "color2Scale", scale, lifetime, x, y, z, primaryCount);
        accent(level, fx, p, "particle3", "particle3Every", null, scale, lifetime, x, y, z, primaryCount);
    }

    private static void accent(ClientLevel level, CosmeticEffect fx, Map<String, String> p, String idKey,
            String everyKey, String scaleKey, float baseScale, int lifetime, double x, double y, double z,
            int primaryCount)
    {
        String id = str(p, idKey, "");
        if (id.isBlank())
            return;
        int every = (int) f(p, everyKey, 0.0F);
        if (every <= 0 || primaryCount % every != 0)
            return;
        float accentScale = scaleKey == null ? baseScale : baseScale * f(p, scaleKey, 1.0F);
        ParticleOptions opt = particleFor(id, fx.colorSecondaryRgb(), accentScale, lifetime, true);
        if (opt != null)
            level.addParticle(opt, x, y, z, 0.0, 0.0, 0.0);
    }

    /** The primary particle options for this effect, built once per tick and reused for every spawn this tick. */
    private static ParticleOptions primaryOptions(CosmeticEffect fx, float scale, int lifetime)
    {
        return particleFor(fx.particle, fx.colorPrimaryRgb(), scale, lifetime, false);
    }

    /**
     * Resolve a particle id to drawable options. A dust reference is tinted through our own particles so the
     * authored colour renders exactly; a plain vanilla simple particle is used as itself; anything else falls back
     * to a tinted glow in the authored colour rather than drawing nothing.
     *
     * @param accent whether a dust reference should read as the softer dust (accent) or match the primary rule
     */
    private static ParticleOptions particleFor(String particleId, int rgb, float scale, int lifetime, boolean accent)
    {
        String path = pathOf(particleId);
        if (path.equals("dust"))
            return RgParticles.dust(rgb, scale, clampLife(lifetime), 0.0F);
        if (path.equals("dust_color_transition"))
            return RgParticles.glow(rgb, scale, clampLife(lifetime), 0.0F);
        ResourceLocation rl = ResourceLocation.tryParse(particleId);
        if (rl == null)
            return RgParticles.glow(rgb, scale, clampLife(lifetime), 0.0F);
        ParticleType<?> type = ForgeRegistries.PARTICLE_TYPES.getValue(rl);
        if (type instanceof ParticleOptions opt)
            return opt;
        // An option-carrying type we cannot construct blindly: draw a tinted mote in its colour instead of nothing.
        return RgParticles.glow(rgb, scale, clampLife(lifetime), 0.0F);
    }

    private static int clampLife(int lifetime)
    {
        return Mth.clamp(lifetime, 1, TintedParticleOptions.MAX_LIFETIME);
    }

    private static String pathOf(String particleId)
    {
        if (particleId == null)
            return "";
        ResourceLocation rl = ResourceLocation.tryParse(particleId.trim().toLowerCase(Locale.ROOT));
        return rl == null ? "" : rl.getPath();
    }

    private static float f(Map<String, String> p, String key, float fallback)
    {
        String v = p == null ? null : p.get(key);
        if (v == null || v.isBlank())
            return fallback;
        try
        {
            float parsed = Float.parseFloat(v.trim());
            return Float.isNaN(parsed) || Float.isInfinite(parsed) ? fallback : parsed;
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    private static String str(Map<String, String> p, String key, String fallback)
    {
        String v = p == null ? null : p.get(key);
        return v == null || v.isBlank() ? fallback : v.trim();
    }
}
