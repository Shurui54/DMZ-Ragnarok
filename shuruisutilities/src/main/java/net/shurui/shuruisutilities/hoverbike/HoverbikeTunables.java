package net.shurui.shuruisutilities.hoverbike;

// per-variant visual/seat tuning, 1-4 (index 0 unused). single tuning point since OBJ export scales/origins
// vary per model. entity (positionRider) needs seat offsets; renderer needs scale + ground-clearance offset.
//
// OBJ models exported origin-centered so model units == world units. scales are user-tuned in-game (not a
// normalization formula), the bikes are meant to read large. measured bboxes (model units):
//   Mk1: min=(-0.6794,0.0457,-0.2009) max=(0.6462,0.4912,0.1807)  h=0.4455 longAxis=X 1.3256 center=(-0.0166,-0.0101)
//   Mk2: min=(-0.2738,0.0460,-0.7631) max=(0.2862,0.8472,0.8549)  h=0.8011 longAxis=Z 1.6180 center=( 0.0062, 0.0459)
//   Mk3: min=(-0.5854,0.0284,-0.1950) max=(0.5898,0.4643,0.2536)  h=0.4359 longAxis=X 1.1752 center=( 0.0022, 0.0293)
//   Mk4: min=(-0.6543,0.0351,-0.2383) max=(0.6399,0.5206,0.3491)  h=0.4855 longAxis=X 1.2943 center=(-0.0072, 0.0554)
// RENDER_Y_OFFSET = -minY*SCALE (lift so lowest vertex rests on origin). it's a world-space translate: the
//   renderer's translate-then-scale source is effectively post-scale under PoseStack order, hence the *SCALE.
// MODEL_CENTER_X/Z = OBJ bbox center; renderer translates by the negation (model space, pre-rotation) so it
//   centers on origin. precedes yaw so it's orientation-independent.
// SEAT_OFFSET = {x,y,z} entity-local; positionRider rotates x/z by yaw, adds y to world Y. x=0 (centered),
//   y = RENDER_Y_OFFSET + 0.62*height*scale - SEAT_LOWER + SEAT_ADJUST_Y, z = -(0.12*longAxis*scale) + SEAT_ADJUST_Z.
// yaw: renderer assumes models are Z-long. v1/3/4 are X-long (need 90) and flipped end-for-end in-game (+180) =
//   270; v2 is Z-long and faces right, so 0.
// gotcha: seatZ sign assumes the bike faces +Z (-Z = tail). if a model faces the other way, flip the seatZ sign.
public final class HoverbikeTunables
{
    private HoverbikeTunables() {}

    // uniform render scale per variant, user-tuned in-game (index 0 unused)
    public static final float[] SCALE = { 1.0F, 2.55F, 1.9125F, 3.0F, 3.0F };

    // world-space Y translate (= -minY*SCALE, effectively post-scale) so each lowest vertex rests on the origin
    public static final double[] RENDER_Y_OFFSET = { 0.0D, -0.116535D, -0.087975D, -0.0852D, -0.1053D };

    // OBJ bbox center X/Z; renderer translates by the negation (model space, pre-rotation) to center on origin
    public static final float[] MODEL_CENTER_X = { 0.0F, -0.0166F, 0.0062F, 0.0022F, -0.0072F };
    public static final float[] MODEL_CENTER_Z = { 0.0F, -0.0101F, 0.0459F, 0.0293F, 0.0554F };

    // uniform seat drop below the formula saddle Y; a resulting seat below ~0.15 is intended (low seat)
    public static final float SEAT_LOWER = 0.5F;

    // per-variant seat Y/Z nudges on top of the formula (-Z = rearward). v3/v4 unnudged. index 0 unused.
    public static final double[] SEAT_ADJUST_Y = { 0.0D, +0.125D, -0.125D, 0.0D, 0.0D };
    public static final double[] SEAT_ADJUST_Z = { 0.0D, -0.5D, -0.5D, 0.0D, 0.0D };

    // {x,y,z} entity-local seat, x/z rotated by yaw in positionRider. see class note for the y/z formula.
    public static final double[][] SEAT_OFFSET = {
            { 0.0D, 0.60D - SEAT_LOWER, 0.0D },                                                   // 0 unused
            { 0.0D, 0.587801D - SEAT_LOWER + SEAT_ADJUST_Y[1], -0.405634D + SEAT_ADJUST_Z[1] },  // Mk1  (formula Y 0.587801, Z -0.405634)
            { 0.0D, 0.861929D - SEAT_LOWER + SEAT_ADJUST_Y[2], -0.371331D + SEAT_ADJUST_Z[2] },  // Mk2  (formula Y 0.861929, Z -0.371331)
            { 0.0D, 0.725574D - SEAT_LOWER + SEAT_ADJUST_Y[3], -0.423072D + SEAT_ADJUST_Z[3] },  // Mk3  (formula Y 0.725574, Z -0.423072, confirmed GOOD)
            { 0.0D, 0.797730D - SEAT_LOWER + SEAT_ADJUST_Y[4], -0.465948D + SEAT_ADJUST_Z[4] },  // Mk4  (formula Y 0.797730, Z -0.465948, confirmed GOOD)
    };

    // jump vy (blocks/tick), clears ~1.25 blocks, no mid-air re-jump
    public static final double JUMP_POWER = 0.55D;

    // per-variant yaw correction after the renderer's -entityYaw+180. see class note (v1/3/4 = 270, v2 = 0).
    public static final float[] MODEL_YAW_OFFSET_DEG = { 0.0F, 270.0F, 0.0F, 270.0F, 270.0F };
}
