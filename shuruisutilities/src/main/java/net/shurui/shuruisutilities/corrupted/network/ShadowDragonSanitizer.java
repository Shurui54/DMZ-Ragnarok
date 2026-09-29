package net.shurui.shuruisutilities.corrupted.network;

import net.shurui.shuruisutilities.corrupted.ShadowDragonDef;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;

import net.minecraft.resources.ResourceLocation;

/**
 * Server-side validation for shadow-dragon def values. Never trust the client, but note what that does and does not
 * mean here: a stat is checked for being a USABLE NUMBER, not for being a number somebody else thinks is reasonable.
 * There is deliberately NO upper bound on any stat. The owner sets these, an arena boss is meant to be absurd, and a
 * ceiling only ever shows up as "the number I typed is not the number that was saved".
 *
 * <p>What is still enforced, and why each one is a validity check rather than a cap:
 * <ul>
 *   <li>NaN and infinity become 0, because they are not quantities and would poison every later multiplication.</li>
 *   <li>Negatives become 0, because the DMZ stats use "0 = keep the entity's own default" and a negative max health
 *       or attack damage has no meaning to the attribute system.</li>
 *   <li>Scale must be positive, because it is a multiplier: 0 or less is a degenerate model, not a small one.</li>
 *   <li>AI tier stays in 0..3, because that is the full range of DMZ's AiTier enum, not a limit we chose.</li>
 *   <li>The entity id must parse as a resource location, and the two dragon models must be real rgnpc ids, or the
 *       spawn path would fail at spawn time instead of at save time.</li>
 * </ul>
 */
public final class ShadowDragonSanitizer
{
    private ShadowDragonSanitizer() {}

    private static final int MAX_NAME_LEN = 64;

    /** Scrub a "0 = keep default" double stat: NaN/infinity and negatives become 0. No upper bound. */
    public static double nonNeg(double v)
    {
        if (Double.isNaN(v) || Double.isInfinite(v))
            return 0.0;
        return Math.max(0.0, v);
    }

    /** Scrub a "0 = keep default" int stat: negatives become 0. No upper bound. */
    public static int nonNegInt(int v)
    {
        return Math.max(0, v);
    }

    /** Scrub the visual scale. It is a multiplier, so anything not positive is degenerate and resets to 1.0. */
    public static double scale(double v)
    {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0)
            return 1.0;
        return v;
    }

    /** Hold DMZ's 1-based AiTier id in [0, 3]; 0 keeps the entity default. The enum's own range, not a cap. */
    public static int aiTier(int v)
    {
        return Math.max(0, Math.min(v, 3));
    }

    /** Trim + length-cap the display name, falling back to the slot's canonical dragon name when blank. */
    public static String name(String v, int slot)
    {
        if (v == null)
            return ShadowDragonDef.defaultName(slot);
        String s = v.trim();
        if (s.isEmpty())
            return ShadowDragonDef.defaultName(slot);
        if (s.length() > MAX_NAME_LEN)
            s = s.substring(0, MAX_NAME_LEN);
        return s;
    }

    /** Validate the entity id as a well-formed resource location; blank / malformed -&gt; the neutral default. */
    public static String entityType(String v)
    {
        if (v == null)
            return ShadowDragonDef.DEFAULT_ENTITY_TYPE;
        String s = v.trim();
        if (s.isEmpty())
            return ShadowDragonDef.DEFAULT_ENTITY_TYPE;
        // tryParse returns null on a malformed id (bad characters, missing namespace handling is added by the ctor)
        ResourceLocation parsed = ResourceLocation.tryParse(s);
        if (parsed == null)
            return ShadowDragonDef.DEFAULT_ENTITY_TYPE;
        return parsed.toString();
    }

    /**
     * Validate the dragon this slot wears. An unknown id falls back to the slot's canonical dragon rather than to the
     * bare rgnpc default, so a bad value cannot turn Syn Shenron into the generic fighter.
     */
    public static String baseModel(String v, int slot)
    {
        // resolveId, not isValidId, so a pre-rename id is carried forward to its current one instead of being reset.
        String resolved = RgNpcModels.resolveId(v == null ? "" : v.trim());
        return resolved != null ? resolved : ShadowDragonDef.defaultBaseModel(slot);
    }

    /**
     * Validate the model this dragon changes to at half health. Blank is a legitimate value and is kept: it means
     * the dragon has no transformed form (slot 3, Eis Shenron), and the fight simply never swaps its model.
     */
    public static String transformModel(String v, int slot)
    {
        String s = v == null ? "" : v.trim();
        if (s.isEmpty())
            return "";
        return RgNpcModels.isValidId(s) ? s : ShadowDragonDef.defaultTransformModel(slot);
    }
}
