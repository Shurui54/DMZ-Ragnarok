package net.shurui.shuruisutilities.worldborder;

import net.shurui.shuruisutilities.commons.selections.Point;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Seeds a default 4000x4000 boundary for the two imported dimensions {@code dmz_ragnarok:namekow} and
 * {@code dmz_ragnarok:kaiow}.
 *
 * <p>The border is the INNER of the two limits those dimensions have. Their generator only produces terrain inside
 * a 4096x4096 box and void outside it (see {@code BoundedChunkGenerator}), and this border sits inside that edge
 * on every side (48 blocks on kaiow, 43 to 52 on namekow, whose box is nudged onto a chunk boundary), so a player
 * is turned back while there is still ground under them rather than at the cliff. The border is what a player runs into; the box is what stops the world existing past it.
 *
 * <p>SU's own {@link WorldBorder} system is used rather than the vanilla per-level world border. The vanilla
 * route is unusable here: {@code MultiworldManager} attaches a {@code DelegateBorderChangeListener} from the
 * overworld onto every level, and 1.20.1 offers no clean way to detach it, so any per-level vanilla size set
 * at load is overwritten whenever the overworld border changes. SU's WorldBorder is an independent per-dimension
 * box that {@code ModuleWorldBorder} enforces on {@code PlayerMoveEvent} (it clamps a player back inside when the
 * border is enabled), which needs nothing from the vanilla border machinery.
 *
 * <p>Design guarantees, mirroring {@code PlanetRegionSeeder}:
 * <ul>
 *   <li><b>Seed if absent only.</b> An existing WorldBorder record for the dimension is never overwritten, so
 *       an admin who has resized the border by hand keeps their setting.</li>
 *   <li><b>Fail soft.</b> Any {@link Throwable} is logged on one line and startup continues; a failed seed must
 *       never block the server from starting.</li>
 *   <li><b>Idempotent.</b> After the first boot each record exists, so later boots are a no-op.</li>
 * </ul>
 *
 * <p>Runs from {@code ServerAboutToStartEvent} (see {@code ShuruisUtilities.serverPreInit}), right after the
 * DataManager instance is set and before any level loads, so the record is on disk before
 * {@code ModuleWorldBorder} reads it at {@code LevelEvent.Load}.
 */
public final class DimensionBorderSeeder
{
    private DimensionBorderSeeder()
    {
    }

    // half-extent in blocks: a value of 2000 makes a 4000x4000 box (center +/- 2000 on each axis).
    private static final int HALF_EXTENT = 2000;

    private record Bound(String dimId, Point center)
    {
    }

    // These centres are fixed by the TERRAIN BOX, not by where players arrive, and the two are no longer the
    // same thing. namekow's imported patch runs 176,800 to 4271,4895, so a 2000 half-extent border only fits
    // with its centre between x 2176-2271 and z 2800-2895: 2226/2852 is inside that window and cannot move far
    // without pushing an edge past the terrain and dropping players at the cliff.
    //
    // The space-pod spawn USED to sit here too, which is why it reads as a spawn coordinate. It was moved on
    // 2026-09-09 (destinations.json namekow x/z = 1976/1784) because 2226/2852 sits on the boundary between the
    // fully generated north-west landmass and the holed south-east, so arrivals looked like they had landed at
    // the edge of the world. Do not "resynchronise" these two numbers: the border centre is geometry, the spawn
    // is where the land is good. kaiow's centre stays at origin, which its box is symmetric about.
    private static final Bound[] BOUNDS = {
        new Bound("dmz_ragnarok:namekow", new Point(2226, 0, 2852)),
        new Bound("dmz_ragnarok:kaiow", new Point(0, 0, 0)),
    };

    /**
     * Seeds every default dimension boundary. Never throws: any failure is logged and swallowed so the server
     * keeps starting. The DataManager instance must already be set (it is, by the time serverPreInit reaches
     * this call).
     */
    public static void seedDefaults()
    {
        for (Bound bound : BOUNDS)
        {
            try
            {
                String storageKey = bound.dimId().replace(":", "-");

                // seed-if-absent: never clobber an admin's hand-set border.
                WorldBorder existing = DataManager.getInstance().load(WorldBorder.class, storageKey);
                if (existing != null)
                {
                    LoggingHandler.sulog.info("[BorderSeeder] {}: border record already present; leaving it alone",
                            bound.dimId());
                    continue;
                }

                WorldBorder border = new WorldBorder(bound.center(), HALF_EXTENT, HALF_EXTENT, bound.dimId());
                border.setEnabled(true);
                border.save();
                LoggingHandler.sulog.info("[BorderSeeder] {}: seeded default {}x{} border centred at {},{}",
                        bound.dimId(), HALF_EXTENT * 2, HALF_EXTENT * 2,
                        bound.center().getX(), bound.center().getZ());
            }
            catch (Throwable t)
            {
                // per-dimension fail soft: a broken seed does not stop the other, nor the server.
                LoggingHandler.sulog.error("[BorderSeeder] {}: seeding failed; continuing startup: {}",
                        bound.dimId(), t.toString());
            }
        }
    }
}
