package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.MainGameRules;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/** The only class naming DMZ's ki grief gate. See {@link KiGrief}. */
final class KiGriefImpl
{
    private KiGriefImpl() {}

    static boolean mayDestroy(Level level, BlockPos pos, Entity source)
    {
        try
        {
            return MainGameRules.canKiGrief(level, pos, source);
        }
        catch (Throwable t)
        {
            // Fail CLOSED. A gate we cannot read is not a gate to walk through: refusing costs one crater, allowing
            // costs someone's build.
            return false;
        }
    }
}
