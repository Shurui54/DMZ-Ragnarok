package net.shurui.shuruisutilities.racing.tuning;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import net.shurui.shuruisutilities.racing.physics.PowerupKind;
import net.shurui.shuruisutilities.racing.physics.RaceDriveParams;

/**
 * The editable tuning payload for the race tuning screen (opened by packet 120, saved by packet 121). It bundles
 * the shared kart-physics numbers ({@link RaceDriveParams}) with the race-wide session defaults and the item-box
 * odds grid, so an operator can adjust feel, pacing and item luck without a rebuild (owner rule: content is
 * configurable in game).
 *
 * <p>The odds grid is {@code [kind][bucket]} weights: one row per real {@link PowerupKind} ordinal (0..{@link
 * #KINDS}-1, {@link PowerupKind#NONE} excluded) and one column per finishing bucket (0 = front runner, {@link
 * #BUCKETS}-1 = back marker, the bucket being {@code ceil(position / racers * BUCKETS)}). The key rolls a box against
 * this table; the numbers are DATA and live once here in core so the DTO, the persisted store and the roll all read
 * the same defaults. Kept comfortably under the 32767-byte serverbound ceiling that 121 must respect ({@code KINDS *
 * BUCKETS} varints plus a handful of scalars).
 */
public final class RaceTuningDto
{
    /** Buckets a finishing position is quantised into for the odds table (front .. back). */
    public static final int BUCKETS = 8;
    /** Real powerup kinds in the odds table ({@link PowerupKind} minus {@link PowerupKind#NONE}). */
    public static final int KINDS = PowerupKind.values().length - 1;

    /** The shared kart-physics numbers every racing bike drives on. */
    public RaceDriveParams drive = new RaceDriveParams();

    /** Default maximum racers per race (owner default 12). */
    public int maxRacers = 12;
    /** Default lap count when a race is opened without an explicit count. */
    public int defaultLaps = 3;
    /** Default lobby countdown, seconds. */
    public int lobbySeconds = 30;
    /** Roulette spin length before the held item settles, ticks. */
    public int rouletteTicks = 50;
    /** Ticks a rescued racer is frozen before regaining control. */
    public int rescuePauseTicks = 30;
    /** How far off the road (blocks, beyond half width) a racer may stray before rescue triggers. */
    public double rescueDistance = 6.0;
    /** Seconds at the start of a race during which a Spirit Bomb may not be used. */
    public int spiritBombLockSeconds = 30;
    /** Ticks an item box stays hidden after a pickup before it pops back in. */
    public int itemRespawnTicks = 40;

    /** The item-box odds grid {@code [kind][bucket]}, seeded from {@link #defaultOdds()}. */
    public int[][] odds = defaultOdds();

    public RaceTuningDto() {}

    /**
     * The built-in odds grid, tuned to Mario Kart (owner-approved). Front buckets (leaders) lean to Ki Blast / Ki
     * Mine / Fake Dragon Ball / Zeni / Kiai; the middle to Senzu / Kaioken x3 / Hellzone; the back (trailers) to
     * Flying Nimbus / Kaioken x20 / Destroyer Aura / Gravity Crush / Spirit Bomb. Rows are in {@link PowerupKind}
     * ordinal order; every bucket column sums to 200, so a roll in any bucket always resolves. These are DEFAULTS:
     * an existing saved dmzr_race_tuning.dat keeps its own grid (the store is not re-seeded), so this is not
     * retroactive.
     */
    public static int[][] defaultOdds()
    {
        int[][] o = new int[KINDS][BUCKETS];
        o[PowerupKind.DESTROYER_AURA.ordinal()] = new int[] { 0, 0, 5, 5, 30, 45, 35, 30 };
        o[PowerupKind.SENZU.ordinal()]          = new int[] { 5, 30, 40, 55, 30, 10, 0, 0 };
        o[PowerupKind.KAIOKEN_X3.ordinal()]     = new int[] { 0, 0, 15, 60, 80, 60, 35, 10 };
        o[PowerupKind.KAIOKEN_X20.ordinal()]    = new int[] { 0, 0, 0, 0, 25, 40, 55, 60 };
        o[PowerupKind.KI_BLAST.ordinal()]       = new int[] { 50, 50, 50, 40, 0, 0, 0, 0 };
        o[PowerupKind.HELLZONE.ordinal()]       = new int[] { 5, 55, 45, 30, 10, 0, 0, 0 };
        o[PowerupKind.SPIRIT_BOMB.ordinal()]    = new int[] { 0, 0, 0, 0, 5, 5, 5, 0 };
        o[PowerupKind.KI_MINE.ordinal()]        = new int[] { 55, 25, 15, 0, 0, 0, 0, 0 };
        o[PowerupKind.SAIBAMAN.ordinal()]       = new int[] { 0, 10, 15, 5, 0, 0, 0, 0 };
        o[PowerupKind.FAKE_BALL.ordinal()]      = new int[] { 10, 10, 5, 0, 0, 0, 0, 0 };
        o[PowerupKind.GRAVITY_CRUSH.ordinal()]  = new int[] { 0, 0, 0, 0, 0, 5, 10, 15 };
        o[PowerupKind.SOLAR_FLARE.ordinal()]    = new int[] { 0, 0, 0, 5, 5, 0, 0, 0 };
        o[PowerupKind.NIMBUS.ordinal()]         = new int[] { 0, 0, 0, 0, 10, 30, 60, 85 };
        o[PowerupKind.AFTERIMAGE.ordinal()]     = new int[] { 0, 0, 0, 0, 5, 5, 0, 0 };
        o[PowerupKind.KIAI.ordinal()]           = new int[] { 5, 5, 5, 0, 0, 0, 0, 0 };
        o[PowerupKind.ZENI.ordinal()]           = new int[] { 70, 15, 5, 0, 0, 0, 0, 0 };
        return o;
    }

