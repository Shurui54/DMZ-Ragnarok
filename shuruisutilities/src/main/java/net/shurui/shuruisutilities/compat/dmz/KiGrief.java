package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

/**
 * DMZ's own "may ki destroy this block" gate, for OUR destructive abilities.
 *
 * <h2>Why ask DMZ rather than decide ourselves</h2>
 * {@code MainGameRules.canKiGrief} is the single funnel every DMZ ki terrain-destruction path goes through, and by
 * now it is the funnel a lot of other things hang off too: DMZ's own {@code allowKiGriefing*} gamerules and its
 * master-structure protection, WorldGuard, SU's guild-claim guard ({@code MixinDmzKiGrief}), and SU's terrain-regen
 * CAPTURE ({@code MixinDmzKiGriefCapture}), which snapshots the block on the way past so it can be repaired later.
 *
 * <p>So routing our own destruction through this one call is not a shortcut, it is the only way to be consistent:
 * anything that breaks blocks without asking here obeys none of those rules, repairs none of its damage, and drifts
 * further from ki destruction every time either side is retuned.
 *
 * <p>Answers FALSE without DMZ, so a destructive ability on a server without it simply cannot break anything rather
 * than breaking everything unchecked.
 */
public final class KiGrief
{
    private KiGrief() {}

    /** May this block be destroyed by ki (or by something of ours that plays by the same rules)? */
    public static boolean mayDestroy(Level level, BlockPos pos, Entity source)
    {
        return level != null && pos != null && ModList.get().isLoaded("dragonminez")
                && KiGriefImpl.mayDestroy(level, pos, source);
    }
}
