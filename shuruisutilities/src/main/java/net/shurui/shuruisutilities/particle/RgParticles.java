package net.shurui.shuruisutilities.particle;

import net.minecraft.core.particles.ParticleType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The suite's own particle types: one soft round dot that can be given ANY colour, ANY size, ANY lifetime and
 * ANY gravity, with all four independent of each other.
 *
 * <h2>Why two types instead of one type with a blend flag</h2>
 * A translucent dot and an additive glow read as completely different effects (smoke versus fire), and both are
 * wanted. Either the options carry a boolean and one particle picks its render type from it, or two types are
 * registered. Two types won, for three reasons:
 * <ul>
 *   <li>the effect catalogue names a particle by id, so an author swaps {@code tinted_dust} for
 *       {@code tinted_glow} and every other parameter stays put. A boolean would have to be threaded through
 *       the effect params, the codec, the command syntax and the wire format to say the same thing;</li>
 *   <li>the two want different lighting. The glow is drawn full bright, because a light emitting mote that goes
 *       dark in a cave is wrong; the dust takes the light at its position, because a drifting speck that glows
 *       in the dark is equally wrong. That is a property of the render, not of the author's intent;</li>
 *   <li>the cost is one registry entry and one three line JSON. They share the sprite, the particle class, the
 *       options class and the codec, so there is nothing to keep in sync.</li>
 * </ul>
 *
 * <h2>Registration</h2>
 * Particle TYPES are a common registry and must exist on both sides, so this register is attached to the mod bus
 * from {@code ShuruisUtilities}'s constructor with everything else. The PROVIDER that turns options into a drawn
 * particle is client only and lives in
 * {@code net.shurui.shuruisutilities.client.particle.RgParticleClientBusEvents}.
 *
 * <p>Note the ids land in the {@code dmz_ragnarok} namespace, because {@link ShuruisUtilities#MODID} is
 * {@code dmz_ragnarok} since the merge. That is also why the sprite and the two particle descriptions live under
 * {@code assets/dmz_ragnarok/}: for particles the asset path is not a choice, the client derives it from the
 * registry id.
 */
public final class RgParticles
{
    private RgParticles()
    {
    }

    public static final DeferredRegister<ParticleType<?>> REGISTER =
            DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, ShuruisUtilities.MODID);

    /**
     * Translucent soft dot, lit by the world. Smoke, dust, petals, sparkles that are not meant to emit light.
     *
     * <p>Description: {@code assets/dmz_ragnarok/particles/tinted_dust.json}.
     */
    public static final RegistryObject<TintedParticleType> TINTED_DUST =
            REGISTER.register("tinted_dust", TintedParticleType::new);

    /**
     * Additive soft dot, drawn full bright and after everything else. Fire, energy, embers, ki. Additive means
     * overlapping particles pile up towards white, which is what makes a dense trail read as hot.
     *
     * <p>Description: {@code assets/dmz_ragnarok/particles/tinted_glow.json}.
     */
    public static final RegistryObject<TintedParticleType> TINTED_GLOW =
            REGISTER.register("tinted_glow", TintedParticleType::new);

    /**
     * A translucent dot from a packed {@code 0xRRGGBB}.
     *
     * <p>Build these ONCE per effect and keep them. They are immutable, so one instance serves every spawn; a
     * fresh one per particle would be an allocation per tick per wearer.
     */
    public static TintedParticleOptions dust(int rgb, float scale, int lifetime, float gravity)
    {
        return TintedParticleOptions.of(TINTED_DUST, rgb, scale, lifetime, gravity);
    }

    /** An additive glow dot from a packed {@code 0xRRGGBB}. Same hoisting rule as {@link #dust}. */
    public static TintedParticleOptions glow(int rgb, float scale, int lifetime, float gravity)
    {
        return TintedParticleOptions.of(TINTED_GLOW, rgb, scale, lifetime, gravity);
    }
}