    /** Deep copy of an odds grid, clamped to the {@link #KINDS} x {@link #BUCKETS} shape and to non-negative weights. */
    public static int[][] copyOdds(int[][] src)
    {
        int[][] o = new int[KINDS][BUCKETS];
        for (int k = 0; k < KINDS; k++)
            for (int b = 0; b < BUCKETS; b++)
                o[k][b] = src != null && k < src.length && src[k] != null && b < src[k].length
                        ? Math.max(0, src[k][b]) : 0;
        return o;
    }

    /** Write the full tuning block. Fixed field order is the wire contract. */
    public void encode(FriendlyByteBuf buf)
    {
        drive.encode(buf);
        buf.writeVarInt(maxRacers);
        buf.writeVarInt(defaultLaps);
        buf.writeVarInt(lobbySeconds);
        buf.writeVarInt(rouletteTicks);
        buf.writeVarInt(rescuePauseTicks);
        buf.writeDouble(rescueDistance);
        buf.writeVarInt(spiritBombLockSeconds);
        buf.writeVarInt(itemRespawnTicks);
        int[][] o = copyOdds(odds);
        for (int k = 0; k < KINDS; k++)
            for (int b = 0; b < BUCKETS; b++)
                buf.writeVarInt(o[k][b]);
    }

    public static RaceTuningDto decode(FriendlyByteBuf buf)
    {
        RaceTuningDto t = new RaceTuningDto();
        t.drive = RaceDriveParams.decode(buf);
        t.maxRacers = buf.readVarInt();
        t.defaultLaps = buf.readVarInt();
        t.lobbySeconds = buf.readVarInt();
        t.rouletteTicks = buf.readVarInt();
        t.rescuePauseTicks = buf.readVarInt();
        t.rescueDistance = buf.readDouble();
        t.spiritBombLockSeconds = buf.readVarInt();
        t.itemRespawnTicks = buf.readVarInt();
        int[][] o = new int[KINDS][BUCKETS];
        for (int k = 0; k < KINDS; k++)
            for (int b = 0; b < BUCKETS; b++)
                o[k][b] = buf.readVarInt();
        t.odds = o;
        return t;
    }

    /**
     * Write the tuning as a canonical, deterministic NBT compound (fixed key order, the odds grid as a flat int
     * array in {@code [kind][bucket]} order), so the persisted {@code RaceTuningStore} and its cross-shard sync are
     * byte-stable regardless of map iteration order.
     */
    public CompoundTag writeNbt()
    {
        CompoundTag t = new CompoundTag();
        // The shared kart-physics numbers, as a canonical sub-compound (fixed key order for a byte-stable hash). The
        // screen now edits these (R10), so they must persist and travel; older tuning files without it load the
        // defaults through readNbt's tolerant read.
        t.put("drive", writeDriveNbt(drive != null ? drive : new RaceDriveParams()));
        t.putInt("maxRacers", maxRacers);
        t.putInt("defaultLaps", defaultLaps);
        t.putInt("lobbySeconds", lobbySeconds);
        t.putInt("rouletteTicks", rouletteTicks);
        t.putInt("rescuePauseTicks", rescuePauseTicks);
        t.putDouble("rescueDistance", rescueDistance);
        t.putInt("spiritBombLockSeconds", spiritBombLockSeconds);
        t.putInt("itemRespawnTicks", itemRespawnTicks);
        int[][] o = copyOdds(odds);
        int[] flat = new int[KINDS * BUCKETS];
        for (int k = 0; k < KINDS; k++)
            System.arraycopy(o[k], 0, flat, k * BUCKETS, BUCKETS);
        t.putIntArray("odds", flat);
        return t;
    }

