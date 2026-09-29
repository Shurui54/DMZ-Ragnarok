package net.shurui.shuruisutilities.npcregion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.api.key.NpcRegionHooks;

/**
 * The NPC region store, as core and the modules see it (S13: facade by FQN). The NPC region module, the store's
 * body (loading and saving {@code npcregions.json}, the spawner tick, the tether, the census, the kill rewards, the
 * behaviour enforcement, the client outline sync, {@code /npcregion} and the editor row) moved into the Ragnarok Key
 * ({@code net.shurui.ragnarokkey.npcregion}). This class keeps its name, its tag and node constants and its public
 * statics, and each store call goes through {@link NpcRegionHooks}: keyless there are no NPC regions (every lookup
 * answers null or empty, nothing is loaded, created or written).
 *
 * <p>Callers: the raids module's rifts (the region list), the Z orb and event features in the key, the shard NPC
 * region table, the chunk purge job, the editor save packets, the event bundle import and the kill-TP falloff.
 * {@link #eligibleAnchorsFor}, {@link #spawnAllowedAt}, {@link #spawnDeniedHere} and {@link #boxAt} stay here
 * unchanged: they read only core state (the region flags, the raid zones and the region model).
 */
public class NpcRegionManager
{
    public static final String PERM = "su.npcregion";
    public static final String PERM_ADMIN = PERM + ".admin";
    /** Required to CREATE new regions (map drag / define / create), on top of the command node. */
    public static final String PERM_CREATE = PERM + ".create";
    /** GUI gate (mirrors the {@code su.gui.*} nodes used by {@link net.shurui.shuruisutilities.hub.HubServer}). */
    public static final String PERM_GUI = "su.gui.npcregions";

    // Persistent-data keys stamped on spawned mobs so the region can count + tether them (survive reloads).
    public static final String TAG_REGION = "su_npcregion";     // string: owning region name
    public static final String TAG_TETHERED = "su_npcr_tether";  // byte: marks a region-spawned mob
    public static final String TAG_NPC_INDEX = "su_npcr_idx";    // int: which NPC slot in the region spawned it
    // Byte stamped on a mob spawned from an event's extra roster (a template region's NPCs appended to a live
    // region, or an eventOnly region's own roster). Marks it for removal at event END and discard on a join after
    // the event, so no event mob is ever left behind as a plain mob. A normal region spawn never carries it.
    public static final String TAG_EVENT = "su_event";
    /**
     * Int stamped on EVERY region-spawned mob: the {@link NpcSpawnConfig#behavior} ordinal
     * (0 Passive, 1 Wander, 2 Dialogue, 3 Follower, 4 Guard, 5 Hostile, 6 DMZ Fighter). Read by
     * the key's {@code NpcRegionBehavior} (LivingChangeTargetEvent) so SU can enforce the behaviour on ANY entity
     * class - sdu fighters AND real {@code dragonminez:*} NPCs - without a compile dependency on either.
     */
    public static final String TAG_BEHAVIOR = "SuNpcBehavior";

    // Pack-aggro stamps (NpcRegionBehavior). When a player attacks a region NPC, every regionmate in notice range is
    // provoked to lock onto that player, Passive ones included: these two record who and until when. Persistent so a
    // relog mid-fight keeps the aggro, but the game-time gate expires them, so a stale stamp read after a long
    // downtime is simply ignored. The keys are shared vocabulary: sdu's SduDmzFighter self-enforces its behaviour
    // inside setTarget and reads these SAME string keys (it cannot import SU) so a provoked fighter fights.
    public static final String TAG_PROVOKE_BY = "su_npcr_provoke_by";      // uuid: the player this NPC was provoked by
    public static final String TAG_PROVOKE_UNTIL = "su_npcr_provoke_until"; // long: game time the provoke expires at
    // Per-victim requery cooldown: a flurry of ki hits on one NPC must not re-run the pack AABB scan every tick.
    public static final String TAG_PACK_QUERY_UNTIL = "su_npcr_packq_until";       // long: game time the next scan is allowed

    private static final NpcRegionManager INSTANCE = new NpcRegionManager();

    /** The one store view (its calls go through {@link NpcRegionHooks}). */
    public static NpcRegionManager instance()
    {
        return INSTANCE;
    }

    protected NpcRegionManager() {}

    /** The region of this name (case-insensitive), or null. Keyless: null. */
    public NpcRegion get(String name)
    {
        return name == null ? null : NpcRegionHooks.get().get(name);
    }

    /** A copy of every region. Keyless: empty. */
    public Collection<NpcRegion> all()
    {
        return NpcRegionHooks.get().all();
    }

