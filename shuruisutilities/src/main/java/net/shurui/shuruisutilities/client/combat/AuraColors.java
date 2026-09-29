package net.shurui.shuruisutilities.client.combat;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;

import net.minecraft.world.entity.player.Player;

/**
 * The colour a player's aura is currently drawn in, for anything of ours that has to match it.
 *
 * <p>The resolution order is DragonMineZ's own, not one of ours, because the point is to agree with what is already on
 * screen: the ACTIVE FORM's aura colour wins when that form declares one, and the character's own aura colour is the
 * fallback. DMZ's {@code AuraRenderer} reads exactly those two, in that order, and {@code hasAuraColorOverride} is the
 * flag it branches on.
 *
 * <p>Reading through {@code Character.getActiveFormData()} also means form COSMETIC overrides are already applied:
 * {@code MixinDmzCharacterFormCosmetic} injects into that method and returns the overridden copy, so a player who has
 * recoloured their form's aura gets a trail in the recoloured shade without this class knowing anything about
 * cosmetics.
 *
 * <p>Every path is wrapped: a decorative tint must never be the reason a frame throws, so any failure returns white
 * and the caller draws what it always drew.
 */
public final class AuraColors
{
    private AuraColors() {}

    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

    /**
     * This player's aura colour as {r, g, b} in 0..1, or white when it cannot be determined.
     *
     * <p>The returned array must not be modified: it may be DMZ's own cached instance.
     */
    public static float[] of(Player player)
    {
        if (player == null)
            return WHITE;
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            if (stats == null)
                return WHITE;
            Character character = stats.getCharacter();
            if (character == null)
                return WHITE;

            FormConfig.FormData form = character.getActiveFormData();
            if (form != null && Boolean.TRUE.equals(form.hasAuraColorOverride()))
            {
                float[] rgb = form.getRgbAuraColor();
                if (valid(rgb))
                    return rgb;
            }
            float[] rgb = character.getRgbAuraColor();
            return valid(rgb) ? rgb : WHITE;
        }
        catch (Throwable t)
        {
            return WHITE;
        }
    }

    // DMZ computes these lazily from a hex string, so a colour that has never been read yet, or one whose hex failed to
    // parse, comes back null or short rather than as a usable triple.
    private static boolean valid(float[] rgb)
    {
        return rgb != null && rgb.length >= 3;
    }
}