    /** Read a tuning compound written by {@link #writeNbt()}; any absent key keeps this instance's current value. */
    public void readNbt(CompoundTag t)
    {
        if (t == null)
            return;
        if (t.contains("drive")) drive = readDriveNbt(t.getCompound("drive"));
        if (t.contains("maxRacers")) maxRacers = t.getInt("maxRacers");
        if (t.contains("defaultLaps")) defaultLaps = t.getInt("defaultLaps");
        if (t.contains("lobbySeconds")) lobbySeconds = t.getInt("lobbySeconds");
        if (t.contains("rouletteTicks")) rouletteTicks = t.getInt("rouletteTicks");
        if (t.contains("rescuePauseTicks")) rescuePauseTicks = t.getInt("rescuePauseTicks");
        if (t.contains("rescueDistance")) rescueDistance = t.getDouble("rescueDistance");
        if (t.contains("spiritBombLockSeconds")) spiritBombLockSeconds = t.getInt("spiritBombLockSeconds");
        if (t.contains("itemRespawnTicks")) itemRespawnTicks = t.getInt("itemRespawnTicks");
        if (t.contains("odds"))
        {
            int[] flat = t.getIntArray("odds");
            int[][] o = new int[KINDS][BUCKETS];
            for (int k = 0; k < KINDS; k++)
                for (int b = 0; b < BUCKETS; b++)
                {
                    int i = k * BUCKETS + b;
                    o[k][b] = i < flat.length ? Math.max(0, flat[i]) : 0;
                }
            odds = o;
        }
    }

    // The kart-physics params as canonical NBT (fixed key order + fixed array shapes), so the tuning NBT stays
    // byte-stable for the persisted store and the cross-shard sync.
    private static CompoundTag writeDriveNbt(RaceDriveParams p)
    {
        CompoundTag d = new CompoundTag();
        d.putDouble("topSpeed", p.topSpeed);
        d.putDouble("accel", p.accel);
        d.putDouble("brake", p.brake);
        d.putDouble("reverseFraction", p.reverseFraction);
        d.putDouble("steerRateLow", p.steerRateLow);
        d.putDouble("steerRateHigh", p.steerRateHigh);
        d.putDouble("grip", p.grip);
        d.putDouble("driftMinFraction", p.driftMinFraction);
        d.putDouble("driftGripFactor", p.driftGripFactor);
        d.putDouble("hopImpulse", p.hopImpulse);
        d.putDouble("offroadMult", p.offroadMult);
        d.putDouble("boostMult", p.boostMult);
        d.putDouble("wallBumpFactor", p.wallBumpFactor);
        d.putDouble("miniTurboMult1", mt(p.miniTurboMult, 1, 1.12));
        d.putDouble("miniTurboMult2", mt(p.miniTurboMult, 2, 1.25));
        d.putDouble("miniTurboMult3", mt(p.miniTurboMult, 3, 1.4));
        d.putInt("miniTurboTicks1", (int) mt(p.miniTurboTicks, 1, 12));
        d.putInt("miniTurboTicks2", (int) mt(p.miniTurboTicks, 2, 22));
        d.putInt("miniTurboTicks3", (int) mt(p.miniTurboTicks, 3, 34));
        d.putInt("rocketStartWindow", p.rocketStartWindow);
        return d;
    }

    private static RaceDriveParams readDriveNbt(CompoundTag d)
    {
        RaceDriveParams p = new RaceDriveParams();
        if (d == null)
            return p;
        if (d.contains("topSpeed")) p.topSpeed = d.getDouble("topSpeed");
        if (d.contains("accel")) p.accel = d.getDouble("accel");
        if (d.contains("brake")) p.brake = d.getDouble("brake");
        if (d.contains("reverseFraction")) p.reverseFraction = d.getDouble("reverseFraction");
        if (d.contains("steerRateLow")) p.steerRateLow = d.getDouble("steerRateLow");
        if (d.contains("steerRateHigh")) p.steerRateHigh = d.getDouble("steerRateHigh");
        if (d.contains("grip")) p.grip = d.getDouble("grip");
        if (d.contains("driftMinFraction")) p.driftMinFraction = d.getDouble("driftMinFraction");
        if (d.contains("driftGripFactor")) p.driftGripFactor = d.getDouble("driftGripFactor");
        if (d.contains("hopImpulse")) p.hopImpulse = d.getDouble("hopImpulse");
        if (d.contains("offroadMult")) p.offroadMult = d.getDouble("offroadMult");
        if (d.contains("boostMult")) p.boostMult = d.getDouble("boostMult");
        if (d.contains("wallBumpFactor")) p.wallBumpFactor = d.getDouble("wallBumpFactor");
        p.miniTurboMult = new double[] { 1.0,
                d.contains("miniTurboMult1") ? d.getDouble("miniTurboMult1") : 1.12,
                d.contains("miniTurboMult2") ? d.getDouble("miniTurboMult2") : 1.25,
                d.contains("miniTurboMult3") ? d.getDouble("miniTurboMult3") : 1.4 };
        p.miniTurboTicks = new int[] { 0,
                d.contains("miniTurboTicks1") ? d.getInt("miniTurboTicks1") : 12,
                d.contains("miniTurboTicks2") ? d.getInt("miniTurboTicks2") : 22,
                d.contains("miniTurboTicks3") ? d.getInt("miniTurboTicks3") : 34 };
        if (d.contains("rocketStartWindow")) p.rocketStartWindow = d.getInt("rocketStartWindow");
        return p;
    }

    private static double mt(double[] a, int i, double fallback)
    {
        return a != null && i < a.length ? a[i] : fallback;
    }

    private static double mt(int[] a, int i, double fallback)
    {
        return a != null && i < a.length ? a[i] : fallback;
    }
}
