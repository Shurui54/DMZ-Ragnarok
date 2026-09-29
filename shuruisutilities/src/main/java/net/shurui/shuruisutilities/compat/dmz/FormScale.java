package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * How much bigger than normal a player currently is, from their ACTIVE FORM's model scaling.
 *
 * <p>DMZ sizes a form through {@code FormData.modelScaling}, which is a RENDER scale: the model grows and the
 * bounding box does not. That is the whole reason combat felt broken around giant forms. DMZ measures melee
 * reach from the attacker's eye to the TARGET'S BOUNDING BOX, so against a player whose model is three times
 * life size you still had to reach a normal 0.6x1.8 box buried at their centre, while they, eyes high and far
 * forward, reached you from well outside yours.</p>
 *
 * <p>This reads the scale so the reach test can put both sides back in proportion. It is deliberately keyed on
 * model scaling rather than on the giant form specifically: any form that makes a player bigger should move
 * reach with it, and a form added later should not need this file edited.</p>
 *
 * <p>Client and server both call this; {@code getActiveFormData} is available on both.</p>
 */
public final class FormScale
{
    private FormScale() {}

    /** Index of the HEIGHT component in DMZ's {@code [x, y, z]} model scaling. */
    private static final int Y = 1;

    /** Scale is never read as SHRINKING reach: a small form should not be harder to hit than a normal one. */
    private static final float MIN = 1.0f;

    /**
     * A sane ceiling. Reach grows linearly with scale, which is what was asked for, but linear growth with no
     * limit means one absurd form config hands somebody a reach measured in tens of blocks. Twelve is far above
     * any real form and exists only to stop a typo in a config becoming a combat exploit.
     */
    private static final float MAX = 12.0f;

    /**
     * Blocks of reach granted per extra block of HEIGHT. Shurui's number, 2026-08-29.
     *
     * <p>This used to be 1.0 implicitly, which is where "larger players have insane reach" came from: a form at
     * three times size stands about 3.6 blocks taller than normal and was handed 3.6 blocks of extra reach, on top
     * of a vanilla reach of 3. Half a block per block of height keeps a giant's swing tied to its frame without
     * letting it out-range everything on the floor: the same form now gains 1.8.</p>
     *
     * <p>It applies to BOTH sides, because {@code MixinDmzSizedReach} uses this one number for the attacker's ray
     * and for the target's box alike. Halving it therefore also halves how far away a giant can BE hit from, which
     * is the intent: the two have to stay equal or one side gets a free advantage.</p>
     */
    private static final double REACH_PER_BLOCK_OF_HEIGHT = 0.5D;

    /**
     * This entity's height scale: 1.0 for anything that is not a player, has no form, or is at normal size.
     *
     * <p>Never throws. A failure to read the form reads as "normal size", which leaves DMZ's own behaviour
     * exactly as it was rather than handing out reach on a broken read.</p>
     */
    public static float heightScale(Entity entity)
    {
        if (!(entity instanceof Player player))
        {
            return 1.0f;
        }
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            if (stats == null || stats.getCharacter() == null)
            {
                return 1.0f;
            }
            // The STACK form has to be checked too, and missing it made this return 1.0 for the very forms the
            // fix exists for. DMZ's own PlayerAttackHelper.getActiveFormComboAttributes reads exactly this pair,
            // in this order, so a form reached by stacking is invisible to anything that asks only the first.
            // The symptom was the whole bug reported: bonus zero means a giant keeps a normal 0.6x1.8 box buried
            // inside a huge model, so they cannot be hit until you stand on that box, while their model has been
            // overlapping you for several blocks already, which reads as them having enormous reach.
            FormConfig.FormData form = stats.getCharacter().getActiveFormData();
            if (form == null)
            {
                form = stats.getCharacter().getActiveStackFormData();
            }
            if (form == null)
            {
                return 1.0f;
            }
            Float[] scaling = form.getModelScaling();
            if (scaling == null || scaling.length <= Y || scaling[Y] == null)
            {
                return 1.0f;
            }
            return Math.max(MIN, Math.min(MAX, scaling[Y]));
        }
        catch (Throwable ignored)
        {
            return 1.0f;
        }
    }

    /**
     * Extra blocks of reach (or of hittable distance) this entity's size is worth, in BLOCKS.
     *
     * <p>Linear in the scale, as asked: the bonus is how much taller than normal the form is, so a form at 3x
     * height is worth two extra body heights. Applied to the ATTACKER it lengthens their reach; applied to the
     * TARGET it lets them be struck from the same distance further out. Using one function for both is what
     * keeps the two sides symmetric, which was the actual complaint: whatever a big player gains in reach, they
     * also give up in how far away they can be hit from.</p>
     */
    public static double reachBonus(Entity entity)
    {
        float scale = heightScale(entity);
        if (scale <= 1.0f)
        {
            return 0.0D;
        }
        // Height at normal scale. Read from the entity so a non-standard base size is respected rather than
        // assuming every player is 1.8 blocks tall.
        double baseHeight = entity.getBbHeight();
        return (scale - 1.0f) * baseHeight * REACH_PER_BLOCK_OF_HEIGHT;
    }
}
