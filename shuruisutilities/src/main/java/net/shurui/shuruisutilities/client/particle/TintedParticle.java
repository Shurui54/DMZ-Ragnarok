package net.shurui.shuruisutilities.client.particle;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.LightTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.particle.TintedParticleOptions;

/**
 * The drawn half of the suite's tinted particle. One soft round dot, in the colour, size, lifetime and gravity
 * the options asked for, with those four kept independent of each other.
 *
 * <h2>What it deliberately does NOT do, and why</h2>
 * <ul>
 *   <li><b>No colour randomisation.</b> {@code DustParticleBase} rolls every channel down to between 48 and 100
 *       percent of the authored value, which is why a colour picked off a swatch renders muddy. The hex the
 *       author chose is the colour that is drawn.</li>
 *   <li><b>No size ramp.</b> {@code DustParticleBase.getQuadSize} grows the quad over the first thirty-second of
 *       its life, and its base size carries vanilla's random 0.5 to 1.0 jitter. Both are dropped: scale 1.0 is
 *       one size, every time, so a trail looks like a trail and not like static.</li>
 *   <li><b>No lifetime derived from scale.</b> That coupling is the whole reason this class exists.</li>
 *   <li><b>No physics.</b> {@code hasPhysics = false}, so {@code move} skips the voxel collision sweep. These
 *       are cosmetic motes spawned in trails behind moving players, they have no business resting on the floor,
 *       and collision is the single most expensive thing a particle can do per tick. If an effect ever genuinely
 *       needs a landing ember, that is a fifth authored property, added on purpose, not an accident of gravity
 *       being non-zero.</li>
 *   <li><b>No per-tick sprite lookup.</b> The description lists one texture, so the sprite is picked once at
 *       construction. Vanilla dust re-reads its sprite from the set on every tick.</li>
 * </ul>
 * Friction is left at the vanilla default (0.98), and it only matters if the spawner passes a velocity at all.
 *
 * <h2>Velocity</h2>
 * The four argument {@code Particle} constructor is used and the speed is set afterwards, because the six
 * argument one does not take the velocity it is given: it adds up to 0.4 of jitter per axis, renormalises the
 * vector to a random length and adds an upward 0.1. Here the three doubles passed to {@code addParticle} mean
 * exactly what they say, which matters when a trail wants its motes to drift with the wearer.
 */
@OnlyIn(Dist.CLIENT)
public class TintedParticle extends TextureSheetParticle
{
    /** Quad half extent at scale 1.0. Chosen to sit in the same range as a vanilla dust particle. */
    private static final float BASE_QUAD_SIZE = 0.1F;

    /** The last quarter of the life is spent fading out, so nothing in a trail ever pops out of existence. */
    private static final float FADE_FRACTION = 0.25F;

    private final ParticleRenderType renderType;
    private final boolean fullBright;

    /** Ticks of remaining life at which the fade starts, and the reciprocal of it, precomputed. */
    private final int fadeTicks;
    private final float fadeStep;

    protected TintedParticle(ClientLevel level, double x, double y, double z, double xd, double yd, double zd,
            TintedParticleOptions options, SpriteSet sprites, ParticleRenderType renderType, boolean fullBright)
    {
        super(level, x, y, z);
        this.setParticleSpeed(xd, yd, zd);

        this.renderType = renderType;
        this.fullBright = fullBright;

        this.rCol = options.red();
        this.gCol = options.green();
        this.bCol = options.blue();
        this.quadSize = BASE_QUAD_SIZE * options.scale();
        this.lifetime = options.lifetime();
        this.gravity = options.gravity();
        this.hasPhysics = false;

        // The collision box is unused (no physics) but the frustum cull reads it, so keep it in step with the
        // quad or a large particle is culled early at the edge of the screen.
        this.setSize(0.2F * options.scale(), 0.2F * options.scale());

        this.fadeTicks = Math.max(1, Math.round(this.lifetime * FADE_FRACTION));
        this.fadeStep = 1.0F / this.fadeTicks;

        this.pickSprite(sprites);
    }

    @Override
    public void tick()
    {
        super.tick();
        if (!this.removed)
        {
            int remaining = this.lifetime - this.age;
            this.alpha = remaining >= this.fadeTicks ? 1.0F : remaining * this.fadeStep;
        }
    }

    @Override
    public ParticleRenderType getRenderType()
    {
        return this.renderType;
    }

    @Override
    protected int getLightColor(float partialTick)
    {
        // A glow mote emits its own light, so it must not go dark in a cave; a dust mote is lit by the world.
        return this.fullBright ? LightTexture.FULL_BRIGHT : super.getLightColor(partialTick);
    }

    /**
     * One provider class for both registered types. The render type and the lighting are baked in when the
     * provider is built, once per type at client start, so nothing is branched per particle.
     */
    @OnlyIn(Dist.CLIENT)
    public static class Provider implements ParticleProvider<TintedParticleOptions>
    {
        private final SpriteSet sprites;
        private final ParticleRenderType renderType;
        private final boolean fullBright;

        public Provider(SpriteSet sprites, ParticleRenderType renderType, boolean fullBright)
        {
            this.sprites = sprites;
            this.renderType = renderType;
            this.fullBright = fullBright;
        }

        @Override
        public Particle createParticle(TintedParticleOptions options, ClientLevel level, double x, double y,
                double z, double xd, double yd, double zd)
        {
            return new TintedParticle(level, x, y, z, xd, yd, zd, options, this.sprites, this.renderType,
                    this.fullBright);
        }
    }
}
