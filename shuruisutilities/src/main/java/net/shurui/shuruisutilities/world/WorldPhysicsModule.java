package net.shurui.shuruisutilities.world;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;

/**
 * Two terrain rules for the hand-built overworld: sand does not fall, and fluids do not spread.
 *
 * <h2>Why the map needs this</h2>
 * The imported city map is built, not generated. Its sand roofs, gravel paths and decorative water are placed
 * where a builder wanted them, with nothing underneath holding them up and no source-block logic behind them.
 * Vanilla physics treats all of that as unstable the moment anything pokes it: a block update sweep, a chunk
 * reload or a stray explosion collapses sand and lets water run through the streets. Freezing both keeps the
 * map looking the way it was drawn.
 *
 * <h2>Overworld only</h2>
 * Deliberately limited to the main world, because the other dimensions are generated and want ordinary physics.
 * Nothing here changes how a block is placed or broken, only whether it MOVES on its own afterwards, so
 * building with sand and water still works normally.
 */
public final class WorldPhysicsModule
{
    // Toggles, baked from SUConfig (HoldOverworldFallingBlocks / HoldOverworldFluids) by SUConfig.bakeConfig.
    //
    // These default to FALSE, and that is the fix for a real shipped bug: they used to default to TRUE with a
    // comment claiming they were "wired to config in bakeConfig", except bake() was never called from anywhere. So
    // every overworld froze its water, lava, sand and gravel, in singleplayer and on third-party servers alike,
    // with no way to turn it off. Holding the map still is what ONE hand-built imported overworld needs, not a
    // property of the mod, so the safe default is vanilla physics and an operator opts in. Keep it that way: if
    // the config ever fails to bake, failing to vanilla behaviour is recoverable and freezing somebody's world is
    // not.
    private static boolean holdFallingBlocks = false;
    private static boolean holdFluids = false;

    private WorldPhysicsModule()
    {
    }

    /** True when sand and gravel in this level should stay where they are. */
    public static boolean holdFallingBlocks(LevelReader level)
    {
        return holdFallingBlocks && isMainWorld(level);
    }

    /** True when fluids in this level should not flow outward. */
    public static boolean holdFluids(LevelReader level)
    {
        return holdFluids && isMainWorld(level);
    }

    // Only the overworld. A LevelReader is accepted because the fluid path hands us one rather than a Level,
    // and anything that is not a server Level (a structure template, a lookup view) is left to behave normally.
    private static boolean isMainWorld(LevelReader level)
    {
        if (!(level instanceof Level real))
        {
            return false;
        }
        return real.dimension() == Level.OVERWORLD;
    }

    public static void bake(boolean sand, boolean fluids)
    {
        holdFallingBlocks = sand;
        holdFluids = fluids;
    }
}
