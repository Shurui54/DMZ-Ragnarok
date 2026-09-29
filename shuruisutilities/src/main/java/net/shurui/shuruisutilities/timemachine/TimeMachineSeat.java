package net.shurui.shuruisutilities.timemachine;

/**
 * Where the pilot sits, and how big the machine is drawn. These are the two hand-tuned numbers a launch test will
 * want to nudge, kept in one place so the entity's {@code positionRider} and the client renderer agree.
 *
 * <p>Derivation, from the authoritative Blockbench GeckoLib export (dbz_trunksTimeMechene_final). The geo shipped at
 * {@code geo/entity/time_machine.geo.json} has already been recentred by a rigid translate (baked into every cube
 * origin and every bone/cube pivot) so that, after GeckoLib's own X-mirror and rotation handling, the RENDERED model
 * is centred on x/z and feet-at-origin: it spans x -1.72..1.72, y 0..5.85, z -1.59..1.59 WORLD blocks at natural
 * scale. (GeckoLib mirrors raw model X to world X and applies bone/cube rotation as -rx,-ry,+rz, so the true rendered
 * silhouette is NOT the raw cube extents; every number here was measured off the rendered geometry, not the file.)
 * That 3.4 x 5.85 x 3.2 block hull is large for a one-seat capsule, so the rideable entity draws it at
 * {@link #RENDER_SCALE}; the decorative multiblock draws it life-size.
 *
 * <p>The seat comes from the export's own {@code seat} bone (pivot 6,68,2 in raw units; the artist's stated sit
 * point), which lands high in the canopy: after recentre and X-mirror the seat cushion sits at world (-0.41, 5.16,
 * -0.22) blocks at natural scale, i.e. about 88% up the hull. At {@link #RENDER_SCALE} that is the values below, so a
 * full-size pilot sits on the cushion with their upper body above the open canopy (the DBZ look). All three are
 * WORLD-space blocks relative to the entity origin (its feet) and already account for the render scale. These and
 * {@link #RENDER_SCALE} are the launch-test nudge knobs: raise/lower {@link #Y} to seat the pilot on the visible
 * cushion, {@link #Z} to slide fore/aft, {@link #X} to centre them left/right.
 */
public final class TimeMachineSeat
{
    private TimeMachineSeat() {}

    /** Render + hitbox scale applied to the natural geo (see the class note). */
    public static final float RENDER_SCALE = 0.6F;

    // seat, in blocks, relative to the entity origin (feet, centred), derived from the export's seat bone at
    // RENDER_SCALE. Nudge after a launch test.
    public static final double X = -0.25D;
    public static final double Y = 3.1D;
    public static final double Z = -0.13D;
}
