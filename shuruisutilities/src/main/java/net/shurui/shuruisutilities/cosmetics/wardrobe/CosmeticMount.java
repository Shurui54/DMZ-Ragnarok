package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The editable movement of a mount-slot {@link CosmeticDef}: whether it FLIES or is GROUND based, and how fast it
 * drives. Plain data, so an admin sets a mount's feel in the editor with no build, exactly as
 * {@link CosmeticAnimation} lets them author a triggered animation.
 *
 * <h2>Why only two fields</h2>
 * The owner asked for one thing here: a mount must be marked flying or ground, and it should have a speed. The rest
 * of a mount's per-rig behaviour (seat height, collision box, the "fast" animation clip name, the render scale)
 * stays in {@code CosmeticMountType}'s code table, because those are properties of the CONVERTED RIG, not choices an
 * operator makes: getting them wrong is a visual bug, and an editor field for each would invite exactly that. This
 * record carries only the two the owner wanted to control.
 *
 * <h2>Migration-free by absence</h2>
 * Only meaningful when the def's {@link CosmeticDef#slot} is {@link CosmeticSlot#MOUNT}. When a def carries no mount
 * sub-record (an older record, or one an admin never edited) the mount entity falls back to
 * {@code CosmeticMountType}'s seeded table, so turning this field on needed no migration, the same property every
 * other {@link CosmeticDef} field has. The seven shipped mounts are seeded with their table values in
 * {@code CosmeticHalloweenDefaults} so a fresh catalogue reads them here, and an already-seeded catalogue keeps
 * working through the fallback.
 *
 * <p>Rides {@link CosmeticDef}'s encode/decode, NBT and the catalogue sync for free, so both the server and every
 * client can read a mount's movement off the synced catalogue without a second packet.
 */
public class CosmeticMount
{
    /** A speed floor and ceiling in blocks per tick, before the sprint multiplier. Generous; only stops a typo. */
    public static final double MIN_SPEED = 0.05D;

    public static final double MAX_SPEED = 4.0D;

    public static final double DEFAULT_SPEED = 0.35D;

    /** True flies (jump climbs, sneak descends, holds altitude); false is ground based (gravity plus a hop). */
    public boolean flying = false;

    /** Ground/air drive speed in blocks per tick, before the sprint multiplier. */
    public double speed = DEFAULT_SPEED;

    public CosmeticMount()
    {
    }

    public CosmeticMount(boolean flying, double speed)
    {
        this.flying = flying;
        this.speed = speed;
    }

    public CosmeticMount copy()
    {
        return new CosmeticMount(flying, speed);
    }

    /** Fill in anything a hand-edited or older record left wrong, so nothing downstream has to guard for it. */
    public CosmeticMount normalise()
    {
        if (!(speed > 0.0D) || Double.isNaN(speed) || Double.isInfinite(speed))
            speed = DEFAULT_SPEED;
        if (speed < MIN_SPEED)
            speed = MIN_SPEED;
        if (speed > MAX_SPEED)
            speed = MAX_SPEED;
        return this;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeBoolean(flying);
        buf.writeDouble(speed);
    }

    public static CosmeticMount decode(FriendlyByteBuf buf)
    {
        CosmeticMount m = new CosmeticMount();
        m.flying = buf.readBoolean();
        m.speed = buf.readDouble();
        return m.normalise();
    }

    /**
     * Deterministic NBT for the cross-server state sync: fixed field order, the speed as raw long bits so a value
     * that round-trips through a file and a wire cannot drift and move the content hash.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putBoolean("flying", flying);
        t.putLong("speedBits", Double.doubleToLongBits(speed));
        return t;
    }

    public static CosmeticMount fromNbt(CompoundTag t)
    {
        CosmeticMount m = new CosmeticMount();
        m.flying = t.getBoolean("flying");
        m.speed = t.contains("speedBits") ? Double.longBitsToDouble(t.getLong("speedBits")) : DEFAULT_SPEED;
        return m.normalise();
    }
}
