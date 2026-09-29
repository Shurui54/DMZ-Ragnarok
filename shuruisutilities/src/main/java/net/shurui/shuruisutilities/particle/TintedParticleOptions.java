package net.shurui.shuruisutilities.particle;

import java.util.Locale;
import java.util.function.Supplier;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Mth;

import org.joml.Vector3f;

/**
 * The options carried by both of the suite's tinted particles ({@link RgParticles}).
 *
 * <h2>Why this exists at all</h2>
 * 1.20.1 registers 95 particle types and only seven carry any options; in practice only {@code dust} and
 * {@code dust_color_transition} can be given an arbitrary colour. Both of those tie properties together that an
 * effect author needs apart:
 * <ul>
 *   <li>scale and lifetime are the SAME number ({@code lifetime = max(rand(8..40) * scale, 1)} and
 *       {@code quadSize *= 0.75 * scale}), so a small dust particle is necessarily a short lived one,</li>
 *   <li>the drawn colour is rolled down to 48 to 100 percent of what was authored by
 *       {@code DustParticleBase.randomizeColor}, so a picked hex renders darker and muddier,</li>
 *   <li>and there is no gravity control at all.</li>
 * </ul>
 * Everything that needed persistence therefore had to fall back on a fixed-colour type ({@code flame} is always
 * orange, {@code portal} is always purple), which is why the effect catalogue could not offer the same trail in
 * green, blue and red.
 *
 * <h2>What it guarantees</h2>
 * The four properties below are genuinely INDEPENDENT. Changing the lifetime does not change the size, changing
 * the size does not change how long it lives, the colour is drawn exactly as authored with no random darkening,
 * and gravity is its own number rather than a side effect of the type.
 *
 * <h2>Allocation</h2>
 * Immutable and meant to be built ONCE, when an effect is parsed or a cosmetic is equipped, then reused for every
 * spawn. These particles are spawned in trails behind moving players, so building a fresh options object per
 * particle would be a per-tick allocation in the hot path. Hoist it into a field.
 */
public class TintedParticleOptions implements ParticleOptions
{
    /** Smallest drawable size multiplier. Below this the quad is sub-pixel and just costs a draw. */
    public static final float MIN_SCALE = 0.05F;

    /** Largest size multiplier. 10 gives a quad of about two blocks across, well past anything the catalogue wants. */
    public static final float MAX_SCALE = 10.0F;

    /**
     * Longest life in ticks (30 seconds).
     *
     * <p>A cap, not a preference: lifetime is the one property a bad value turns into a leak, because a trail
     * spawning every tick with a 20 minute lifetime piles up thousands of live particles. Clamped here rather
     * than at the caller so a hand-edited effect file cannot do it either.
     */
    public static final int MAX_LIFETIME = 600;

    /** Gravity bound. Vanilla uses 0 for floating types and 1 for falling ones; negatives rise. */
    public static final float MAX_GRAVITY = 4.0F;

    /**
     * Reads the six numbers a {@code /particle} command or a spawn packet carries, in the order
     * {@link #writeToNetwork} writes them.
     *
     * <p>The reads are separate statements on purpose. Java evaluates arguments left to right so inlining them
     * into the constructor call would in fact work, but that is a language-lawyer guarantee to rest a wire
     * format on, and it reads as an accident.
     */
    public static final ParticleOptions.Deserializer<TintedParticleOptions> DESERIALIZER =
            new ParticleOptions.Deserializer<TintedParticleOptions>()
    {
        @Override
        public TintedParticleOptions fromCommand(ParticleType<TintedParticleOptions> type, StringReader reader)
                throws CommandSyntaxException
        {
            reader.expect(' ');
            float red = reader.readFloat();
            reader.expect(' ');
            float green = reader.readFloat();
            reader.expect(' ');
            float blue = reader.readFloat();
            reader.expect(' ');
            float scale = reader.readFloat();
            reader.expect(' ');
            int lifetime = reader.readInt();
            reader.expect(' ');
            float gravity = reader.readFloat();
            return new TintedParticleOptions(() -> type, red, green, blue, scale, lifetime, gravity);
        }

        @Override
        public TintedParticleOptions fromNetwork(ParticleType<TintedParticleOptions> type, FriendlyByteBuf buf)
        {
            float red = buf.readFloat();
            float green = buf.readFloat();
            float blue = buf.readFloat();
            float scale = buf.readFloat();
            int lifetime = buf.readVarInt();
            float gravity = buf.readFloat();
            return new TintedParticleOptions(() -> type, red, green, blue, scale, lifetime, gravity);
        }
    };

