package net.shurui.shuruisutilities.senzu;

import java.util.Locale;

/**
 * The eight growable bean varieties. Note CRACKED is intentionally BOTH its own growable type (its bean is the
 * cracked senzu, {@code bean_cracked}) AND the name of the weaker outcome the other types roll into on harvest, so
 * "the cracked variant of SENZU" and "the CRACKED type's bean" are the same item on purpose.
 *
 * <p>Every id this feature uses is underscore-cased ({@code bean_hp}, {@code seed_hp}, {@code bean_pot_hp}, ...) so
 * it never collides with the pre-existing camel-run placeholders in ContentItems ({@code senzubean}, {@code senzuhp}
 * and friends), which are separate legacy content we must leave untouched.
 */
public enum BeanType
{
    SENZU,
    HP,
    KI,
    STAMINA,
    GOLDEN,
    BURNT,
    DEATH,
    CRACKED;

    // the lowercase tail shared by every id for this type (e.g. "hp"). Locale.ROOT so a Turkish-locale build never
    // folds the I in a surprising way.
    public String lower()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    // registry ids for the three items/blocks this type owns
    public String beanId()
    {
        return "bean_" + lower();
    }

    public String seedId()
    {
        return "seed_" + lower();
    }

    public String potId()
    {
        return "bean_pot_" + lower();
    }

    // the item id harvested as the WEAKER outcome for this type. For SENZU that is bean_cracked (the CRACKED type's
    // own bean, reused as senzu's cracked variant); for CRACKED and BURNT there is no separate cracked item and this
    // is never consulted (their harvest is a flat 100% drop handled in the pot). For the rest it is bean_cracked_<type>.
    public String crackedVariantId()
    {
        if (this == SENZU)
        {
            return "bean_cracked";
        }
        return "bean_cracked_" + lower();
    }
}
