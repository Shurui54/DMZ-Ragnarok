package net.shurui.shuruisutilities.corrupted;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.api.key.CorruptedHooks;
import net.shurui.shuruisutilities.world.space.SurfaceSnap;
import net.shurui.shuruisutilities.worldborder.BorderClamp;

/**
 * The shadow dragon boss defaults for servers WITHOUT the Ragnarok Key.
 *
 * <p>Keyless servers cannot edit the seven slot definitions (the editor is key-only since S20), so an unedited slot
 * has no arena and "0 = keep the entity default" stats: before S20b such a server fired the corrupted event and got
 * no dragons at all. Keyless, and only keyless ({@link CorruptedHooks#available()} is false, the same core check the
 * editor packets use), the encounter now fills the gaps:
 *
 * <ul>
 *   <li><b>Stats.</b> Every stat still at its shipped default takes the value of DragonMineZ 2.1.3's Buu saga Majin
 *       Buu ({@code dragonminez:saga_buufat}, saga {@code buu_saga} quest 14 "Majin Buu Awakens", the first Majin Buu
 *       fight: health 343000, melee damage 14400, ki damage 12920). That saga definition sets nothing else, so
 *       defence, battle power, AI tier, speed and scale keep the fighter's own defaults, exactly as for the quest
 *       spawned Buu. The dragons keep their own names, models, transformations and their shadow dragon moves
 *       (DragonBossController casts each slot's DragonMove; nothing here touches it).</li>
 *   <li><b>Spawn spots.</b> A slot with no arena, or whose arena dimension is not loaded, spawns at a random safe spot
 *       instead of being skipped: see {@link #randomSpot}.</li>
 * </ul>
 *
 * <p><b>Edited configs are never overwritten.</b> The stored definitions ({@link ShadowDragonStorage}) are never
 * written by this class: it works on a COPY made at spawn time, so nothing reaches disk. A value is replaced only
 * when it still equals the shipped default ({@code 0}, "keep the entity default"); any value an admin set, and any
 * configured arena, is used as it is. A keyed server never reaches this class, so it behaves exactly as before.
 */
public final class ShadowDragonKeylessDefaults
{
    private ShadowDragonKeylessDefaults() {}

    /** DMZ 2.1.3 Buu saga Majin Buu ({@code dragonminez:saga_buufat}, buu_saga quest 14): max health. */
    public static final double BUU_HEALTH = 343000.0;
    /** DMZ 2.1.3 Buu saga Majin Buu: melee damage (DMZ applies it to the attack damage attribute). */
    public static final double BUU_MELEE_DAMAGE = 14400.0;
    /** DMZ 2.1.3 Buu saga Majin Buu: ki damage (DMZ applies it to its ki blast damage attribute). */
    public static final double BUU_KI_DAMAGE = 12920.0;

    /** Random spots are picked in a ring this far (in blocks, horizontally) from the event location at least... */
    static final int MIN_RADIUS = 16;
    /** ...and at most. Small enough to stay inside the chunks loaded around the player who defiled the balls. */
    static final int MAX_RADIUS = 48;
    /** Candidate columns tried per dragon before the event location itself is tried as the last resort. */
    static final int ATTEMPTS = 32;
    /** Candidates tried before the spread rule below is relaxed. */
    private static final int SPREAD_ATTEMPTS = 20;
    /** Two dragons never land closer than this to each other (in blocks) while the spread rule holds. */
    private static final int MIN_SEPARATION = 8;
    /** How far inside the world border a spot must be, matching the corrupted ball scatter. */
    private static final int BORDER_MARGIN = 16;
    /** Extra blocks kept clear around vanilla spawn protection. */
    private static final int SPAWN_PROTECTION_MARGIN = 4;

    /** True on a server without the Ragnarok Key: the only case these defaults apply. */
    public static boolean active()
    {
        return !CorruptedHooks.available();
    }

    /**
     * The definition the encounter spawns from, keyless: a copy of {@code stored} where each stat still at its
     * shipped default takes the Majin Buu value. {@code stored} itself is never changed.
     */
    public static ShadowDragonDef withBuuStats(ShadowDragonDef stored)
    {
        ShadowDragonDef d = ShadowDragonDef.load(stored.save());
        if (d.health == 0.0)
            d.health = BUU_HEALTH;
        if (d.meleeDamage == 0.0)
            d.meleeDamage = BUU_MELEE_DAMAGE;
        if (d.kiBlastDamage == 0.0)
            d.kiBlastDamage = BUU_KI_DAMAGE;
        return d;
    }

