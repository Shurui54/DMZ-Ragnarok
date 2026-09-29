package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Re-applies our technique DEFINITIONS onto the copies a player already has unlocked.
 *
 * <h2>Why this has to exist</h2>
 * A player's unlocked techniques are not references into {@code PredefinedTechniques.REGISTRY}: DMZ writes each one
 * out in full to player NBT ({@code Techniques.save}) and rebuilds it from that NBT on load, never consulting the
 * registry again. Everything DMZ then uses at cast time - the ki type, the SIZE, the colours, the cooldown and the
 * ANIMATION PREFIX - is read from that stored copy ({@code TickHandler.handleTechniqueCharge} and
 * {@code AbstractKiProjectile.triggerAnimationPacket} both look the technique up in
 * {@code getUnlockedTechniques()}).
 *
 * <p>So a change to {@code DragonTechniqueDefs} only reached a character who unlocked the move AFTERWARDS. Anyone
 * who already had it kept the definition from the day they unlocked it - including the era when these moves had no
 * size at all (an invisible ball) or the wrong animation prefix (a silent cast). That is why re-tuning the moves
 * appeared to do nothing.
 *
 * <p>{@link #refreshAll} closes that gap by rewriting each stored copy from the registry on login, keeping the
 * player's own progression (experience and every upgrade level, with the per-level stat gains re-applied on top of
 * the new base).
 */
public final class TechniqueRefresh
{
    private TechniqueRefresh() {}

    /**
     * Bring every one of OUR techniques this player has unlocked back in line with the current definition.
     *
     * @return how many stored copies were actually rewritten
     */
    public static int refreshAll(ServerPlayer player)
    {
        return player != null && ModList.get().isLoaded("dragonminez")
                ? TechniqueRefreshImpl.refreshAll(player) : 0;
    }
}
