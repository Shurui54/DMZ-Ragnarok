package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The triggered ANIMATION carried by a {@link CosmeticDef} on a triggered {@link CosmeticSlot}. Plain data, so an
 * admin authors what plays and the client draws it with no build.
 *
 * <h2>Where the code / data line sits, same as {@link CosmeticEffect}</h2>
 * The PLAYBACK KINDS are Java: something has to spawn the particles and play the sound, and a server owner
 * inventing a new kind of visual from a text field is a scripting engine, not a feature. WHICH kind, the colours,
 * the radius, the duration and the two sounds are DATA. {@link #style} is an allow-list ({@link #STYLES}) for the
 * reason the effect record's TYPES is: a value with no Java behind it draws nothing and an admin cannot debug it.
 *
 * <h2>Rig or particles, both no entity</h2>
 * A {@link #STYLE_GEO} effect draws the real converted GeckoLib RIG (the pack's animated model) client side with no
 * entity at all, keyed by {@link #geoRig}; every other style is a particle composition (our tintable
 * {@code tinted_dust} / {@code tinted_glow}). A geo effect keeps a {@link #geoFallbackStyle} so it degrades to
 * particles when the rig cannot be drawn (over the client rig cap, rigs disabled, or the model missing), never to
 * silence. The five shipped Halloween animations are geo, with their converted rigs under
 * {@code geo/fx/cosmetic_anim/}. Both forms carry the matching in / out sound.
 *
 * <h2>One record, both directions</h2>
 * A single catalogue entry is equippable in any of the four triggered slots, so it carries BOTH an in and an out
 * sound and the client picks which by the trigger: JOIN and TP_ARRIVE play {@link #soundIn}, LEAVE and TP_DEPART
 * play {@link #soundOut}. The visual is the same either way (the particles read better both arriving and leaving).
 *
 * <p>Rides on {@link CosmeticDef}, so it is synced to every client through the catalogue packet and travels
 * across shards in the catalogue state sync with zero new transport. Only meaningful when the def's slot
 * {@link CosmeticSlot#triggered()}; on a worn slot it is inert.
 */
public class CosmeticAnimation
{
    /** The client presenters, picked by {@link #style}. An unknown value draws nothing (sound still plays). */
    public static final String STYLE_COLUMN = "column";

    public static final String STYLE_RING = "ring";

    public static final String STYLE_VORTEX = "vortex";

    public static final String STYLE_BURST = "burst";

    public static final String STYLE_GATE = "gate";

    /**
     * The real animated GeckoLib RIG (the ModelEngine art the pack ships), drawn client side with no entity. When
     * a def uses this style it names a rig with {@link #geoRig}; the in / out halves are the converted
     * {@code anim_in_<rig>} / {@code anim_out_<rig>} models under {@code geo/fx/cosmetic_anim/}. If the rig cannot
     * be drawn (no rig named, the simultaneous-rig cap is reached, or the client has geo drawing off) the effect
     * falls back to the particle presentation named by {@link #geoFallbackStyle}, so it is never silent.
     */
    public static final String STYLE_GEO = "geo";

    /** The allow-list. A style not here is refused by {@link #normalise()} back to {@link #STYLE_BURST}. */
    public static final List<String> STYLES =
            Arrays.asList(STYLE_COLUMN, STYLE_RING, STYLE_VORTEX, STYLE_BURST, STYLE_GATE, STYLE_GEO);

    /** The particle styles only, for {@link #geoFallbackStyle} (a geo effect cannot fall back to itself). */
    public static final List<String> PARTICLE_STYLES =
            Arrays.asList(STYLE_COLUMN, STYLE_RING, STYLE_VORTEX, STYLE_BURST, STYLE_GATE);

    /** Longest an animation may hold on a client, in ticks. 200 (10 s) is already generous; a bad value cannot leak. */
    public static final int MAX_DURATION = 200;

    public static final int MIN_RADIUS = 16;

    public static final int MAX_RADIUS = 96;

    public static final int DEFAULT_RADIUS = 48;

    /** Parked without being deleted, the same idea as {@link CosmeticDef#enabled}. */
    public boolean enabled = true;

    /** Which client presenter draws this. See {@link #STYLES}. */
    public String style = STYLE_BURST;

    /** Hex {@code #RRGGBB}. The main particle colour, drawn exactly by the tintable particle. */
    public String colorPrimary = "#FFFFFF";

    /** Hex. The accent particle colour. */
    public String colorSecondary = "#FFFFFF";

    /** Whether the particles are the ADDITIVE full-bright {@code tinted_glow} rather than the lit {@code tinted_dust}. */
    public boolean glow = true;

    /** How long the FX lives on the client, in ticks. Authoritative on the wire so a stale catalogue cannot leak one. */
    public int durationTicks = 40;

    /** How near an observer must be to receive and draw it, in blocks. Clamped {@link #MIN_RADIUS}..{@link #MAX_RADIUS}. */
    public int radius = DEFAULT_RADIUS;

    /** Particle count multiplier. 1.0 is the authored density; the operator's dial, no upper bound. */
    public float density = 1.0F;

    /** Sound id played on an ARRIVING trigger (JOIN, TP_ARRIVE). Blank is silent. */
    public String soundIn = "";

    /** Sound id played on a DEPARTING trigger (LEAVE, TP_DEPART). Blank is silent. */
    public String soundOut = "";

    public float soundVolume = 1.0F;

    public float soundPitch = 1.0F;

    // ---------------------------------------------------------------- geo rig (STYLE_GEO)

    /**
     * The rig base key when {@link #style} is {@link #STYLE_GEO}, e.g. {@code cadavercurse}. The client draws
     * {@code dmz_ragnarok:geo/fx/cosmetic_anim/anim_in_<key>.geo.json} for an arriving trigger and
     * {@code anim_out_<key>} for a departing one, with the matching texture and {@code actived} animation. Blank
     * means the geo style falls back to particles. Lower-case {@code [a-z0-9_]} only, sanitised in {@link #normalise()}.
     */
    public String geoRig = "";

    /** Uniform render scale for the rig. 1.0 is the authored size. Clamped {@code (0, 8]}. */
    public float geoScale = 1.0F;

    /** The particle style drawn when {@link #style} is {@link #STYLE_GEO} but the rig cannot be shown. */
    public String geoFallbackStyle = STYLE_BURST;

    /** Life in ticks of the arriving (in) rig, its {@code actived} clip length times twenty. Clamped 1..{@link #MAX_DURATION}. */
    public int geoInTicks = 40;

    /** Life in ticks of the departing (out) rig. */
    public int geoOutTicks = 40;

    public CosmeticAnimation()
    {
    }

    public CosmeticAnimation copy()
    {
        CosmeticAnimation c = new CosmeticAnimation();
        c.enabled = enabled;
        c.style = style;
        c.colorPrimary = colorPrimary;
        c.colorSecondary = colorSecondary;
        c.glow = glow;
        c.durationTicks = durationTicks;
        c.radius = radius;
        c.density = density;
        c.soundIn = soundIn;
        c.soundOut = soundOut;
        c.soundVolume = soundVolume;
        c.soundPitch = soundPitch;
        c.geoRig = geoRig;
        c.geoScale = geoScale;
        c.geoFallbackStyle = geoFallbackStyle;
        c.geoInTicks = geoInTicks;
        c.geoOutTicks = geoOutTicks;
        return c;
    }

    /** Fill in anything a hand-edited or older record left wrong, so nothing downstream has to guard for it. */
    public CosmeticAnimation normalise()
    {
        style = style == null ? "" : style.trim().toLowerCase(Locale.ROOT);
        if (!STYLES.contains(style))
            style = STYLE_BURST;
        colorPrimary = CosmeticEffect.normaliseHex(colorPrimary, "#FFFFFF");
        colorSecondary = CosmeticEffect.normaliseHex(colorSecondary, "#FFFFFF");
        soundIn = CosmeticEffect.normaliseResourceId(soundIn);
        soundOut = CosmeticEffect.normaliseResourceId(soundOut);
        if (durationTicks < 1)
            durationTicks = 1;
        if (durationTicks > MAX_DURATION)
            durationTicks = MAX_DURATION;
        if (radius < MIN_RADIUS)
            radius = MIN_RADIUS;
        if (radius > MAX_RADIUS)
            radius = MAX_RADIUS;
        if (!(density >= 0.0F) || Float.isInfinite(density))
            density = 1.0F;
        if (!(soundVolume >= 0.0F) || Float.isInfinite(soundVolume))
            soundVolume = 1.0F;
        if (soundVolume > 1.0F)
            soundVolume = 1.0F;
        if (!(soundPitch > 0.0F) || Float.isInfinite(soundPitch))
            soundPitch = 1.0F;
        if (soundPitch < 0.5F)
            soundPitch = 0.5F;
        if (soundPitch > 2.0F)
            soundPitch = 2.0F;
        geoRig = sanitiseRigKey(geoRig);
        geoFallbackStyle = geoFallbackStyle == null ? "" : geoFallbackStyle.trim().toLowerCase(Locale.ROOT);
        if (!PARTICLE_STYLES.contains(geoFallbackStyle))
            geoFallbackStyle = STYLE_BURST;
        if (!(geoScale > 0.0F) || Float.isInfinite(geoScale))
            geoScale = 1.0F;
        if (geoScale > 8.0F)
            geoScale = 8.0F;
        geoInTicks = clampDuration(geoInTicks);
        geoOutTicks = clampDuration(geoOutTicks);
        return this;
    }

    private static int clampDuration(int t)
    {
        if (t < 1)
            return 1;
        return Math.min(t, MAX_DURATION);
    }

    /** Reduce a rig key to lower-case {@code [a-z0-9_]}; anything else is dropped so it cannot escape the fixed path. */
    private static String sanitiseRigKey(String s)
    {
        if (s == null)
            return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++)
        {
            char ch = t.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')
                b.append(ch);
        }
        return b.toString();
    }

    /** Whether this effect should draw the real rig: the geo style with a rig named. */
    public boolean hasGeo()
    {
        return STYLE_GEO.equals(style) && geoRig != null && !geoRig.isBlank();
    }

    /** The effective PARTICLE style: the fallback style for a geo effect, otherwise the style itself. */
    public String particleStyle()
    {
        if (STYLE_GEO.equals(style))
            return geoFallbackStyle == null || geoFallbackStyle.isBlank() ? STYLE_BURST : geoFallbackStyle;
        return style;
    }

    /** Whether a trigger is an ARRIVING one (plays the in rig / in sound), as opposed to a departing one. */
    public static boolean arriving(CosmeticSlot trigger)
    {
        return trigger == CosmeticSlot.JOIN || trigger == CosmeticSlot.TP_ARRIVE || trigger == CosmeticSlot.RESPAWN;
    }

    /**
     * The authoritative life sent on the wire for a trigger. A geo effect uses its per-direction clip length so
     * neither the in nor the out rig is cut short or padded; everything else uses the flat {@link #durationTicks}.
     */
    public int durationFor(CosmeticSlot trigger)
    {
        if (hasGeo())
            return clampDuration(arriving(trigger) ? geoInTicks : geoOutTicks);
        return durationTicks;
    }

    /** Whether this can present something: enabled, and either a visual or a sound to play. */
    public boolean valid()
    {
        return enabled && ((style != null && STYLES.contains(style))
                || (soundIn != null && !soundIn.isBlank()) || (soundOut != null && !soundOut.isBlank()));
    }

    public int colorPrimaryRgb()
    {
        return CosmeticEffect.rgb(colorPrimary, 0xFFFFFF);
    }

    public int colorSecondaryRgb()
    {
        return CosmeticEffect.rgb(colorSecondary, 0xFFFFFF);
    }

    /** The sound to play for one trigger, or blank. Arriving triggers get the in sound, departing ones the out. */
    public String soundFor(CosmeticSlot trigger)
    {
        boolean arriving = trigger == CosmeticSlot.JOIN || trigger == CosmeticSlot.TP_ARRIVE
                || trigger == CosmeticSlot.RESPAWN;
        String s = arriving ? soundIn : soundOut;
        return s == null ? "" : s;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(enabled);
        buf.writeUtf(style == null ? "" : style);
        buf.writeUtf(colorPrimary == null ? "" : colorPrimary);
        buf.writeUtf(colorSecondary == null ? "" : colorSecondary);
        buf.writeBoolean(glow);
        buf.writeVarInt(durationTicks);
        buf.writeVarInt(radius);
        buf.writeFloat(density);
        buf.writeUtf(soundIn == null ? "" : soundIn);
        buf.writeUtf(soundOut == null ? "" : soundOut);
        buf.writeFloat(soundVolume);
        buf.writeFloat(soundPitch);
        buf.writeUtf(geoRig == null ? "" : geoRig);
        buf.writeFloat(geoScale);
        buf.writeUtf(geoFallbackStyle == null ? "" : geoFallbackStyle);
        buf.writeVarInt(geoInTicks);
        buf.writeVarInt(geoOutTicks);
    }

    public static CosmeticAnimation decode(FriendlyByteBuf buf)
    {
        CosmeticAnimation c = new CosmeticAnimation();
        c.enabled = buf.readBoolean();
        c.style = buf.readUtf();
        c.colorPrimary = buf.readUtf();
        c.colorSecondary = buf.readUtf();
        c.glow = buf.readBoolean();
        c.durationTicks = buf.readVarInt();
        c.radius = buf.readVarInt();
        c.density = buf.readFloat();
        c.soundIn = buf.readUtf();
        c.soundOut = buf.readUtf();
        c.soundVolume = buf.readFloat();
        c.soundPitch = buf.readFloat();
        c.geoRig = buf.readUtf();
        c.geoScale = buf.readFloat();
        c.geoFallbackStyle = buf.readUtf();
        c.geoInTicks = buf.readVarInt();
        c.geoOutTicks = buf.readVarInt();
        return c.normalise();
    }

    /**
     * Deterministic NBT for the cross-server state sync: fixed field order, floats as raw int bits so a value that
     * round-trips through a file and a wire cannot drift and move the content hash. Blank sounds are omitted so a
     * record that never had one and one whose sound was cleared hash the same.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putBoolean("enabled", enabled);
        t.putString("style", style == null ? "" : style);
        t.putString("colorPrimary", colorPrimary == null ? "" : colorPrimary);
        t.putString("colorSecondary", colorSecondary == null ? "" : colorSecondary);
        t.putBoolean("glow", glow);
        t.putInt("durationTicks", durationTicks);
        t.putInt("radius", radius);
        t.putInt("densityBits", Float.floatToIntBits(density));
        if (soundIn != null && !soundIn.isBlank())
            t.putString("soundIn", soundIn);
        if (soundOut != null && !soundOut.isBlank())
            t.putString("soundOut", soundOut);
        t.putInt("soundVolumeBits", Float.floatToIntBits(soundVolume));
        t.putInt("soundPitchBits", Float.floatToIntBits(soundPitch));
        // Geo keys are written ONLY for a geo effect, so every existing (particle) record hashes exactly as it did
        // before this field existed and the cross-shard content sync does not churn on the upgrade.
        if (STYLE_GEO.equals(style) || (geoRig != null && !geoRig.isBlank()))
        {
            t.putString("geoRig", geoRig == null ? "" : geoRig);
            t.putInt("geoScaleBits", Float.floatToIntBits(geoScale));
            t.putString("geoFallbackStyle", geoFallbackStyle == null ? "" : geoFallbackStyle);
            t.putInt("geoInTicks", geoInTicks);
            t.putInt("geoOutTicks", geoOutTicks);
        }
        return t;
    }

    public static CosmeticAnimation fromNbt(CompoundTag t)
    {
        CosmeticAnimation c = new CosmeticAnimation();
        c.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        c.style = t.getString("style");
        c.colorPrimary = t.getString("colorPrimary");
        c.colorSecondary = t.getString("colorSecondary");
        c.glow = !t.contains("glow") || t.getBoolean("glow");
        c.durationTicks = t.contains("durationTicks") ? t.getInt("durationTicks") : 40;
        c.radius = t.contains("radius") ? t.getInt("radius") : DEFAULT_RADIUS;
        c.density = t.contains("densityBits") ? Float.intBitsToFloat(t.getInt("densityBits")) : 1.0F;
        c.soundIn = t.getString("soundIn");
        c.soundOut = t.getString("soundOut");
        c.soundVolume = t.contains("soundVolumeBits") ? Float.intBitsToFloat(t.getInt("soundVolumeBits")) : 1.0F;
        c.soundPitch = t.contains("soundPitchBits") ? Float.intBitsToFloat(t.getInt("soundPitchBits")) : 1.0F;
        c.geoRig = t.getString("geoRig");
        c.geoScale = t.contains("geoScaleBits") ? Float.intBitsToFloat(t.getInt("geoScaleBits")) : 1.0F;
        c.geoFallbackStyle = t.contains("geoFallbackStyle") ? t.getString("geoFallbackStyle") : STYLE_BURST;
        c.geoInTicks = t.contains("geoInTicks") ? t.getInt("geoInTicks") : 40;
        c.geoOutTicks = t.contains("geoOutTicks") ? t.getInt("geoOutTicks") : 40;
        return c.normalise();
    }
}
