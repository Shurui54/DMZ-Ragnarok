package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * One MAGIC effect, as an admin authors it. A standalone record with its own id.
 *
 * <h2>PERSISTED NAME. NEVER RENAME THIS CLASS OR ITS FIELDS.</h2>
 * Instances live in {@code cosmetics.json} under the {@code effects} map and every Magic copy of a cosmetic
 * references one by {@link #id} ({@link CosmeticOwnership#effectId}, {@link EquippedCosmetic#effectId}).
 * Renaming the class changes the Gson shape of that file, renaming a field silently drops its value on the next
 * load, and renaming an id orphans every copy already minted in the world.
 *
 * <h2>The SLOT owns the presentation, the EFFECT owns the visual identity</h2>
 * Nothing here says what an effect looks like on a hat versus behind a runner. A presenter chosen by
 * {@link CosmeticSlot} does that: a halo at the attach bone for HEAD and BACK, a distance-keyed trail for BODY
 * and a mount, the halo parameters at the weapon for a ki weapon. That is the whole reason this record has no
 * per-slot block: {@link CosmeticSlot}'s own contract is that adding a slot costs ONE constant and one lang key,
 * and an effect that had to declare itself per slot would make every new slot an edit to every effect already
 * authored, with "presents as nothing, silently" as the failure mode for any that were missed.
 *
 * <p>{@link #slotOverrides} is the escape hatch that keeps that honest: an admin MAY say "on the body this uses
 * a quarter of the converge radius", but nobody HAS to, and an effect authored before a slot existed still works
 * on it from its base values. In the shipped catalogue two effects of eighteen carry one, which is the evidence
 * the model is the right way round.
 *
 * <h2>Where the line between code and data sits</h2>
 * The presenter is Java: something has to spawn the particle and drive the ring, and a server owner inventing a
 * new kind of visual from a text field is not a feature, it is a scripting engine. The particle, the colours,
 * the rates, the spacings and the sound are DATA, so an admin can build "green fiery trail" in the editor with
 * no build. The retired {@code particle_trail} / {@code aura_tint} / {@code glow} / {@code sound_loop} /
 * {@code fx_model} allow-list is gone from this record as a user-facing concept for that reason: it is now the
 * presenters' internal vocabulary, picked by the slot rather than typed by an admin.
 *
 * <h2>{@link #params} is a flat string map ON PURPOSE</h2>
 * A typed record per presenter would mean the editor, the Gson shape, the NBT codec and the wire format all grow
 * a branch per presenter, and a presenter added later would be a data migration. The presenter parses its own
 * keys and is the only thing that knows what they mean. {@link #paramFloat} exists so the common case is not
 * re-parsed by hand everywhere; note that some values are deliberately NOT numbers ({@code motionX} is the
 * string {@code hue} on the note-particle effect), so a fallback is never a bug.
 *
 * <p>The vocabulary the shipped catalogue is authored in is named in the {@code P_} constants below. They are
 * conventions, not a schema: an unknown key is kept and handed to the presenter, which is what lets a presenter
 * gain a parameter without touching this class.
 *
 * <h2>The crowd variant</h2>
 * {@code crowd.*} keys are a REDUCED-DENSITY version of the same effect, switched on by the client when it is
 * already drawing several. The presenter swaps values, so a player never sees their effect turn into a different
 * effect. {@link #effectiveParams} applies the swap; {@link #CROWD_TARGETS} is the mapping and
 * {@code crowd.scale} is the one that lands on the {@link #scale} field rather than on a param, because for dust
 * types cutting the scale also shortens the lifetime and so cuts the count for free.
 */
public class CosmeticEffect
{
    // The params vocabulary the shipped catalogue is authored in. See the class note: conventions, not a schema.

    /** Registry id of an accent particle emitted every {@link #P_PARTICLE2_EVERY} primaries. */
    public static final String P_PARTICLE2 = "particle2";

    public static final String P_PARTICLE2_EVERY = "particle2Every";

    /** Registry id of a second accent particle. */
    public static final String P_PARTICLE3 = "particle3";

    public static final String P_PARTICLE3_EVERY = "particle3Every";

    /** Scale for the accent particle when it is a dust type. */
    public static final String P_COLOR2_SCALE = "color2Scale";

    /** The three doubles handed to the spawn call. A velocity for most types, an inward offset for
     *  {@code enchant}, a hue for {@code note}, which is why these are strings and not floats. */
    public static final String P_MOTION_X = "motionX";

    public static final String P_MOTION_Y = "motionY";

    public static final String P_MOTION_Z = "motionZ";

    /** Random offset added to the spawn position, in blocks, per axis. */
    public static final String P_SPREAD = "spread";

    /** Halo presenter: particles per second while worn. */
    public static final String P_HALO_RATE = "halo.rate";

    /** Halo presenter: orbit radius in blocks from the attach point. */
    public static final String P_HALO_RADIUS = "halo.radius";

    /** Halo presenter: ticks per revolution. Negative reverses, 0 means emit in place. */
    public static final String P_HALO_ORBIT_TICKS = "halo.orbitTicks";

    /** Halo presenter: vertical offset from the attach point, in blocks. */
    public static final String P_HALO_Y_OFFSET = "halo.yOffset";

    /** Halo presenter: an optional second, counter-rotating ring. */
    public static final String P_HALO_RING2_RADIUS = "halo.ring2Radius";

    public static final String P_HALO_RING2_ORBIT_TICKS = "halo.ring2OrbitTicks";

    /** Trail presenter: blocks travelled per particle. The core dial, and the reason a sprinter and a walker
     *  leave a comparable line instead of the sprinter drowning the screen. */
    public static final String P_TRAIL_SPACING = "trail.spacing";

    /** Trail presenter: random offset around the path, in blocks. */
    public static final String P_TRAIL_JITTER = "trail.jitter";

    /** Trail presenter: height above the source's feet, in blocks. */
    public static final String P_TRAIL_Y_OFFSET = "trail.yOffset";

    /** Ticks between sound plays. An editor should floor this at 20: nothing may fire more than once a second. */
    public static final String P_SOUND_EVERY_TICKS = "sound.everyTicks";

    /** Every crowd key starts with this. */
    public static final String CROWD_PREFIX = "crowd.";

    /** The crowd key that lands on the {@link #scale} FIELD rather than on a param. */
    public static final String P_CROWD_SCALE = "crowd.scale";

    /**
     * Which base key each {@code crowd.*} key replaces.
     *
     * <p>Deliberately NOT a name transform. The crowd levers are per effect on purpose (cut the rate for the
     * count-driven effects, cut the SCALE for the fill-driven ones), so the mapping is an explicit table and a
     * crowd key with no entry here is left alone rather than guessed at.
     */
    public static final Map<String, String> CROWD_TARGETS;

    /** The value of {@code crowd.ring2} that switches the second halo ring off entirely. */
    public static final String CROWD_RING2_OFF = "off";

    static
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("crowd.haloRate", P_HALO_RATE);
        m.put("crowd.spacing", P_TRAIL_SPACING);
        m.put("crowd.particle2Every", P_PARTICLE2_EVERY);
        m.put("crowd.particle3Every", P_PARTICLE3_EVERY);
        m.put("crowd.soundEveryTicks", P_SOUND_EVERY_TICKS);
        CROWD_TARGETS = Collections.unmodifiableMap(m);
    }

    /**
     * Stable lowercase id, sanitised by {@link CosmeticDef#sanitizeId} so it can never contain a colon.
     *
     * <p>PERSISTED on every Magic ownership row and in every equipped slot, so renaming one orphans everybody
     * holding it: the copy keeps the old id and simply presents as nothing. A rename is a delete plus a new
     * entry, which at least makes the loss visible. Same rule, and the same reason, as {@link CosmeticDef#id}.
     *
     * <p>The no-colon guarantee is load-bearing a second time over in {@link CosmeticCatalog}, where stamp keys
     * are namespaced {@code fx:<id>}.
     */
    public String id = "";

    /** Shown on the crate reveal and in the wardrobe. Supports the suite's &amp; colour codes. */
    public String displayName = "New Effect";

    /**
     * Free text driving the crate reveal colour and the editor's grouping. The shipped catalogue uses
     * {@code subtle}, {@code standard} and {@code showpiece}.
     *
     * <p>Free text rather than an enum because it is presentation, not behaviour: nothing gates on it, a server
     * inventing a fourth band should not need a build, and an unknown value has an obvious fallback (no colour).
     */
    public String rarityLabel = "";

    /**
     * Registry id of the primary particle, for example {@code minecraft:flame}.
     *
     * <p>ANY namespace. The shipped catalogue is vanilla plus one DragonMineZ reference, but a tintable particle
     * of our own is coming, so nothing here allow-lists {@code minecraft:}. A missing namespace reads as
     * {@code minecraft:}, which is what a ResourceLocation does anyway.
     */
    public String particle = "";

    /**
     * Hex, {@code #RRGGBB}.
     *
     * <p>ONLY READ when {@link #particle} is a dust type ({@code dust}, {@code dust_color_transition}) or when a
     * presenter is spawning through a tinting helper. On every other particle it is documentation, so the editor
     * can draw a swatch of what that particle actually looks like. That is a property of vanilla, not a
     * shortcoming here: {@code flame} is always orange and {@code portal} is always purple.
     */
    public String colorPrimary = "#FFFFFF";

    /** Hex. The {@code to} colour of a {@code dust_color_transition}, or the accent particle's dust colour. */
    public String colorSecondary = "#FFFFFF";

    /**
     * Emission rate multiplier. 1.0 is the authored rate.
     *
     * <p>THE OPERATOR'S DIAL, not the author's. Every rate in the shipped catalogue is authored at 1.0 so a busy
     * server can thin the whole feature proportionally without anybody re-authoring anything.
     */
    public float density = 1.0F;

    /** Particle scale. Passed to the dust types and ignored by the rest, which size themselves. */
    public float scale = 1.0F;

    /**
     * DESCRIPTIVE. What the particle class will actually do, recorded so the editor and the budget table can
     * reason about the cost.
     *
     * <p>No vanilla particle takes a lifetime argument through the spawn call, and for dust the lifetime is
     * welded to the scale inside the particle class, so writing here changes nothing on screen. It is kept
     * because "how long does this stay up" is the single most useful number when judging an effect and it is
     * otherwise only discoverable by reading Minecraft's source.
     */
    public int lifetimeTicks = 0;

    /** Optional ambient sound id. Blank means silent, and blank is omitted from the persisted form entirely. */
    public String sound = "";

    public float soundVolume = 0.0F;

    public float soundPitch = 1.0F;

    /**
     * Parked without being deleted, the same idea as {@link CosmeticDef#enabled}.
     *
     * <p>A disabled effect keeps every copy that rolled it and simply stops being offered by a pool and stops
     * presenting, which is how a seasonal effect is taken down for a year rather than destroyed. Nothing deletes
     * an ownership row because of this.
     */
    public boolean enabled = true;

    /** Presenter-specific values. See the class note on why this is a flat string map. */
    public Map<String, String> params = new LinkedHashMap<>();

    /**
     * Per-slot parameter overrides, keyed by {@link CosmeticSlot#key}, then by param key.
     *
     * <p>Keys are NOT validated against the enum on purpose. The shipped catalogue already carries overrides for
     * {@code mount} and {@code ki_weapon}, which are slots the owner has named but that are not declared yet,
     * and dropping them at load would quietly destroy authored work that becomes correct the day the slot lands.
     * An override for a slot that never exists is inert.
     */
    public Map<String, Map<String, String>> slotOverrides = new LinkedHashMap<>();

    /**
     * The retired {@code effectType} key, read once from an older {@code cosmetics.json} and then dropped.
     *
     * <p>NOT a field anybody may use. It exists only so Gson has somewhere to put the old value while
     * {@link #normalise()} folds a legacy {@code particle_trail} record onto the identity fields. Gson omits
     * nulls, so the key disappears from the file on the next save. Nothing ever rendered one of these, so the
     * migration is a courtesy rather than a rescue, but the reader stays tolerant because a hand-edited file
     * outlives the code that wrote it. Delete this once no live server can still hold a pre-M1 catalogue.
     */
    private String effectType;

    public CosmeticEffect()
    {
    }

    public CosmeticEffect(String id)
    {
        this.id = id == null ? "" : id;
    }

    public CosmeticEffect copy()
    {
        CosmeticEffect c = new CosmeticEffect(id);
        c.displayName = displayName;
        c.rarityLabel = rarityLabel;
        c.particle = particle;
        c.colorPrimary = colorPrimary;
        c.colorSecondary = colorSecondary;
        c.density = density;
        c.scale = scale;
        c.lifetimeTicks = lifetimeTicks;
        c.sound = sound;
        c.soundVolume = soundVolume;
        c.soundPitch = soundPitch;
        c.enabled = enabled;
        c.params = new LinkedHashMap<>(params == null ? Map.of() : params);
        c.slotOverrides = new LinkedHashMap<>();
        if (slotOverrides != null)
            for (Map.Entry<String, Map<String, String>> e : slotOverrides.entrySet())
                c.slotOverrides.put(e.getKey(),
                        new LinkedHashMap<>(e.getValue() == null ? Map.of() : e.getValue()));
        return c;
    }

    /** Fill in anything a hand-edited or older file left null, so nothing downstream has to guard for it. */
    public CosmeticEffect normalise()
    {
        id = CosmeticDef.sanitizeId(id);
        if (displayName == null)
            displayName = "";
        if (rarityLabel == null)
            rarityLabel = "";
        rarityLabel = rarityLabel.trim();
        if (params == null)
            params = new LinkedHashMap<>();
        if (slotOverrides == null)
            slotOverrides = new LinkedHashMap<>();
        migrateLegacyType();
        particle = normaliseResourceId(particle);
        sound = normaliseResourceId(sound);
        colorPrimary = normaliseHex(colorPrimary, "#FFFFFF");
        colorSecondary = normaliseHex(colorSecondary, "#FFFFFF");
        // A negative or non-finite rate would emit nothing with no error anywhere to explain why. Corrected
        // rather than accepted. There is deliberately NO upper bound: density is the operator's dial and a
        // server that wants ten times the particles is entitled to ask for them.
        if (!(density >= 0.0F) || Float.isInfinite(density))
            density = 1.0F;
        if (!(scale > 0.0F) || Float.isInfinite(scale))
            scale = 1.0F;
        if (lifetimeTicks < 0)
            lifetimeTicks = 0;
        if (!(soundVolume >= 0.0F) || Float.isInfinite(soundVolume))
            soundVolume = 0.0F;
        if (!(soundPitch > 0.0F) || Float.isInfinite(soundPitch))
            soundPitch = 1.0F;
        // A blank key carries no meaning and a null value is not the same as an absent one to Gson, so both are
        // dropped here instead of being guarded for at every read.
        params.keySet().removeIf(k -> k == null || k.isBlank());
        params.values().removeIf(v -> v == null);
        Map<String, Map<String, String>> keptOverrides = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : slotOverrides.entrySet())
        {
            if (e.getKey() == null || e.getKey().isBlank() || e.getValue() == null)
                continue;
            Map<String, String> inner = new LinkedHashMap<>();
            for (Map.Entry<String, String> o : e.getValue().entrySet())
                if (o.getKey() != null && !o.getKey().isBlank() && o.getValue() != null)
                    inner.put(o.getKey(), o.getValue());
            if (!inner.isEmpty())
                keptOverrides.put(e.getKey().trim().toLowerCase(Locale.ROOT), inner);
        }
        slotOverrides = keptOverrides;
        return this;
    }

    /**
     * Fold a pre-M1 {@code { effectType, params }} record onto the identity fields, once, then forget it.
     *
     * <p>Keyed on the legacy field being PRESENT rather than on a version number, which is what makes it
     * idempotent: a record written by this class has no {@code effectType} at all, so running it on every load
     * and every decode costs nothing and can never overwrite authored values twice.
     */
    private void migrateLegacyType()
    {
        if (effectType == null || effectType.isBlank())
        {
            effectType = null;
            return;
        }
        // particle_trail was the only legacy type carrying anything worth keeping. Its particle and colour move
        // onto the identity fields and everything else stays in params, where a presenter can still read it.
        String legacyParticle = params.remove("particle");
        if (legacyParticle != null && !legacyParticle.isBlank() && (particle == null || particle.isBlank()))
            particle = legacyParticle;
        String legacyColor = params.remove("color");
        if (legacyColor != null && !legacyColor.isBlank())
            colorPrimary = legacyColor;
        // The type itself is kept as a param rather than discarded, so an admin can see what the record used to
        // be when they open it in the editor and find it presenting as a plain particle.
        params.putIfAbsent("legacyType", effectType);
        effectType = null;
    }

    /**
     * Whether this effect can actually present something.
     *
     * <p>Asks the record only. Whether a pool offers it, whether the module is on and whether the wearer owns a
     * Magic copy are three separate questions with three separate answers, and keeping them apart is what stops
     * a disabled effect deleting somebody's ownership row.
     */
    public boolean valid()
    {
        return enabled && id != null && !id.isBlank() && particle != null && !particle.isBlank()
                && ResourceLocation.tryParse(particle) != null;
    }

    /** One param, or the fallback when it is absent or blank. */
    public String param(String key, String fallback)
    {
        if (params == null || key == null)
            return fallback;
        String v = params.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** One param as a float, or the fallback. A non-numeric value is NOT an error: see the class note. */
    public float paramFloat(String key, float fallback)
    {
        String v = param(key, null);
        if (v == null)
            return fallback;
        try
        {
            float f = Float.parseFloat(v.trim());
            return Float.isNaN(f) || Float.isInfinite(f) ? fallback : f;
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    /** One param as an int, or the fallback. */
    public int paramInt(String key, int fallback)
    {
        String v = param(key, null);
        if (v == null)
            return fallback;
        try
        {
            return Integer.parseInt(v.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    public void setParam(String key, String value)
    {
        if (key == null || key.isBlank())
            return;
        if (params == null)
            params = new LinkedHashMap<>();
        if (value == null || value.isBlank())
            params.remove(key);
        else
            params.put(key, value);
    }

    /** The override block for one slot key, never null, never the live map. */
    public Map<String, String> slotOverride(String slotKey)
    {
        if (slotOverrides == null || slotKey == null)
            return new LinkedHashMap<>();
        Map<String, String> m = slotOverrides.get(slotKey.toLowerCase(Locale.ROOT));
        return m == null ? new LinkedHashMap<>() : new LinkedHashMap<>(m);
    }

    /** Set or clear one per-slot override. A blank value removes it, and an emptied block removes itself. */
    public void setSlotOverride(String slotKey, String key, String value)
    {
        if (slotKey == null || slotKey.isBlank() || key == null || key.isBlank())
            return;
        if (slotOverrides == null)
            slotOverrides = new LinkedHashMap<>();
        String k = slotKey.toLowerCase(Locale.ROOT);
        Map<String, String> m = slotOverrides.computeIfAbsent(k, x -> new LinkedHashMap<>());
        if (value == null || value.isBlank())
            m.remove(key);
        else
            m.put(key, value);
        if (m.isEmpty())
            slotOverrides.remove(k);
    }

    /**
     * The params a presenter should actually read: the base set, overlaid by this slot's overrides, then by the
     * crowd variant if the client has asked for it.
     *
     * <p>Order matters and this is the order: an admin's per-slot decision is about how the effect READS in that
     * presentation, while the crowd variant is about what the machine can afford right now, so the budget wins.
     * A returned map is always a fresh copy, so a presenter may consume it.
     */
    public Map<String, String> effectiveParams(String slotKey, boolean crowded)
    {
        Map<String, String> out = new LinkedHashMap<>(params == null ? Map.of() : params);
        out.putAll(slotOverride(slotKey));
        if (crowded)
        {
            // Read the crowd keys out of the SAME resolved map, so a per-slot override of a crowd key is honoured
            // rather than being read from the base set behind it.
            Map<String, String> source = new LinkedHashMap<>(out);
            for (Map.Entry<String, String> e : source.entrySet())
            {
                String target = CROWD_TARGETS.get(e.getKey());
                if (target != null && e.getValue() != null && !e.getValue().isBlank())
                    out.put(target, e.getValue());
            }
            if (CROWD_RING2_OFF.equalsIgnoreCase(source.getOrDefault("crowd.ring2", "")))
            {
                out.remove(P_HALO_RING2_RADIUS);
                out.remove(P_HALO_RING2_ORBIT_TICKS);
            }
        }
        // The crowd keys themselves are stripped from what the presenter sees: they have been applied, and a
        // presenter reading one afterwards would be reading a setting twice.
        out.keySet().removeIf(k -> k != null && k.startsWith(CROWD_PREFIX));
        return out;
    }

    /**
     * The scale to draw at. The one crowd lever that lands on a field rather than a param, because quad cost
     * goes as the square of the scale and, for dust types, a smaller scale is also a shorter lifetime, so it
     * saves the count for free.
     */
    public float effectiveScale(String slotKey, boolean crowded)
    {
        if (!crowded)
            return scale;
        String v = slotOverride(slotKey).get(P_CROWD_SCALE);
        if (v == null)
            v = param(P_CROWD_SCALE, null);
        if (v == null)
            return scale;
        try
        {
            float f = Float.parseFloat(v.trim());
            return f > 0.0F && !Float.isInfinite(f) ? f : scale;
        }
        catch (NumberFormatException e)
        {
            return scale;
        }
    }

    public int colorPrimaryRgb()
    {
        return rgb(colorPrimary, 0xFFFFFF);
    }

    public int colorSecondaryRgb()
    {
        return rgb(colorSecondary, 0xFFFFFF);
    }

    /** {@code #RRGGBB} to a packed int, or the fallback. Never throws: a hand-typed colour is not a crash. */
    public static int rgb(String hex, int fallback)
    {
        String h = normaliseHex(hex, null);
        if (h == null)
            return fallback;
        try
        {
            return Integer.parseInt(h.substring(1), 16);
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    /**
     * Canonical {@code #RRGGBB}, upper case, or the fallback.
     *
     * <p>Canonicalised rather than merely accepted because two shards holding the same colour typed as
     * {@code #ff9a2e} and {@code FF9A2E} would otherwise hash differently and republish each other forever.
     */
    public static String normaliseHex(String raw, String fallback)
    {
        if (raw == null)
            return fallback;
        String h = raw.trim();
        if (h.startsWith("#"))
            h = h.substring(1);
        if (h.length() == 3)
        {
            // #abc is a common shorthand an admin will type. Expanded here so the persisted form is one shape.
            StringBuilder sb = new StringBuilder(6);
            for (char c : h.toCharArray())
                sb.append(c).append(c);
            h = sb.toString();
        }
        if (h.length() != 6)
            return fallback;
        for (char c : h.toCharArray())
            if (Character.digit(c, 16) < 0)
                return fallback;
        return "#" + h.toUpperCase(Locale.ROOT);
    }

    /**
     * Lower case, trimmed, and given the {@code minecraft} namespace when it has none, which is what a
     * ResourceLocation does anyway. An unparseable value is KEPT rather than blanked: losing an admin's typo is
     * worse than reporting {@link #valid()} false and showing them what they typed.
     */
    public static String normaliseResourceId(String raw)
    {
        if (raw == null)
            return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty())
            return "";
        if (s.indexOf(':') < 0 && ResourceLocation.tryParse("minecraft:" + s) != null)
            return "minecraft:" + s;
        return s;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id == null ? "" : id);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeUtf(rarityLabel == null ? "" : rarityLabel);
        buf.writeUtf(particle == null ? "" : particle);
        buf.writeUtf(colorPrimary == null ? "" : colorPrimary);
        buf.writeUtf(colorSecondary == null ? "" : colorSecondary);
        buf.writeFloat(density);
        buf.writeFloat(scale);
        buf.writeVarInt(lifetimeTicks);
        buf.writeUtf(sound == null ? "" : sound);
        buf.writeFloat(soundVolume);
        buf.writeFloat(soundPitch);
        buf.writeBoolean(enabled);
        writeMap(buf, params);
        Map<String, Map<String, String>> so = slotOverrides == null ? Map.of() : slotOverrides;
        buf.writeVarInt(so.size());
        for (Map.Entry<String, Map<String, String>> e : so.entrySet())
        {
            buf.writeUtf(e.getKey() == null ? "" : e.getKey());
            writeMap(buf, e.getValue());
        }
    }

    public static CosmeticEffect decode(FriendlyByteBuf buf)
    {
        CosmeticEffect c = new CosmeticEffect();
        c.id = buf.readUtf();
        c.displayName = buf.readUtf();
        c.rarityLabel = buf.readUtf();
        c.particle = buf.readUtf();
        c.colorPrimary = buf.readUtf();
        c.colorSecondary = buf.readUtf();
        c.density = buf.readFloat();
        c.scale = buf.readFloat();
        c.lifetimeTicks = buf.readVarInt();
        c.sound = buf.readUtf();
        c.soundVolume = buf.readFloat();
        c.soundPitch = buf.readFloat();
        c.enabled = buf.readBoolean();
        c.params = readMap(buf);
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
        {
            String slotKey = buf.readUtf();
            c.slotOverrides.put(slotKey, readMap(buf));
        }
        return c.normalise();
    }

    private static void writeMap(FriendlyByteBuf buf, Map<String, String> m)
    {
        Map<String, String> src = m == null ? Map.of() : m;
        buf.writeVarInt(src.size());
        for (Map.Entry<String, String> e : src.entrySet())
        {
            buf.writeUtf(e.getKey() == null ? "" : e.getKey());
            buf.writeUtf(e.getValue() == null ? "" : e.getValue());
        }
    }

    private static Map<String, String> readMap(FriendlyByteBuf buf)
    {
        int n = buf.readVarInt();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++)
            out.put(buf.readUtf(), buf.readUtf());
        return out;
    }

    /**
     * Serialise for the cross-server state sync. DETERMINISTIC, and every rule below is load-bearing.
     *
     * <ul>
     *   <li>Map keys are written in SORTED order, so two shards that authored the same effect in a different
     *       order produce identical bytes.</li>
     *   <li>Floats are written as raw int bits, so a value that round-trips through a file and a wire cannot
     *       drift in the last place and move the content hash.</li>
     *   <li>Blank optionals are OMITTED entirely rather than written as empty, so a record that has never had a
     *       sound and one whose sound was cleared hash the same.</li>
     * </ul>
     *
     * <p>Two converged shards that produced different bytes here would republish each other forever, which is
     * the failure this method exists to prevent.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("displayName", displayName == null ? "" : displayName);
        if (rarityLabel != null && !rarityLabel.isBlank())
            t.putString("rarityLabel", rarityLabel);
        t.putString("particle", particle == null ? "" : particle);
        t.putString("colorPrimary", colorPrimary == null ? "" : colorPrimary);
        t.putString("colorSecondary", colorSecondary == null ? "" : colorSecondary);
        t.putInt("densityBits", Float.floatToIntBits(density));
        t.putInt("scaleBits", Float.floatToIntBits(scale));
        t.putInt("lifetimeTicks", lifetimeTicks);
        if (sound != null && !sound.isBlank())
        {
            t.putString("sound", sound);
            t.putInt("soundVolumeBits", Float.floatToIntBits(soundVolume));
            t.putInt("soundPitchBits", Float.floatToIntBits(soundPitch));
        }
        t.putBoolean("enabled", enabled);
        if (params != null && !params.isEmpty())
            t.put("params", sortedMap(params));
        if (slotOverrides != null && !slotOverrides.isEmpty())
        {
            CompoundTag so = new CompoundTag();
            List<String> slotKeys = new ArrayList<>(slotOverrides.keySet());
            Collections.sort(slotKeys);
            for (String k : slotKeys)
            {
                Map<String, String> inner = slotOverrides.get(k);
                if (inner != null && !inner.isEmpty())
                    so.put(k, sortedMap(inner));
            }
            if (!so.isEmpty())
                t.put("slotOverrides", so);
        }
        return t;
    }

    public static CosmeticEffect fromNbt(CompoundTag t)
    {
        CosmeticEffect c = new CosmeticEffect();
        c.id = t.getString("id");
        c.displayName = t.getString("displayName");
        c.rarityLabel = t.getString("rarityLabel");
        c.particle = t.getString("particle");
        c.colorPrimary = t.getString("colorPrimary");
        c.colorSecondary = t.getString("colorSecondary");
        // An absent bits key is an older tag, not a zero: defaulting to 1.0 keeps the effect visible rather than
        // silently emitting nothing, which is the failure an admin cannot debug.
        c.density = t.contains("densityBits") ? Float.intBitsToFloat(t.getInt("densityBits")) : 1.0F;
        c.scale = t.contains("scaleBits") ? Float.intBitsToFloat(t.getInt("scaleBits")) : 1.0F;
        c.lifetimeTicks = t.getInt("lifetimeTicks");
        c.sound = t.getString("sound");
        c.soundVolume = t.contains("soundVolumeBits") ? Float.intBitsToFloat(t.getInt("soundVolumeBits")) : 0.0F;
        c.soundPitch = t.contains("soundPitchBits") ? Float.intBitsToFloat(t.getInt("soundPitchBits")) : 1.0F;
        c.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        // The pre-M1 tag wrote its allow-listed kind under "type" and nothing else. Read here so a tag written
        // by that shape reaches the same migration the Gson path uses, rather than arriving as a blank effect.
        if (t.contains("type"))
            c.effectType = t.getString("type");
        CompoundTag p = t.getCompound("params");
        for (String k : p.getAllKeys())
            c.params.put(k, p.getString(k));
        CompoundTag so = t.getCompound("slotOverrides");
        for (String slotKey : so.getAllKeys())
        {
            CompoundTag inner = so.getCompound(slotKey);
            Map<String, String> m = new LinkedHashMap<>();
            for (String k : inner.getAllKeys())
                m.put(k, inner.getString(k));
            c.slotOverrides.put(slotKey, m);
        }
        return c.normalise();
    }

    private static CompoundTag sortedMap(Map<String, String> m)
    {
        CompoundTag out = new CompoundTag();
        List<String> keys = new ArrayList<>(m.keySet());
        Collections.sort(keys);
        for (String k : keys)
        {
            String v = m.get(k);
            out.putString(k, v == null ? "" : v);
        }
        return out;
    }
}
