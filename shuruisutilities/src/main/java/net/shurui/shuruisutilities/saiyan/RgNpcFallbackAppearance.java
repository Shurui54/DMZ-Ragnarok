package net.shurui.shuruisutilities.saiyan;

import java.util.UUID;

/**
 * The {@link SaiyanAppearance} an rgnpc entity presents WHEN ITS MODEL IS NOT INSTALLED, so the client draws a generated
 * DMZ saiyan instead of a plain Steve. Adding this interface is the whole per-entity cost: every field is a default
 * method reading the one UUID-derived {@link SaiyanAppearance.Roll} (see {@link FallbackSaiyanLook}), so
 * {@code RgNpcEntity}, {@code RgNpcFighterEntity} and {@code PlanetGarrisonDefenderEntity} gain the contract without
 * eleven getters each.
 *
 * <p>Common (not client): the getters return meaningful values on the server too, but the server never renders, and
 * keeping the interface common lets the common entity classes implement it without a client import.
 *
 * <p>{@link #getNamed()} is pinned to null: a fallback stand-in must NEVER be a named saiyan (real player skin + account
 * name over the wrong entity). {@link #getScouterColor()} stays at the default, so a fallback rgnpc wears no scouter.
 * {@link #getUUID()} is declared only so the defaults can reach it; every implementor is a Minecraft {@code Entity},
 * which already supplies it.
 */
public interface RgNpcFallbackAppearance extends SaiyanAppearance
{
    /** Supplied by every implementor via {@code net.minecraft.world.entity.Entity#getUUID()}; the seed for the look. */
    UUID getUUID();

    /** The one UUID-derived look, computed once and cached; every getter below reads it. */
    default Roll rgNpcFallbackRoll()
    {
        return FallbackSaiyanLook.of(getUUID());
    }

    @Override
    default boolean isMale()
    {
        return rgNpcFallbackRoll().male();
    }

    @Override
    default int getBodyType()
    {
        return rgNpcFallbackRoll().bodyType();
    }

    @Override
    default int getHairId()
    {
        return rgNpcFallbackRoll().hairId();
    }

    @Override
    default int getEyesType()
    {
        return rgNpcFallbackRoll().eyesType();
    }

    @Override
    default int getNoseType()
    {
        return rgNpcFallbackRoll().noseType();
    }

    @Override
    default int getMouthType()
    {
        return rgNpcFallbackRoll().mouthType();
    }

    @Override
    default int getSkinColor()
    {
        return rgNpcFallbackRoll().skinColor();
    }

    @Override
    default int getTailColor()
    {
        return rgNpcFallbackRoll().tailColor();
    }

    @Override
    default int getHairColor()
    {
        return rgNpcFallbackRoll().hairColor();
    }

    @Override
    default int getEye1Color()
    {
        return rgNpcFallbackRoll().eye1Color();
    }

    @Override
    default int getEye2Color()
    {
        return rgNpcFallbackRoll().eye2Color();
    }

    /** Always null: a fallback stand-in is never a named saiyan, so it never wears a real player's skin or name tag. */
    @Override
    default NamedSaiyan getNamed()
    {
        return null;
    }
}
