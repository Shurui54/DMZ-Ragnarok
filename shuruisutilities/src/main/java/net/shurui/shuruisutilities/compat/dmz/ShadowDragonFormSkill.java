package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;

import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.DmzBridge;

/**
 * DMZ-facing race read for the shadow dragon feature, reached only through {@link ShadowDragonFormCompat}'s guard.
 *
 * <p>The transformation GRANT (raising the shared {@code superforms} skill to the Omega form's unlock level, with its
 * hard cross-race guard) is the Ragnarok Key's, feature {@code shadowform}; what stays in core is the live race read
 * the public kill tallies and race checks need.
 *
 * <p>API verified against dragonminez-2.1.3.jar with javap: {@code StatsData.getCharacter():Character},
 * {@code Character.getRaceName():String} (race field, defaults to "human").
 */
final class ShadowDragonFormSkill
{
    private ShadowDragonFormSkill() {}

    /** The player's current DMZ race id, or null when statless or on any error. */
    static String currentRace(ServerPlayer player)
    {
        StatsData stats = DmzBridge.stats(player);
        if (stats == null)
            return null;
        try
        {
            Character character = stats.getCharacter();
            return character == null ? null : character.getRaceName();
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
