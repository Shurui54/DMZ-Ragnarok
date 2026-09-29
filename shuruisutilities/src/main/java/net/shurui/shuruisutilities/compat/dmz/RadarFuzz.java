package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Server-side positional fuzzing for the normal dragon ball radars.
 *
 * <p>WHY. The radar only needs to point a player at the rough area a ball is in, but DMZ sends the exact block a
 * ball sits on. For the normal sets we want the radar to lead to an approximate spot instead, so the last stretch
 * of the hunt is done by eye rather than walking onto the exact coordinate the server already knows. This runs on
 * the SERVER (from {@code DragonBallSavedData.getAllKnownPositionsForRadar}, the list DMZ folds into the outgoing
 * radar packet), so the client never receives the true position and a modified client cannot recover it.
 *
 * <p>SCOPE. Only the normal sets are fuzzed: everything EXCEPT {@code super} and {@code blackstar} (so DMZ's
 * {@code earth} and {@code namek}, plus SU's {@code cerulean}). Excluding by id, rather than listing the normal
 * ids, means any future normal set is fuzzed automatically and the two special radars stay exact.
 *
 * <p>STABILITY. The offset is a pure function of the ball's real horizontal position (a hash of its x and z), NOT
 * a fresh random per packet, so the same ball resolves to the same fuzzed spot in every packet and across
 * restarts. The radar dot therefore sits still instead of jittering to a new place each tick, which reads as an
 * approximate location rather than a broken one. Hashing only x and z (not y) also keeps a ball's fuzzed spot
 * identical whether it is still PENDING (stored with a dummy y) or has since become ACTIVE at its real y, so a
 * ball does not appear to jump when it materialises.
 *
 * <p>DATA SAFETY. The input list is DMZ's own fresh radar copy and every {@link BlockPos} is immutable, so this
 * builds a NEW list of new positions and never mutates the stored active/pending entries. The true coordinates
 * that scatter, pickup and the summon scan rely on are left exactly as DMZ holds them.
 */
public final class RadarFuzz
{
    private RadarFuzz()
    {
    }

    // The two sets whose radar must keep pointing at the exact block: Super and Black Star. Every other set id is
    // treated as a normal set and gets fuzzed.
    private static final Set<String> EXACT_SETS = Set.of("super", "blackstar");

    /**
     * Return a fuzzed copy of a set's radar positions, or the input unchanged when fuzzing does not apply (special
     * set, fuzzing disabled by config, or an empty/absent list). Never mutates the input.
     */
    public static List<BlockPos> fuzzForRadar(String setId, List<BlockPos> exactPositions)
    {
        if (exactPositions == null || exactPositions.isEmpty())
        {
            return exactPositions;
        }
        int radius = SUConfig.radarFuzzRadius;
        if (radius <= 0)
        {
            // 0 (or negative) disables fuzzing entirely: hand back DMZ's exact positions.
            return exactPositions;
        }
        if (setId != null && EXACT_SETS.contains(setId))
        {
            // Super and Black Star radars stay exact.
            return exactPositions;
        }
        List<BlockPos> out = new ArrayList<>(exactPositions.size());
        for (BlockPos pos : exactPositions)
        {
            out.add(pos == null ? null : fuzz(pos, radius));
        }
        return out;
    }

    // Shift a single position horizontally by a deterministic offset derived from its own x and z. The offset lands
    // in the ring between half the radius and the full radius, so the dot always moves a meaningful amount (never
    // near-exact) while staying inside the configured radius. y is passed through untouched: the client radar reads
    // only x and z, and leaving y alone keeps the dot from ever pointing underground.
    private static BlockPos fuzz(BlockPos pos, int radius)
    {
        long h = hash(pos.getX(), pos.getZ());
        double angle = (h & 0xFFFFL) / 65536.0 * (Math.PI * 2.0);
        double frac = ((h >>> 16) & 0xFFFFL) / 65536.0;
        double dist = radius * (0.5 + 0.5 * frac);
        int ox = (int) Math.round(Math.cos(angle) * dist);
        int oz = (int) Math.round(Math.sin(angle) * dist);
        return new BlockPos(pos.getX() + ox, pos.getY(), pos.getZ() + oz);
    }

    // A 64-bit mix of a ball's x and z into a well-spread hash, so the angle and distance drawn from it look random
    // between balls yet are fully determined by the position. Standard murmur-style finaliser over the two mixed
    // coordinates; the exact constants are not important beyond giving good bit dispersion.
    private static long hash(int x, int z)
    {
        long h = x * 0x9E3779B97F4A7C15L ^ z * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return h;
    }
}
