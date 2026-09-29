package net.shurui.shuruisutilities.space;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

/**
 * Deterministic, order-independent "no two bodies of the same kind spawn inside each other" contest, shared by the
 * per-cell derivations ({@link GeneratedPlanets}, {@link StarPositions}, {@link BlackHolePositions}) so all three use
 * ONE implementation rather than three drift-prone copies. Every space body is a pure function of its cell, derived
 * independently, so two bodies in ADJACENT cells can land close to their shared face and overlap even though each cell
 * holds at most one body (the old "the sector is far larger than any body" claim only rules out WITHIN-cell overlap,
 * never cross-cell). This class closes that gap without breaking the pure-derivation model.
 *
 * <h3>How it stays deterministic and terminating</h3>
 * Each cell derives a RAW candidate (its full gating MINUS this contest). A candidate SURVIVES iff no raw-existing
 * neighbour whose cube overlaps it OUTRANKS it, where rank is a strict total order over cells: the cell hash first, then
 * the cell coordinates as a tiebreak. Because rank is a pure function of the cells, of any two overlapping candidates
 * exactly one outranks the other and exactly one yields, identically on the client and the server and regardless of the
 * order cells are visited. It cannot recurse or loop: the contest only inspects RAW neighbour candidates (never their own
 * contest result), and only the 26 immediate neighbours can possibly reach across (a body two cells away is at least one
 * whole sector clear, far past any body radius), pruned further to just the faces the body is actually near.
 *
 * <p>GUARANTEE. If a body survives, every raw-existing neighbour that overlaps it has a lower rank, so each of those
 * neighbours sees this higher-ranked overlapping body and yields. Therefore a surviving body has no surviving overlapping
 * neighbour: no two same-kind bodies can occupy overlapping space. A body that yields simply does not exist, which is a
 * deterministic, bounded outcome (never a spin loop): the field is a touch sparser exactly where two cells collided.
 */
final class CellOverlap
{
    private CellOverlap()
    {
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    // one-shot-per-kind diagnostic so the FIRST rejected overlap of each body kind is visible in the log without spamming
    // the hot derivation path. Kinds already logged live here; a kind logs its first yield exactly once per JVM.
    private static final Set<String> LOGGED_KINDS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    /**
     * A raw candidate occupying a cell: its cube centre, its cube half-extent, the deterministic rank (cell hash then
     * cell coordinates) and the cell it came from. {@code radius} is the OVERLAP half-extent to contest on, which may be
     * padded past the visual radius when a body's "personal space" is larger than what it draws (a black hole's pull).
     */
    record Cand(double x, double y, double z, double radius, long priority, int cx, int cy, int cz)
    {
    }

    /** Supplies the raw candidate occupying a cell, WITHOUT that cell running its own contest, or null if the cell is empty. */
    interface Provider
    {
        Cand at(int cx, int cy, int cz);
    }

    // strict total order over distinct cells: higher cell hash wins; on the astronomically rare equal-hash tie the higher
    // (cx,cy,cz) lexicographically wins. Distinct cells always differ in at least one coordinate, so this never ties.
    private static boolean outranks(Cand a, Cand b)
    {
        if (a.priority != b.priority)
        {
            return a.priority > b.priority;
        }
        if (a.cx != b.cx)
        {
            return a.cx > b.cx;
        }
        if (a.cy != b.cy)
        {
            return a.cy > b.cy;
        }
        return a.cz > b.cz;
    }

    /**
     * True if {@code me} SURVIVES its same-kind overlap contest: it yields (returns false) iff a raw-existing neighbour
     * whose cube overlaps it outranks it. {@code maxNeighborRadius} is the largest overlap half-extent any neighbour of
     * this kind can have, used only to PRUNE which of the 26 neighbours are worth deriving (a body far from every cell
     * face has none). {@code kind} names the body type for the one-shot diagnostic.
     */
    static boolean survives(Cand me, int sector, double maxNeighborRadius, String kind, Provider provider)
    {
        double reach = me.radius + maxNeighborRadius;
        boolean xLo = me.x - (double) me.cx * sector < reach;
        boolean xHi = (double) (me.cx + 1) * sector - me.x < reach;
        boolean yLo = me.y - (double) me.cy * sector < reach;
        boolean yHi = (double) (me.cy + 1) * sector - me.y < reach;
        boolean zLo = me.z - (double) me.cz * sector < reach;
        boolean zHi = (double) (me.cz + 1) * sector - me.z < reach;
        for (int dx = -1; dx <= 1; ++dx)
        {
            if ((dx < 0 && !xLo) || (dx > 0 && !xHi))
            {
                continue;
            }
            for (int dy = -1; dy <= 1; ++dy)
            {
                if ((dy < 0 && !yLo) || (dy > 0 && !yHi))
                {
                    continue;
                }
                for (int dz = -1; dz <= 1; ++dz)
                {
                    if ((dz < 0 && !zLo) || (dz > 0 && !zHi) || (dx == 0 && dy == 0 && dz == 0))
                    {
                        continue;
                    }
                    Cand n = provider.at(me.cx + dx, me.cy + dy, me.cz + dz);
                    if (n == null)
                    {
                        continue;
                    }
                    double half = me.radius + n.radius;
                    if (Math.abs(me.x - n.x) < half && Math.abs(me.y - n.y) < half && Math.abs(me.z - n.z) < half
                            && outranks(n, me))
                    {
                        logFirstYield(kind, me, n);
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static void logFirstYield(String kind, Cand me, Cand winner)
    {
        if (!LOGGED_KINDS.add(kind))
        {
            return;
        }
        LOGGER.info("[SU] space overlap rejection ({}): a body at cell [{},{},{}] pos ({},{},{}) r={} overlapped a "
                        + "higher-ranked body at cell [{},{},{}] and yielded (deterministic, not spawned). Further "
                        + "rejections of this kind are not logged.",
                kind, me.cx, me.cy, me.cz, (long) me.x, (long) me.y, (long) me.z, (long) me.radius,
                winner.cx, winner.cy, winner.cz);
    }
}