    /** A chosen spawn spot: the level and the feet position. */
    public record Spot(ServerLevel level, Vec3 pos) {}

    /**
     * A random safe spawn spot for one dragon, or null when none was found (the slot is then skipped with a log line).
     *
     * <p>Rules:
     * <ul>
     *   <li>Centre: the event location (where the balls were defiled) in its dimension. With no event location, or
     *       when that dimension has a ceiling (Nether style, where the surface heightmap is the roof), the overworld
     *       world spawn.</li>
     *   <li>A random column in the ring {@value #MIN_RADIUS}..{@value #MAX_RADIUS} blocks from the centre, pulled
     *       {@value #BORDER_MARGIN} blocks inside the world border.</li>
     *   <li>The column's chunk must already be loaded (no chunk is loaded or generated for this).</li>
     *   <li>Feet on the topmost surface (MOTION_BLOCKING_NO_LEAVES), above the world floor (no void), solid ground
     *       under the feet, feet and head free, and no fluid at the ground, feet or head (no water or lava).</li>
     *   <li>Not inside vanilla spawn protection (overworld, spawn-protection radius plus
     *       {@value #SPAWN_PROTECTION_MARGIN}).</li>
     *   <li>At least {@value #MIN_SEPARATION} blocks from the spots already picked in this encounter for the first
     *       {@value #SPREAD_ATTEMPTS} tries.</li>
     *   <li>After {@value #ATTEMPTS} failed tries, the centre column itself if it passes the same checks.</li>
     * </ul>
     */
    public static Spot randomSpot(MinecraftServer server, ResourceKey<Level> dim, BlockPos origin, RandomSource random,
                                  List<Vec3> taken)
    {
        ServerLevel level = dim == null ? null : server.getLevel(dim);
        BlockPos center = origin;
        if (level == null || center == null || level.dimensionType().hasCeiling())
        {
            level = server.overworld();
            center = level.getSharedSpawnPos();
        }

        for (int attempt = 0; attempt < ATTEMPTS; attempt++)
        {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double dist = MIN_RADIUS + random.nextDouble() * (MAX_RADIUS - MIN_RADIUS);
            int x = center.getX() + Mth.floor(Math.cos(angle) * dist);
            int z = center.getZ() + Mth.floor(Math.sin(angle) * dist);
            int[] clamped = BorderClamp.clampInside(level, x, z, BORDER_MARGIN);
            Vec3 spot = safeSurface(server, level, clamped[0], clamped[1]);
            if (spot == null)
                continue;
            if (attempt < SPREAD_ATTEMPTS && tooClose(spot, taken))
                continue;
            taken.add(spot);
            return new Spot(level, spot);
        }
        Vec3 fallback = safeSurface(server, level, center.getX(), center.getZ());
        if (fallback == null)
            return null;
        taken.add(fallback);
        return new Spot(level, fallback);
    }

    /** A fresh list for {@link #randomSpot}'s spread rule, one per encounter. */
    public static List<Vec3> newTakenList()
    {
        return new ArrayList<>();
    }

    private static boolean tooClose(Vec3 spot, List<Vec3> taken)
    {
        double min = (double) MIN_SEPARATION * MIN_SEPARATION;
        for (Vec3 t : taken)
            if (t.distanceToSqr(spot) < min)
                return true;
        return false;
    }

    // the feet position on the surface of a loaded column when it passes every safety rule, else null
    private static Vec3 safeSurface(MinecraftServer server, ServerLevel level, int x, int z)
    {
        if (!level.hasChunkAt(new BlockPos(x, level.getMinBuildHeight(), z)))
            return null;
        if (underSpawnProtection(server, level, x, z))
            return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight() + 1)
            return null;
        BlockPos feet = new BlockPos(x, y, z);
        if (!level.getFluidState(feet.below()).isEmpty() || !level.getFluidState(feet).isEmpty()
                || !level.getFluidState(feet.above()).isEmpty())
            return null;
        if (!SurfaceSnap.isStandable(level, x + 0.5, y, z + 0.5))
            return null;
        return new Vec3(x + 0.5, y, z + 0.5);
    }

    private static boolean underSpawnProtection(MinecraftServer server, ServerLevel level, int x, int z)
    {
        if (level.dimension() != Level.OVERWORLD)
            return false;
        int radius = server.getSpawnProtectionRadius();
        if (radius <= 0)
            return false;
        BlockPos spawn = level.getSharedSpawnPos();
        int d = Math.max(Math.abs(x - spawn.getX()), Math.abs(z - spawn.getZ()));
        return d <= radius + SPAWN_PROTECTION_MARGIN;
    }
}
