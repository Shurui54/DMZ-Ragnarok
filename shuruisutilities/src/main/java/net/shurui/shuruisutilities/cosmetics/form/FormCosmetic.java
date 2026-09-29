package net.shurui.shuruisutilities.cosmetics.form;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One player's per-form cosmetic override. STRUCTURALLY appearance only: this class has no stat, cost, mastery,
 * unlock or scale field at all, so a client packet or a stored record can never carry a gameplay value into DMZ's
 * form system no matter what it sends. The render side copies a form's real {@code FormData} verbatim (all stats
 * intact) and only replaces the appearance strings below, and the server re-sanitizes every field on save.
 *
 * <p>Each field is a hex colour ({@code "#RRGGBB"}) or empty. Empty means "keep the form's own value for that
 * channel", so a supporter can recolour just the aura, say, and leave everything else stock. Exactly one form is
 * targeted per player ({@link #group} + {@link #form}); there is no way to hold more than one.
 *
 * <p>Besides colours this now carries five ID-STRING channels: hair state, hair code, model, extra layer and aura
 * type. They were held back until there was a validated value list, because an id DMZ cannot resolve breaks the
 * model instead of merely looking wrong. {@link FormAppearanceOptions} supplies that list from DMZ's own loaded
 * configs and {@link FormCosmeticManager} enforces it on save, so a stored value is always one DMZ ships.
 *
 * <p>Still deliberately excluded: model SCALE, because DMZ's form scaling drives the entity hitbox, block/entity
 * reach and pose fitting server-side, so it is not cosmetic.
 */
public final class FormCosmetic
{
    // upper bound on the whole-body colour wash. DMZ only floors tintIntensity at 0 with no ceiling; past 1.0 the
    // body saturates into a single unreadable colour, so 1.0 (full but still shaped) is the sane maximum.
    public static final double TINT_INTENSITY_MAX = 1.0;
    // outline halo thickness. DMZ default is 1.5 and only floors at 0; a very thick outline becomes a glowing halo
    // that blocks the view of nearby players, so 3.0 (a bold but readable edge) is the ceiling.
    public static final double OUTLINE_THICKNESS_MIN = 0.0;
    public static final double OUTLINE_THICKNESS_MAX = 3.0;
    // longest id string accepted for an appearance channel. DMZ's own values are short names; anything longer is
    // junk or an attempt to stuff the record, so it is dropped rather than stored.
    public static final int ID_MAX_LENGTH = 64;

    // which single form this styles: group key + form name, matched case-insensitively against the active form.
    public String group = "";
    public String form = "";

    // appearance channels (all "#RRGGBB" or "")
    public String bodyColor1 = "";
    public String bodyColor2 = "";
    public String bodyColor3 = "";
    public String hairColor = "";
    public String eye1Color = "";
    public String eye2Color = "";
    public String auraColor = "";
    public String extraFormColor = "";

    // whole-body tint: only applied when tintColor is non-empty; intensity clamped to [0, TINT_INTENSITY_MAX]
    public String tintColor = "";
    public double tintIntensity = 0.0;

    // id-string appearance channels. Unlike the colours these NAME an asset, so an unresolvable value breaks the
    // model rather than looking wrong. They are validated against FormAppearanceOptions (the set of values DMZ
    // itself ships) server-side on save; empty means "keep the form's own value".
    public String hairType = "";
    public String hairCode = "";
    public String customModel = "";
    public String extraFormLayer = "";
    public String auraType = "";

    // outline shader: only applied when enabled; colours are "#RRGGBB", thickness clamped
    public boolean outlineEnabled = false;
    public String outlinePrimary = "";
    public String outlineSecondary = "";
    public double outlineThickness = 1.5;

    public FormCosmetic() {}

    /** True when a form is targeted (both group and form set). An untargeted override styles nothing. */
    public boolean hasTarget()
    {
        return group != null && !group.isBlank() && form != null && !form.isBlank();
    }

    /** True when at least one appearance channel actually overrides something (so an empty record is a no-op). */
    public boolean hasAnyOverride()
    {
        return !bodyColor1.isEmpty() || !bodyColor2.isEmpty() || !bodyColor3.isEmpty()
                || !hairColor.isEmpty() || !eye1Color.isEmpty() || !eye2Color.isEmpty()
                || !auraColor.isEmpty() || !extraFormColor.isEmpty()
                || (!tintColor.isEmpty() && tintIntensity > 0.0)
                || !hairType.isEmpty() || !hairCode.isEmpty() || !customModel.isEmpty()
                || !extraFormLayer.isEmpty() || !auraType.isEmpty()
                || outlineEnabled;
    }

    /**
     * True when this override styles the given active (group, form), matched case-insensitively.
     *
     * <p>Grade variants of one transformation share a look: DMZ's SSJ grades are separate form ids in the same
     * group ({@code supersaiyan}, {@code supersaiyangrade2}, {@code supersaiyangrade3} under {@code ssgrades}), so an
     * exact form match dropped a cosmetic set on the base grade the moment the player ascended to grade 2 or 3 (bug
     * 680a). We compare the form with a trailing {@code gradeN} stripped, so all grades of the same group keep the
     * one cosmetic. Only the grade groups use that suffix, so nothing else is merged.
     */
    public boolean matches(String activeGroup, String activeForm)
    {
        return hasTarget() && activeGroup != null && activeForm != null
                && group.equalsIgnoreCase(activeGroup)
                && stripGrade(form).equalsIgnoreCase(stripGrade(activeForm));
    }

    /** A form id with a trailing {@code grade} + digits removed, so {@code supersaiyangrade2} reads as
     * {@code supersaiyan}. Leaves every other form id (including {@code supersaiyan2}) untouched. */
    private static String stripGrade(String formId)
    {
        if (formId == null)
        {
            return "";
        }
        return formId.replaceFirst("(?i)grade\\d+$", "");
    }

    /**
     * Clamp and normalize every field to a legal value in place. Colours become {@code "#RRGGBB"} (upper case) or
     * {@code ""} if malformed, so a bad hex can never reach ColorUtils and produce a render error; numeric fields
     * are clamped to their published range. Called server-side on save (authoritative) and defensively on the
     * client when a synced record arrives.
     */
    public FormCosmetic sanitize()
    {
        group = group == null ? "" : group.trim();
        form = form == null ? "" : form.trim();
        bodyColor1 = hex(bodyColor1);
        bodyColor2 = hex(bodyColor2);
        bodyColor3 = hex(bodyColor3);
        hairColor = hex(hairColor);
        eye1Color = hex(eye1Color);
        eye2Color = hex(eye2Color);
        auraColor = hex(auraColor);
        extraFormColor = hex(extraFormColor);
        tintColor = hex(tintColor);
        tintIntensity = clamp(tintIntensity, 0.0, TINT_INTENSITY_MAX);
        outlinePrimary = hex(outlinePrimary);
        outlineSecondary = hex(outlineSecondary);
        outlineThickness = clamp(outlineThickness, OUTLINE_THICKNESS_MIN, OUTLINE_THICKNESS_MAX);
        hairType = id(hairType);
        hairCode = id(hairCode);
        customModel = id(customModel);
        extraFormLayer = id(extraFormLayer);
        auraType = id(auraType);
        return this;
    }

    // "" if blank/malformed, else "#RRGGBB" upper-cased. Six hex digits only, so alpha is never expressible: a
    // player cannot make themselves transparent/invisible through a colour, only recolour a solid model.
    private static String hex(String v)
    {
        if (v == null)
            return "";
        String s = v.trim();
        if (s.isEmpty())
            return "";
        String body = s.startsWith("#") ? s.substring(1) : s;
        if (body.length() != 6)
            return "";
        for (int i = 0; i < 6; i++)
            if (Character.digit(body.charAt(i), 16) < 0)
                return "";
        return "#" + body.toUpperCase(Locale.ROOT);
    }

    // Trim and bound an id string. Whether the id is one DMZ actually ships is checked server-side by
    // FormCosmeticManager against FormAppearanceOptions: that needs DMZ's loaded config, which this class must not
    // depend on because it also runs client side and over the wire.
    private static String id(String v)
    {
        if (v == null)
            return "";
        String s = v.trim();
        return s.length() > ID_MAX_LENGTH ? "" : s;
    }

    private static double clamp(double v, double lo, double hi)
    {
        if (Double.isNaN(v))
            return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("group", group);
        t.putString("form", form);
        t.putString("b1", bodyColor1);
        t.putString("b2", bodyColor2);
        t.putString("b3", bodyColor3);
        t.putString("hair", hairColor);
        t.putString("e1", eye1Color);
        t.putString("e2", eye2Color);
        t.putString("aura", auraColor);
        t.putString("extra", extraFormColor);
        t.putString("tint", tintColor);
        t.putDouble("tintI", tintIntensity);
        t.putBoolean("outline", outlineEnabled);
        t.putString("outP", outlinePrimary);
        t.putString("outS", outlineSecondary);
        t.putDouble("outT", outlineThickness);
        t.putString("hairT", hairType);
        t.putString("hairC", hairCode);
        t.putString("model", customModel);
        t.putString("layer", extraFormLayer);
        t.putString("auraT", auraType);
        return t;
    }

    public static FormCosmetic fromNbt(CompoundTag t)
    {
        FormCosmetic c = new FormCosmetic();
        c.group = t.getString("group");
        c.form = t.getString("form");
        c.bodyColor1 = t.getString("b1");
        c.bodyColor2 = t.getString("b2");
        c.bodyColor3 = t.getString("b3");
        c.hairColor = t.getString("hair");
        c.eye1Color = t.getString("e1");
        c.eye2Color = t.getString("e2");
        c.auraColor = t.getString("aura");
        c.extraFormColor = t.getString("extra");
        c.tintColor = t.getString("tint");
        c.tintIntensity = t.getDouble("tintI");
        c.outlineEnabled = t.getBoolean("outline");
        c.outlinePrimary = t.getString("outP");
        c.outlineSecondary = t.getString("outS");
        c.outlineThickness = t.contains("outT") ? t.getDouble("outT") : 1.5;
        c.hairType = t.getString("hairT");
        c.hairCode = t.getString("hairC");
        c.customModel = t.getString("model");
        c.extraFormLayer = t.getString("layer");
        c.auraType = t.getString("auraT");
        return c.sanitize();
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(group);
        buf.writeUtf(form);
        buf.writeUtf(bodyColor1);
        buf.writeUtf(bodyColor2);
        buf.writeUtf(bodyColor3);
        buf.writeUtf(hairColor);
        buf.writeUtf(eye1Color);
        buf.writeUtf(eye2Color);
        buf.writeUtf(auraColor);
        buf.writeUtf(extraFormColor);
        buf.writeUtf(tintColor);
        buf.writeDouble(tintIntensity);
        buf.writeBoolean(outlineEnabled);
        buf.writeUtf(outlinePrimary);
        buf.writeUtf(outlineSecondary);
        buf.writeDouble(outlineThickness);
        buf.writeUtf(hairType);
        buf.writeUtf(hairCode);
        buf.writeUtf(customModel);
        buf.writeUtf(extraFormLayer);
        buf.writeUtf(auraType);
    }

    public static FormCosmetic decode(FriendlyByteBuf buf)
    {
        FormCosmetic c = new FormCosmetic();
        c.group = buf.readUtf();
        c.form = buf.readUtf();
        c.bodyColor1 = buf.readUtf();
        c.bodyColor2 = buf.readUtf();
        c.bodyColor3 = buf.readUtf();
        c.hairColor = buf.readUtf();
        c.eye1Color = buf.readUtf();
        c.eye2Color = buf.readUtf();
        c.auraColor = buf.readUtf();
        c.extraFormColor = buf.readUtf();
        c.tintColor = buf.readUtf();
        c.tintIntensity = buf.readDouble();
        c.outlineEnabled = buf.readBoolean();
        c.outlinePrimary = buf.readUtf();
        c.outlineSecondary = buf.readUtf();
        c.outlineThickness = buf.readDouble();
        c.hairType = buf.readUtf();
        c.hairCode = buf.readUtf();
        c.customModel = buf.readUtf();
        c.extraFormLayer = buf.readUtf();
        c.auraType = buf.readUtf();
        return c.sanitize();
    }

    public FormCosmetic copy()
    {
        return fromNbt(toNbt());
    }
}
