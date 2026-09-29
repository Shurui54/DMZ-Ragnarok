package net.shurui.shuruisutilities.regions;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The region flag QUERIES, kept in core under the name every caller already uses.
 *
 * <p>The region enforcement (the Forge handlers for every event-mapped {@link RegionFlag}, the residency effects,
 * {@code /serverclaim} and the region editor) moved into the Ragnarok Key with the Regions module (S12,
 * {@code net.shurui.ragnarokkey.regions}). The region STORE and these answers stay in core because public features
 * read them on every server: terrain regen ({@code TerrainRegenRule} / {@code TerrainRegenHandler} /
 * {@code TerrainRegenService}, the {@code terrain-regen} flag), the world-flag mixins (ice, snow, fluids, sculk,
 * ticks, frost walker, the quest start), {@code FallingBlockGuard}, {@code BlockBreakGuard}, the build-dimension guard
 * and DMZ's ki-grief / gravity hook ({@link RegionDmzHook}). Core loads the saved regions at every server start
 * ({@link RegionEngine}), keyless included, exactly as the torn-down module used to before S12, so every answer
 * here is unchanged with or without the key. Nothing in core writes the regions: only the key edits them.
 */
public final class RegionEventHandler
{
    private RegionEventHandler() {}

    public static final String ALLOW = "allow";
    public static final String DENY = "deny";

    public static RegionManager rm()
    {
        return RegionManager.instance();
    }

    public static boolean bypass(Player p)
    {
        return p != null && RegionBypass.isBypassing(p.getUUID());
    }

    public static String dimOf(Entity e)
    {
        return e.level().dimension().location().toString();
    }

    public static String dimOf(Level l)
    {
        return l.dimension().location().toString();
    }

    /** {@code deny} at this point for a pure state flag (unset / allow => not denied). */
    public static boolean denied(String dim, double x, double y, double z, String flag)
    {
        return DENY.equals(rm().flagAt(dim, x, y, z, flag));
    }

    /** {@code allow} at this point (used by flags whose "on" state is allow, e.g. invincible). */
    public static boolean allowed(String dim, double x, double y, double z, String flag)
    {
        return ALLOW.equals(rm().flagAt(dim, x, y, z, flag));
    }

    /** Cheap short-circuit so hot vanilla tick/fluid mixins do nothing when no regions exist at all. */
    public static boolean hasRegions()
    {
        return !rm().isEmpty();
    }

    /** {@code deny} for a state flag at a world block position. Fast-pathed when there are no regions. */
    public static boolean worldFlagDenied(Level level, BlockPos pos, String flag)
    {
        if (rm().isEmpty())
            return false;
        return DENY.equals(rm().flagAt(dimOf(level), pos.getX(), pos.getY(), pos.getZ(), flag));
    }

    /** {@code allow} for a state flag at a world block position, for flags whose "on" state is allow. */
    public static boolean worldFlagAllowed(Level level, BlockPos pos, String flag)
    {
        if (rm().isEmpty())
            return false;
        return ALLOW.equals(rm().flagAt(dimOf(level), pos.getX(), pos.getY(), pos.getZ(), flag));
    }

    /** {@code deny} for a state flag at a player's position (e.g. receive-chat). */
    public static boolean playerFlagDenied(Player p, String flag)
    {
        if (p == null || rm().isEmpty())
            return false;
        return DENY.equals(rm().flagAt(dimOf(p), p.getX(), p.getY(), p.getZ(), flag));
    }

    /**
     * Build-family permission: the specific flag wins, then the umbrella {@code build} flag, then region
     * membership (governed & non-member => denied). Bypassers always pass.
     */
    public static boolean canBuild(Player p, String dim, double x, double y, double z, String specific)
    {
        if (bypass(p))
            return true;
        String s = rm().flagAt(dim, x, y, z, specific);
        if (ALLOW.equals(s))
            return true;
        if (DENY.equals(s))
            return false;
        String b = rm().flagAt(dim, x, y, z, RegionFlag.BUILD);
        if (ALLOW.equals(b))
            return true;
        if (DENY.equals(b))
            return false;
        Region gov = rm().highestAt(dim, x, y, z);
        return gov == null || gov.isMember(p.getUUID());
    }

    /**
     * Dragon balls are exempt from claim and region building rules.
     *
     * <p>A ball scatters wherever the world decides, which is regularly inside somebody's claim or a
     * protected build. Left to the normal rules a player who finds one there cannot pick it up and the set
     * is simply unfinishable, and the same block check blocks placing the seven back down to call Shenron.
     * Since a ball is a world event rather than someone's property, both are allowed regardless of who owns
     * the ground.
     *
     * <p>Matched on the registry path so it covers DragonMineZ's own {@code dball1..7}, our added sets
     * (black star, super, cerulean) and the corrupted balls ({@code corrupted_dball1..7}) without naming each one,
     * and without compiling against DMZ.
     *
     * <p>Deliberately NOT routed through the shared {@link net.shurui.shuruisutilities.compat.dmz.DragonBallSets}
     * predicate the bag and containment rules use. That one reads DMZ's definitions and degrades to "no dragon balls"
     * if DMZ's API shifts; this exemption only ever GRANTS a build allowance, so a broader path match is the safe
     * direction (a stray non-ball hit merely lets a block be placed in a claim), and staying DMZ-classload-free keeps a
     * scattered ball pickable in a claim even when the shared predicate cannot read DMZ. On real balls, corrupted
     * included, the two already agree, so there is no live disagreement to reconcile.
     */
    public static boolean isDragonBall(BlockState state)
    {
        if (state == null)
        {
            return false;
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null)
        {
            return false;
        }
        String path = id.getPath();
        return path.contains("dball") || path.contains("dragonball") || path.contains("dragon_ball");
    }
}
