package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How an {@link CosmeticSlot#ACCESSORY} cosmetic is drawn on the body. Meaningful ONLY on the accessory slot; on
 * any other slot it is inert and normally left at its default.
 *
 * <h2>DATA, not a hardcoded id list</h2>
 * Which of the twelve shipped accessories is a held prop, which is a balloon and which is a carried oddment used to
 * live only as an implicit split in the render code. It is a field on {@link CosmeticDef} now, so an admin retunes
 * it in game with no build, the same way {@link CosmeticSlot} and {@link CosmeticMount#flying} are data. The render
 * layer branches on it rather than on a baked-in id table.
 *
 * <h2>The key, never the ordinal</h2>
 * Every persisted and wire form is the {@link #key} string, so the order of the constants here carries no meaning
 * and declaring a new one can never shift a stored record onto a different style. {@link #byKey} never throws: an
 * unknown or absent key reads as {@link #CARRIED}, the same discipline {@link CosmeticSlot#byKey} uses.
 *
 * <h2>Why CARRIED is the default</h2>
 * Before this field existed, every accessory drew in the hand, always. {@link #CARRIED} is exactly that behaviour
 * (held in the hand, ungated), so an accessory with no style set, or one read off a record written before this
 * field, keeps drawing the way it did with no migration. The eight held weapon props are moved to {@link #HAND}
 * and the three balloons to {@link #FLOAT} by a seed value and a one-time migration; anything not touched stays
 * CARRIED, which is the least surprising outcome for an admin's own hand trinket.
 */
public enum CosmeticAccessoryStyle
{
    /**
     * A held prop that appears ONLY while the wearer has a DragonMineZ ki weapon drawn. These are the weapon-shaped
     * accessories (scythes, swords, wands, staffs): they read as a skin over the ki weapon, so they show when the
     * ki weapon is out and vanish when it is put away. The render layer asks DMZ's own
     * {@code PlayerAttackHelper.isKiWeaponActive} for that state.
     */
    HAND("hand"),

    /**
     * A balloon that floats above and slightly to the side of the wearer, bobbing gently and lagging behind their
     * movement, tethered to the hand by a string drawn the way vanilla draws a fishing line.
     */
    FLOAT("float"),

    /**
     * A carried oddment held in the hand at all times, ungated. The default, and the prior behaviour of every
     * accessory. The candy basket is CARRIED: it is a trick-or-treat prop a player carries around, not a weapon
     * skin that should only appear mid combat, and not a balloon.
     */
    CARRIED("carried");

    /** Stable lowercase key. PERSISTED on every definition, so never rename these. */
    public final String key;

    CosmeticAccessoryStyle(String key)
    {
        this.key = key;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.core.cosmetics.accstyle." + key;
    }

    private static final CosmeticAccessoryStyle[] VALUES = values();

    /** Never throws: an unknown or absent key reads as {@link #CARRIED}, matching the field default. */
    public static CosmeticAccessoryStyle byKey(String key)
    {
        if (key != null)
        {
            String lower = key.toLowerCase(Locale.ROOT);
            for (CosmeticAccessoryStyle s : VALUES)
                if (s.key.equals(lower))
                    return s;
        }
        return CARRIED;
    }

    /** Every style's key, in declaration order, for an editor dropdown. */
    public static List<String> keys()
    {
        List<String> out = new ArrayList<>(VALUES.length);
        for (CosmeticAccessoryStyle s : VALUES)
            out.add(s.key);
        return out;
    }
}
