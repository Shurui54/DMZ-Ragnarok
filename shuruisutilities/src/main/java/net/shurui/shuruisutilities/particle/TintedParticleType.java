package net.shurui.shuruisutilities.particle;

import com.mojang.serialization.Codec;

import net.minecraft.core.particles.ParticleType;

/**
 * The {@link ParticleType} both tinted particles are instances of. Two are registered ({@link RgParticles}); they
 * differ only in how the client draws them, so they share this class, the options class and the particle class.
 *
 * <p>{@code overrideLimiter} is FALSE on purpose: these are cosmetic trails, so a client that has turned its
 * particle setting down to Decreased or Minimal should see fewer of them, exactly as it does for vanilla dust.
 * Only particles that carry information (block break markers, the shriek) override the limiter.
 */
public class TintedParticleType extends ParticleType<TintedParticleOptions>
{
    private final Codec<TintedParticleOptions> codec;

    public TintedParticleType()
    {
        super(false, TintedParticleOptions.DESERIALIZER);
        // Safe despite handing out `this` mid construction: the codec only dereferences the type when something
        // actually encodes or decodes, which is long after the registry has finished building this object.
        this.codec = TintedParticleOptions.codec(this);
    }

    @Override
    public Codec<TintedParticleOptions> codec()
    {
        return this.codec;
    }
}