    /**
     * The type this instance belongs to.
     *
     * <p>A supplier rather than the resolved {@link ParticleType} because the same options class serves both
     * registered types, and a caller is allowed to declare a preset in a {@code static final} field that
     * initialises before the registry has fired. {@link net.minecraftforge.registries.RegistryObject} is itself
     * a Supplier, so {@code RgParticles.TINTED_GLOW} is passed straight in.
     */
    private final Supplier<? extends ParticleType<TintedParticleOptions>> type;

    private final float red;
    private final float green;
    private final float blue;
    private final float scale;
    private final int lifetime;
    private final float gravity;

    /**
     * @param type     which of the two registered types this is, glow or dust
     * @param red      0..1, drawn exactly, no randomisation
     * @param green    0..1
     * @param blue     0..1
     * @param scale    quad size multiplier, 1.0 is about a vanilla dust particle
     * @param lifetime ticks, independent of the scale
     * @param gravity  blocks per tick squared, applied as vanilla does ({@code yd -= 0.04 * gravity}); 0 floats,
     *                 1 falls like snow, negative rises
     */
    public TintedParticleOptions(Supplier<? extends ParticleType<TintedParticleOptions>> type, float red, float green,
            float blue, float scale, int lifetime, float gravity)
    {
        this.type = type;
        this.red = Mth.clamp(red, 0.0F, 1.0F);
        this.green = Mth.clamp(green, 0.0F, 1.0F);
        this.blue = Mth.clamp(blue, 0.0F, 1.0F);
        this.scale = Mth.clamp(scale, MIN_SCALE, MAX_SCALE);
        this.lifetime = Mth.clamp(lifetime, 1, MAX_LIFETIME);
        this.gravity = Mth.clamp(gravity, -MAX_GRAVITY, MAX_GRAVITY);
    }

    /**
     * Builds one from a packed {@code 0xRRGGBB}, which is the shape colours are authored in everywhere else in
     * the suite (the editors and the effect params store hex strings).
     */
    public static TintedParticleOptions of(Supplier<? extends ParticleType<TintedParticleOptions>> type, int rgb,
            float scale, int lifetime, float gravity)
    {
        return new TintedParticleOptions(type, (rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F, scale, lifetime, gravity);
    }

    /** The codec, bound to the type that owns it. Called once per registered type, from its constructor. */
    public static Codec<TintedParticleOptions> codec(ParticleType<TintedParticleOptions> owner)
    {
        return RecordCodecBuilder.create(instance -> instance.group(
                ExtraCodecs.VECTOR3F.fieldOf("color").forGetter(o -> new Vector3f(o.red, o.green, o.blue)),
                Codec.FLOAT.fieldOf("scale").forGetter(o -> o.scale),
                Codec.INT.fieldOf("lifetime").forGetter(o -> o.lifetime),
                Codec.FLOAT.fieldOf("gravity").forGetter(o -> o.gravity))
                .apply(instance, (colour, scale, lifetime, gravity) -> new TintedParticleOptions(() -> owner,
                        colour.x(), colour.y(), colour.z(), scale, lifetime, gravity)));
    }

    @Override
    public ParticleType<?> getType()
    {
        return this.type.get();
    }

    @Override
    public void writeToNetwork(FriendlyByteBuf buf)
    {
        buf.writeFloat(this.red);
        buf.writeFloat(this.green);
        buf.writeFloat(this.blue);
        buf.writeFloat(this.scale);
        buf.writeVarInt(this.lifetime);
        buf.writeFloat(this.gravity);
    }

    @Override
    public String writeToString()
    {
        return String.format(Locale.ROOT, "%s %.3f %.3f %.3f %.3f %d %.3f",
                BuiltInRegistries.PARTICLE_TYPE.getKey(this.getType()), this.red, this.green, this.blue, this.scale,
                this.lifetime, this.gravity);
    }

    public float red()
    {
        return this.red;
    }

    public float green()
    {
        return this.green;
    }

    public float blue()
    {
        return this.blue;
    }

    public float scale()
    {
        return this.scale;
    }

    public int lifetime()
    {
        return this.lifetime;
    }

    public float gravity()
    {
        return this.gravity;
    }
}
