package net.shurui.shuruisutilities.regen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.regions.RegionEventHandler;
import net.shurui.shuruisutilities.regions.RegionFlag;

/**
 * The {@code terrainRegen} boolean gamerule: registration + accessor.
 *
 * <p>When TRUE, blocks destroyed by combat are snapshotted as they break and put back a while later by
 * {@link TerrainRegenService}. When FALSE nothing is captured and nothing restores, so turning the rule off is a
 * complete off switch rather than a pause: a world that ran with it off has no pending work to inherit.
 *
 * <p>This is a gamerule rather than a config because the user asked for one global switch that an operator can flip
 * live, and because a gamerule is stored per world in level.dat, so a creative build world and a survival world on the
 * same server can disagree without any per dimension plumbing on our side.
 */
public final class TerrainRegenRule
{
    private TerrainRegenRule() {}

    public static final String RULE_NAME = "terrainRegen";
    public static final boolean DEFAULT = false;

    // null until register() runs in common setup; guarded reads below degrade to DEFAULT before then
    private static GameRules.Key<GameRules.BooleanValue> key;

    // Register the gamerule. MUST run on the mod thread (GameRules' static registry is not thread safe), so this is
    // called from FMLCommonSetupEvent#enqueueWork alongside SU's other rule. Guarded because re-registering the same
    // name would throw.
    public static void register()
    {
        if (key != null)
            return;
        key = GameRules.register(RULE_NAME, GameRules.Category.MISC, GameRules.BooleanValue.create(DEFAULT));
    }

    public static GameRules.Key<GameRules.BooleanValue> key()
    {
        return key;
    }

    // registered and currently on in this level. Every capture site and the drain both ask this, so a flip takes
    // effect on the next block broken and the next tick drained, with no restart.
    public static boolean isEnabled(Level level)
    {
        if (key == null || level == null)
            return false;
        try
        {
            return level.getGameRules().getBoolean(key);
        }
        catch (Throwable t)
        {
            return DEFAULT;
        }
    }

    /**
     * Whether this particular BLOCK should be repaired, which is the gamerule plus whatever the region it sits in has
     * to say about it.
     *
     * <p>Two ways to turn repair on and one to turn it off, in the order an operator would expect:
     * <ul>
     *   <li>A region with {@code terrain-regen} set to ALLOW repairs, whatever the gamerule says. That is what lets an
     *       arena rebuild itself between fights while the open world keeps its scars.</li>
     *   <li>A region with it set to DENY never repairs, even with the gamerule on world wide, so a build inside a
     *       repairing world can be left permanently changed.</li>
     *   <li>Anywhere with no opinion falls back to the gamerule, which is the whole world switch.</li>
     * </ul>
     *
     * <p>Region lookups are short circuited when no regions exist at all, so a server that uses none pays a single
     * emptiness check per captured block on top of the gamerule read it was already doing.
     */
    public static boolean isEnabledAt(Level level, BlockPos pos)
    {
        if (level == null || pos == null)
            return false;
        try
        {
            if (RegionEventHandler.hasRegions())
            {
                if (RegionEventHandler.worldFlagAllowed(level, pos, RegionFlag.TERRAIN_REGEN))
                    return true;
                if (RegionEventHandler.worldFlagDenied(level, pos, RegionFlag.TERRAIN_REGEN))
                    return false;
            }
        }
        catch (Throwable ignored)
        {
            // The regions module is optional in spirit even though it ships here. A failure to consult it must leave
            // the gamerule answering, not stop repair outright.
        }
        return isEnabled(level);
    }
}
