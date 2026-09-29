package net.shurui.shuruisutilities.npcregion;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Picks a spot on the surface INSIDE an {@link NpcRegion}: a real, visible, standable place that is not in a wall,
 * not underground, not under a roof and not (unless asked) afloat.
 *
 * <h2>Why this is its own class</h2>
 * The airdrop crate solved this problem first and solved it properly, over several live-server bug reports: the
 * WORLD_SURFACE rule that stops a crate being buried, the "never replace a block" rule, the sky check that keeps it
 * out from under overhangs, the preference for genuine ground over a treetop or a rooftop, and a last-resort landing
 * so a heavily built region can never silently swallow one. Dimensional tears need exactly the same answer to
 * exactly the same question, so the logic lives here and the airdrop calls it, rather than being copied and left to
 * drift apart the first time one of them is fixed.
 *
 * <p>Nothing here places anything or decides what goes at the spot; it only answers "where". It may LOAD a chunk
 * (and so generate terrain that had never been visited) once its no-load scan has failed, because a region with no
 * player in it is precisely where these events are supposed to fire. See {@link #FORCED_ATTEMPTS}.
 */
public final class RegionSurfacePicker {

    private RegionSurfacePicker() {
    }

    /** How many candidate columns are examined, and the hard loop cap for a mostly-unloaded region. */
    private static final int REAL_ATTEMPTS = 50;
    private static final int MAX_ITERATIONS = 200;

    /**
     * How many columns are tried with the chunk FORCE-LOADED, after the ordinary scan has found nothing.
     *
     * <h2>Why this exists</h2>
     * The ordinary scan deliberately never loads a chunk: 50 attempts spread over a large region would generate
     * terrain all over it for one event. The consequence, which was reported as "rifts and airdrops do not happen",
     * is that in a region NOBODY IS STANDING IN every column is unloaded, every attempt is skipped, the old
     * {@link #fallbackSpot} returned null on its unloaded centre chunk too, and the feature silently did nothing.
     * A tear that only opens where a player already is defeats the point of a tear.
     *
     * <p>So the no-load rule is kept for the cheap scan and dropped only once that scan has failed, for a SMALL
     * bounded number of columns. Eight is enough to find a spot in almost any region while capping the worst case
     * at eight chunk generations for an event that fires once every 45 to 90 minutes.
     */
    private static final int FORCED_ATTEMPTS = 8;

    /**
     * In a dimension with no skylight, how far above a spot is probed for a ceiling. {@code canSeeSky} is
     * meaningless there and would reject every column, so a short local headroom probe stands in for it.
     */
    private static final int NO_SKYLIGHT_HEADROOM = 4;

    /**
     * Tally of why candidate columns were rejected, for operator-facing diagnostics. The four counters are the four
     * ways {@link #findSpot} can refuse a column, and a breakdown of them is what tells an operator whether a region
     * is unusable because it is unloaded, flooded, built over or roofed.
     */
    public static final class Tally {
        public int unloadedChunk;
        public int fluid;
        public int noHeadroom;
        public int notOpenToSky;

        public String breakdown() {
            return "unloaded=" + unloadedChunk + ", fluid=" + fluid
                    + ", noHeadroom=" + noHeadroom + ", notOpenToSky=" + notOpenToSky;
        }
    }

    /**
     * The outcome of a full search.
     *
     * @param pos the chosen spot, or null when even the last-resort landing was impossible
     * @param secondChoice true when no column rested on genuine ground and a treetop, rooftop or water surface was
     *                     accepted instead
     * @param fallback true when every attempt failed and the region-centre last resort was used, which deliberately
     *                 ignores the water and overhead checks
     * @param attempts how many real attempts were spent (an unloaded column does not consume one)
     * @param breakdown the rejection tally, for logging
     */
    public record Search(BlockPos pos, boolean secondChoice, boolean fallback, int attempts, String breakdown) {
    }

    /**
     * Find a surface spot inside {@code region}, with the full attempt / second-choice / last-resort ladder.
     *
     * <p>The ladder matters and is not an implementation detail: a region that is all canopy, all rooftops or all
     * sea must still yield a spot, because a feature that silently does nothing is indistinguishable from a broken
     * one. What varies between callers is only whether a fluid surface is acceptable up front.
     *
     * @param allowWater whether a fluid surface may be chosen during the normal attempts (the last-resort landing
     *                   ignores this, as it ignores every other preference)
     */
    public static Search search(ServerLevel level, NpcRegion region, boolean allowWater) {
        Tally tally = new Tally();
        if (level == null || region == null || region.boxes.isEmpty()) {
            return new Search(null, false, false, 0, tally.breakdown());
        }
        int realAttempts = 0;
        // A spot that passed every placement rule but is not resting on real ground: a treetop, a roof, a wall, open
        // water. Held in case no attempt turns up anything better. A region with any ground in it lands there
        // instead of in the branches or afloat; one that genuinely has none still gets its spot.
        BlockPos secondChoice = null;
        for (int iter = 0; iter < MAX_ITERATIONS && realAttempts < REAL_ATTEMPTS; iter++) {
            int before = tally.unloadedChunk;
            BlockPos pos = findSpot(level, region, allowWater, tally);
            // An unloaded-chunk column is not a "real" attempt: retry without spending the budget.
            if (pos == null && tally.unloadedChunk == before + 1) {
                continue;
            }
            realAttempts++;
            if (pos == null) {
                continue;
            }
            if (restsOnGround(level, pos)) {
                return new Search(pos, false, false, realAttempts, tally.breakdown());
            }
            if (secondChoice == null) {
                secondChoice = pos;
            }
        }
        if (secondChoice != null) {
            return new Search(secondChoice, true, false, realAttempts, tally.breakdown());
        }
        // Nothing in the loaded part of the region worked, which in an empty region means nothing was even looked
        // at. Try again with the chunk loaded, so a region with no player in it still gets its event. These are
        // real attempts and are counted as such: they went through the identical placement rules.
        for (int i = 0; i < FORCED_ATTEMPTS; i++) {
            BlockPos pos = findSpot(level, region, allowWater, tally, true);
            realAttempts++;
            if (pos == null) {
                continue;
            }
            if (restsOnGround(level, pos)) {
                return new Search(pos, false, false, realAttempts, tally.breakdown());
            }
            if (secondChoice == null) {
                secondChoice = pos;
            }
        }
        if (secondChoice != null) {
            return new Search(secondChoice, true, false, realAttempts, tally.breakdown());
        }
        return new Search(fallbackSpot(level, region), false, true, realAttempts, tally.breakdown());
    }

    /**
     * One candidate column: area-weighted random X/Z inside the region, then the first air block above EVERYTHING
     * in that column (the WORLD_SURFACE heightmap).
     *
     * <p>The box only defines the X/Z footprint; the Y comes from the surface, NOT the box bounds, so the spot is
     * always on top of the ground rather than buried at the box floor. There is no descent scan and no
     * support-block requirement: whatever is underneath is acceptable, only the slot itself must be genuine air
     * with nothing overhead.
     */
    public static BlockPos findSpot(ServerLevel level, NpcRegion region, boolean allowWater, Tally tally) {
        return findSpot(level, region, allowWater, tally, false);
    }

    /**
     * As {@link #findSpot(ServerLevel, NpcRegion, boolean, Tally)}, but {@code forceLoad} decides what an unloaded
     * column means.
     *
     * @param forceLoad false: an unloaded column is a skipped attempt, and no chunk is ever generated (the cheap
     *                  scan). true: the column's chunk is loaded, generating it if it has never existed, and the
     *                  placement rules are then applied to it normally. See {@link #FORCED_ATTEMPTS}.
     */
    public static BlockPos findSpot(ServerLevel level, NpcRegion region, boolean allowWater, Tally tally,
                                    boolean forceLoad) {
        NpcRegion.Box b = pickBox(level, region);
        int x = b.minX + level.random.nextInt(Math.max(1, b.maxX - b.minX + 1));
        int z = b.minZ + level.random.nextInt(Math.max(1, b.maxZ - b.minZ + 1));
        if (!level.isLoaded(new BlockPos(x, level.getMinBuildHeight(), z))) {
            if (!forceLoad) {
                tally.unloadedChunk++;
                return null;
            }
            // Synchronous, blocking, and generating if the chunk has never existed. getChunk(int, int) is
            // ChunkStatus.FULL with require = true, so once it returns the heightmap and block reads below are
            // answering about real terrain rather than about an absent chunk. A ticket would not do: it schedules
            // a load for a later tick, and this method has to answer now.
            level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        }
        // WORLD_SURFACE, not MOTION_BLOCKING_NO_LEAVES: the spot must be above ALL non-air occupants, including
        // leaves, grass, snow layers, saplings and crops, so nothing can ever end up inside them.
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        y = Math.min(y, level.getMaxBuildHeight() - 2);
        BlockPos slot = new BlockPos(x, y, z);

        BlockState below = level.getBlockState(slot.below());
        if (!below.getFluidState().isEmpty() && !allowWater) {
            tally.fluid++;
            return null;
        }
        // The slot and the block above it must be GENUINE air, so nothing is ever replaced. The rare miss is a
        // floating block one above the surface, which is correctly refused.
        if (!level.getBlockState(slot).isAir() || !level.getBlockState(slot.above()).isAir()) {
            tally.noHeadroom++;
            return null;
        }
        if (!isOpenToSky(level, slot)) {
            tally.notOpenToSky++;
            return null;
        }
        return slot;
    }

    /**
     * Is the spot on the ground, or merely on the highest thing in the column?
     *
     * <p>WORLD_SURFACE puts the slot above every non-air occupant, which is what keeps it from being buried, but it
     * means the block holding it up is whatever happened to be topmost: leaves over a forest, roof slabs over a
     * town, open water over a lake. Preferred rather than required, because a region may legitimately have no bare
     * ground in it at all.
     */
    public static boolean restsOnGround(ServerLevel level, BlockPos slot) {
        return GroundBlocks.isPreferredGround(level.getBlockState(slot.below()));
    }

    /**
     * Nothing-overhead guard. In a skylit dimension the spot must literally see the sky, which is false under
     * terrain, an overhang, a cliff or a ceiling. In a dimension with no skylight that test is meaningless and
     * would reject every column, so a cheap local headroom probe stands in.
     */
    public static boolean isOpenToSky(ServerLevel level, BlockPos slot) {
        if (level.dimensionType().hasSkyLight()) {
            return level.canSeeSky(slot);
        }
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = Math.min(slot.getY() + NO_SKYLIGHT_HEADROOM, level.getMaxBuildHeight() - 1);
        for (int y = slot.getY() + 1; y <= top; y++) {
            m.set(slot.getX(), y, slot.getZ());
            if (level.getBlockState(m).blocksMotion()) {
                return false; // a solid ceiling just above: under an overhang or roof
            }
        }
        return true;
    }

    /**
     * Guaranteed-visible last resort when no random column passed: the primary box centre, on the topmost surface.
     * Still above every non-air block, so it is never buried and never replaces one, but it deliberately ignores
     * the water and overhead checks so a heavily built or canopied region cannot swallow the event entirely.
     *
     * <p>The centre chunk is LOADED if it is not already, generating it if it has never existed. This used to
     * return null instead, which made the "guaranteed" last resort guarantee nothing in the exact case it was
     * written for: a region with no player anywhere near it. One chunk is a cost worth paying to stop an event
     * from silently not happening.
     */
    public static BlockPos fallbackSpot(ServerLevel level, NpcRegion region) {
        NpcRegion.Box b = region.primary();
        int x = (b.minX + b.maxX) / 2;
        int z = (b.minZ + b.maxZ) / 2;
        if (!level.isLoaded(new BlockPos(x, level.getMinBuildHeight(), z))) {
            level.getChunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z));
        }
        int ceiling = level.getMaxBuildHeight() - 2;
        int y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
        if (y <= ceiling) {
            return new BlockPos(x, y, z);
        }
        // The column is built to the build limit, so the surface is above the highest usable slot. Clamping to the
        // ceiling would be the one path that hands back a Y INSIDE solid blocks; walk down for the first genuine
        // air slot with something under it instead, so the spot still sits on top of something.
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y2 = ceiling; y2 > level.getMinBuildHeight(); y2--) {
            m.set(x, y2, z);
            if (level.getBlockState(m).isAir() && !level.getBlockState(m.below()).isAir()) {
                return new BlockPos(x, y2, z);
            }
        }
        return null;
    }

    /** Weighted by X/Z area, so spots spread evenly over a multi-selection region instead of favouring small boxes. */
    public static NpcRegion.Box pickBox(ServerLevel level, NpcRegion region) {
        if (region.boxes.size() == 1) {
            return region.primary();
        }
        long total = 0;
        for (NpcRegion.Box b : region.boxes) {
            total += Math.max(1, b.areaXZ());
        }
        long roll = (long) (level.random.nextDouble() * total);
        for (NpcRegion.Box b : region.boxes) {
            roll -= Math.max(1, b.areaXZ());
            if (roll < 0) {
                return b;
            }
        }
        return region.primary();
    }
}