    /** The regions that should run right now (eventOnly ones only while their event owns them). Keyless: empty. */
    public Collection<NpcRegion> live()
    {
        return NpcRegionHooks.get().live();
    }

    /** Every region name, sorted. Keyless: empty. */
    public List<String> getNames()
    {
        return NpcRegionHooks.get().names();
    }

    public boolean exists(String name)
    {
        return get(name) != null;
    }

    public boolean isEmpty()
    {
        return all().isEmpty();
    }

    /** Store (and persist, sync and shard-publish) a region. Keyless: nothing. */
    public void put(NpcRegion region)
    {
        NpcRegionHooks.get().put(region);
    }

    /** Delete a region. Keyless: false, nothing deleted. */
    public boolean delete(String name)
    {
        return NpcRegionHooks.get().delete(name);
    }

    /** Rename a region in place. Keyless: false, nothing renamed. */
    public boolean rename(String oldName, String newName)
    {
        return NpcRegionHooks.get().rename(oldName, newName);
    }

    /**
     * Public accessor for the Z orb spawner (the key): the players on {@code level} who may anchor spawns right
     * now, applying the same raid + protection-flag gates the NPC spawner uses.
     */
    public static List<Player> eligibleAnchorsFor(ServerLevel level)
    {
        return eligibleAnchors(level);
    }

    /**
     * Public accessor for the Z orb spawner (the key): may region content spawn at this point, i.e. no protection
     * region denies {@code npcregion-spawns} here and no live raid zone contains it. Fails OPEN on a lookup throw,
     * so a broken lookup never silently stops every Z orb spawn on the server.
     */
    public static boolean spawnAllowedAt(String dim, double x, double y, double z)
    {
        try
        {
            if (spawnDeniedHere(dim, x, y, z))
                return false;
            return !net.shurui.shuruisutilities.compat.RaidBossesCompat.isInsideActiveRaidZone(dim, x, y, z);
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    /**
     * The players on this level who are allowed to anchor region spawns at all.
     *
     * <p>Neither gate here has anything to do with which region is being considered, so the answer is the same
     * for every one of them and is worked out once per level per tick.
     */
    private static List<Player> eligibleAnchors(ServerLevel level)
    {
        List<Player> anchors = null;
        for (Player p : level.players())
        {
            if (p.isSpectator())
                continue;
            // a player in an active raid must not anchor spawns or keep region cooldowns burning.
            // no-op when raid bosses is absent.
            if (net.shurui.shuruisutilities.compat.RaidBossesCompat.isPlayerInActiveRaid(p.getUUID()))
                continue;
            // nor may one standing where the npcregion-spawns flag is denied. That flag already refuses a spawn
            // POSITION inside a protected region, which only ever pushed the spawns to the zone's edge - the
            // player was still the anchor pulling them there. Asked at the anchor too, standing in a safe zone
            // means no spawns at all: it anchors nothing, counts toward no cap and burns no cooldown, exactly
            // like a player in a raid.
            if (!spawnsAllowedAt(p))
                continue;
            if (anchors == null)
                anchors = new ArrayList<>();
            anchors.add(p);
        }
        return anchors == null ? List.of() : anchors;
    }

    /**
     * May region NPCs be spawned for a player standing where this one is?
     *
     * <p>The very same {@code npcregion-spawns} flag that already refuses a spawn POSITION inside a protected
     * region, asked at the ANCHOR instead. One flag governs both halves, so a zone set to deny NPC region spawns
     * denies them outright rather than only relocating them to its edge.
     *
     * <p>Fails OPEN: a region lookup that throws must not quietly stop every NPC region on the server from
     * populating.
     */
    private static boolean spawnsAllowedAt(Player p)
    {
        try
        {
            return !spawnDeniedHere(net.shurui.shuruisutilities.regions.RegionEventHandler.dimOf(p),
                    p.getX(), p.getY(), p.getZ());
        }
        catch (Throwable t)
        {
            return true;
        }
    }

    /** True when a server protection region at this point sets the {@code npcregion-spawns} flag to deny. */
    public static boolean spawnDeniedHere(String dim, double x, double y, double z)
    {
        return "deny".equals(net.shurui.shuruisutilities.regions.RegionManager.instance()
                .flagAt(dim, x, y, z, net.shurui.shuruisutilities.regions.RegionFlag.NPCREGION_SPAWNS));
    }

    /** The first selection whose XZ footprint contains the column, or null when outside the region. */
    public static NpcRegion.Box boxAt(NpcRegion region, int x, int z)
    {
        for (NpcRegion.Box b : region.boxes)
            if (x >= b.minX && x <= b.maxX && z >= b.minZ && z <= b.maxZ)
                return b;
        return null;
    }
}
