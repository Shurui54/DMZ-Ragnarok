package net.shurui.shuruisutilities.grave;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;

import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Places, opens, and tears down player graves: an oak fence with the dead player's head on top, a floating
 * name marker, and a chest-backed inventory in {@link GraveStorage}. Visuals are real vanilla blocks;
 * interaction is intercepted in {@link GraveEventHandler} by matching registered positions, so no custom
 * block/block-entity registration.
 */
public final class GraveManager
{
    private GraveManager() {}

    private static final int UP_SCAN = 6; // blocks up we scan for a placeable spot when death pos is occupied
    private static final String MARKER_TAG = "su_grave_marker"; // so a broken/cleaned grave can find its own marker
    // Stamped into the head's persistent (ForgeData) block-entity NBT, so a totem stays identifiable as a grave even
    // when its GraveStorage record has gone missing. The marker, not the block shape, is what makes a head a totem.
    private static final String HEAD_MARKER_TAG = "su_grave_head";

    // Chunk ticket keeping a grave's chunk loaded while we wait for its async entity section to drain so the
    // marker ArmorStand is queryable. Comparator by packed pos for dedup. Lifetime 0 (released explicitly once
    // handled or deadline passes); radius 2 loads the chunk (not entity-ticking, which we don't need).
    private static final TicketType<ChunkPos> GRAVE_MARKER_TICKET =
            TicketType.create("su_grave_marker_removal", java.util.Comparator.comparingLong(ChunkPos::toLong));
    private static final int TICKET_RADIUS = 2;
    private static final int DEFER_DEADLINE_TICKS = 200; // retry window before giving up, ~10s at 20 tps

    // Deferred marker-removal queue. Populated by removeGrave when the marker can't be found the same tick
    // (grave expired in an unloaded chunk: block chunk force-loads synchronously, but 1.17+ loads the entity
    // section async via PersistentEntitySectionManager, which drains on the level's next tick). Drained at tick
    // END by drainPending, AFTER ServerLevel.tick() ran, so the marker is finally queryable. Server thread only.
    private static final List<PendingMarkerRemoval> PENDING = new ArrayList<>();

    /**
     * How many times a single grave's marker removal may fail before we stop trying and force the cleanup.
     *
     * <p>Without a cap this never ended. The expiry sweep runs every five seconds and re-selects any grave still
     * in storage; a marker that could not be found kept its record on purpose (dropping it used to orphan the
     * floating name), so the same grave was re-queued, retried for ten seconds, failed, warned and kept, forever.
     * Four such graves produced 5061 of the 7618 lines in one day's log. The record still survives a FAILURE, so
     * the original reasoning holds; it just no longer survives an unbounded number of them.
     */
    private static final int MAX_MARKER_ATTEMPTS = 5;

    /** Wait between attempts. Long enough that a stuck grave costs nothing; the sweep skips it until then. */
    private static final int RETRY_BACKOFF_TICKS = 1200; // 60s

    /** How wide the final give-up sweep looks for the name. Generous: it is the last chance to catch it. */
    private static final double GIVE_UP_RADIUS = 8.0D;

    /** One grave's marker-removal attempts. Server thread only, so no synchronisation. */
    private static final class MarkerAttempts
    {
        int tries;
        long nextTick;
    }

    private static final Map<String, MarkerAttempts> RETRIES = new HashMap<>();

    private static String retryKey(ServerLevel level, BlockPos pos)
    {
        return level.dimension().location() + "@" + pos.asLong();
    }

    /**
     * Whether the expiry sweep should attempt this grave now.
     *
     * <p>The backoff lives here rather than in {@link #removeGrave} so a player breaking or emptying a grave is
     * never made to wait: only the automatic five-second sweep is throttled, and only for a grave whose marker
     * has already refused to be found.
     */
    public static boolean markerRetryDue(ServerLevel level, BlockPos pos, long nowTick)
    {
        MarkerAttempts a = RETRIES.get(retryKey(level, pos));
        return a == null || nowTick >= a.nextTick;
    }

    /** Forget a grave's retry state. Called whenever its record leaves storage, however that happened. */
    public static void clearRetryState(ServerLevel level, BlockPos pos)
    {
        RETRIES.remove(retryKey(level, pos));
    }

    // one deferred removal: level + grave, marker id (null for legacy graves on the AABB fallback), the fallback
    // AABB, and the tick deadline after which storage is handled anyway
    private static final class PendingMarkerRemoval
    {
        final ResourceKey<Level> dimension;
        final BlockPos gravePos;
        final UUID markerId;
        final AABB box;
        final long deadlineTick;

        PendingMarkerRemoval(ResourceKey<Level> dimension, BlockPos gravePos, UUID markerId, AABB box,
                long deadlineTick)
        {
            this.dimension = dimension;
            this.gravePos = gravePos;
            this.markerId = markerId;
            this.box = box;
            this.deadlineTick = deadlineTick;
        }
    }

    // Create a grave at/above deathPos holding contents (up to 27 stacks) + xp. Placement scans up to UP_SCAN
    // blocks up for the first air/replaceable block, clamped into build height (void deaths land near min
    // build height); fence there, head one above, invisible name-marker ArmorStand above the head. Offsets up
    // if a grave already occupies the spot.
    public static GraveData createGrave(ServerLevel level, BlockPos deathPos, GameProfile owner,
            List<ItemStack> contents, int xp)
    {
        GraveStorage storage = GraveStorage.get(level);
        BlockPos base = findPlacement(level, deathPos, storage);
        // A totem holding a dragon ball is moved OUT of a guild claim before it is placed. Inside one it is the
        // claim owner's to stand over, and a ball set is not something the game may hand to whoever happens to own
        // the chunk somebody died in. Ordinary loot graves stay exactly where their owner fell.
        if (holdsDragonBall(contents))
            base = outsideClaim(level, base, storage);

        GraveData data = new GraveData(base, owner.getId(), owner.getName(), xp);
        data.setCreatedGameTime(level.getGameTime());
        SimpleContainer container = data.container();
        int slot = 0;
        for (ItemStack stack : contents)
        {
            if (stack == null || stack.isEmpty())
                continue;
            if (slot >= GraveData.SIZE)
            {
                // overflow guard: shouldn't happen (27 slots vs 27 main-inv stacks) but never lose items
                level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
                        base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5, stack));
                continue;
            }
            container.setItem(slot++, stack);
        }

        // fence + player head on top
        level.setBlockAndUpdate(base, Blocks.OAK_FENCE.defaultBlockState());
        BlockPos headPos = base.above();
        level.setBlockAndUpdate(headPos, Blocks.PLAYER_HEAD.defaultBlockState());
        BlockEntity be = level.getBlockEntity(headPos);
        if (be instanceof SkullBlockEntity skull)
        {
            skull.setOwner(owner);
            stampHeadMarker(skull);
            skull.setChanged();
            BlockState hs = level.getBlockState(headPos);
            level.sendBlockUpdated(headPos, hs, hs, 3);
        }

        ArmorStand marker = spawnNameMarker(level, base, owner.getName());
        if (marker != null)
            data.setMarkerId(marker.getUUID());

        storage.put(data);
        return data;
    }

    // first placeable position at/above deathPos, clamped into world bounds
    private static BlockPos findPlacement(ServerLevel level, BlockPos deathPos, GraveStorage storage)
    {
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight() - 2; // room for the head above
        int startY = deathPos.getY();
        if (startY < minY)
            startY = minY + 3; // void death: near the floor of the world
        if (startY > maxY)
            startY = maxY;

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(deathPos.getX(), startY, deathPos.getZ());
        for (int i = 0; i <= UP_SCAN; i++)
        {
            int y = startY + i;
            if (y > maxY)
                break;
            cursor.setY(y);
            if (isPlaceable(level, cursor) && !storage.has(cursor))
                return cursor.immutable();
        }
        // nothing free within the scan: fall back to the heightmap top so the grave is reachable, offsetting up
        // while a grave already occupies the spot
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, deathPos.getX(), deathPos.getZ());
        BlockPos.MutableBlockPos fallback = new BlockPos.MutableBlockPos(deathPos.getX(),
                Math.min(Math.max(top, minY + 1), maxY), deathPos.getZ());
        while (storage.has(fallback) && fallback.getY() < maxY)
            fallback.setY(fallback.getY() + 1);
        return fallback.immutable();
    }

    private static boolean holdsDragonBall(List<ItemStack> contents)
    {
        if (contents == null)
            return false;
        for (ItemStack stack : contents)
        {
            if (stack != null && !stack.isEmpty()
                    && net.shurui.shuruisutilities.compat.dmz.DragonBallSets.isDragonBall(stack))
                return true;
        }
        return false;
    }

    /** How far out (in chunks) a ball totem will look for ground outside a guild claim before giving up. */
    private static final int CLAIM_ESCAPE_CHUNKS = 8;

    /**
     * The nearest placement outside any guild claim, or {@code base} unchanged when it is already outside one (or
     * when nothing unclaimed is within reach, because a totem in the wrong place still beats no totem at all).
     *
     * <p>Walks outward a chunk ring at a time and, within each ring, takes the point of that chunk closest to where
     * the player actually died, so "right outside the claim" is literally the nearest ground over the boundary.
     */
    private static BlockPos outsideClaim(ServerLevel level, BlockPos base, GraveStorage storage)
    {
        if (GuildManager.claimOwner(level, base) == null)
            return base;
        int baseCx = base.getX() >> 4;
        int baseCz = base.getZ() >> 4;
        for (int ring = 1; ring <= CLAIM_ESCAPE_CHUNKS; ring++)
        {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -ring; dx <= ring; dx++)
            {
                for (int dz = -ring; dz <= ring; dz++)
                {
                    // ring only: the inner chunks were answered by the previous pass
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring)
                        continue;
                    int cx = baseCx + dx;
                    int cz = baseCz + dz;
                    // the point of this chunk nearest the death spot, so we hug the claim edge
                    int x = Math.max(cx << 4, Math.min(base.getX(), (cx << 4) + 15));
                    int z = Math.max(cz << 4, Math.min(base.getZ(), (cz << 4) + 15));
                    BlockPos probe = new BlockPos(x, base.getY(), z);
                    if (GuildManager.claimOwner(level, probe) != null)
                        continue;
                    BlockPos spot = findPlacement(level, surfaceAt(level, x, z, base.getY()), storage);
                    if (spot == null || GuildManager.claimOwner(level, spot) != null)
                        continue;
                    double dist = spot.distSqr(base);
                    if (dist < bestDist)
                    {
                        bestDist = dist;
                        best = spot;
                    }
                }
            }
            if (best != null)
                return best;
        }
        return base;
    }

    /** Ground level at this column, falling back to the given Y when the heightmap is unhelpful. */
    private static BlockPos surfaceAt(ServerLevel level, int x, int z, int fallbackY)
    {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int y = top > level.getMinBuildHeight() ? top : fallbackY;
        return new BlockPos(x, Math.min(Math.max(y, level.getMinBuildHeight() + 1), level.getMaxBuildHeight() - 2), z);
    }

    /**
     * Move a standing grave, contents and marker and all, to a new position.
     *
     * <p>Used when a repair has buried a totem, and by anything else that has to get a grave out of the way. The
     * record is rebuilt at the new position because a grave is keyed by where it stands, and everything that makes
     * it the SAME grave (items, XP and whether it was already released, creation time for the expiry sweep, and the
     * marker's own entity id) is carried across.
     *
     * @return true when the grave moved.
     */
    public static boolean relocate(ServerLevel level, BlockPos from, BlockPos to)
    {
        if (level == null || from == null || to == null || from.equals(to))
            return false;
        GraveStorage storage = GraveStorage.get(level);
        GraveData old = storage.get(from);
        if (old == null || storage.has(to))
            return false;

        GraveData moved = new GraveData(to, old.ownerId(), old.ownerName(), old.xp());
        moved.setCreatedGameTime(old.createdGameTime());
        if (old.isXpReleased())
            moved.consumeXp(); // already paid out once; the moved grave must not pay again
        SimpleContainer src = old.container();
        SimpleContainer dst = moved.container();
        for (int i = 0; i < src.getContainerSize() && i < dst.getContainerSize(); i++)
            dst.setItem(i, src.getItem(i));
        moved.setMarkerId(old.markerId());

        clearBlocks(level, from);
        placeBlocks(level, to, old.ownerId(), old.ownerName());
        // carry the existing marker rather than respawning it, so its id stays the one the record knows
        if (!moveMarker(level, old.markerId(), to))
        {
            ArmorStand marker = spawnNameMarker(level, to, old.ownerName());
            moved.setMarkerId(marker == null ? null : marker.getUUID());
        }
        storage.remove(from);
        storage.put(moved);
        LoggingHandler.sulog.info("[grave] Moved {}'s grave from {} to {}.", old.ownerName(), from, to);
        return true;
    }

    private static boolean moveMarker(ServerLevel level, UUID markerId, BlockPos base)
    {
        if (markerId == null)
            return false;
        net.minecraft.world.entity.Entity entity = level.getEntity(markerId);
        if (!(entity instanceof ArmorStand stand) || !stand.getPersistentData().getBoolean(MARKER_TAG))
            return false;
        stand.teleportTo(base.getX() + 0.5, base.getY() + 1.4, base.getZ() + 0.5);
        return true;
    }

    /** The fence, the head, and the head's skull owner: the visible half of a grave. */
    private static void placeBlocks(ServerLevel level, BlockPos base, UUID ownerId, String ownerName)
    {
        level.setBlockAndUpdate(base, Blocks.OAK_FENCE.defaultBlockState());
        BlockPos headPos = base.above();
        level.setBlockAndUpdate(headPos, Blocks.PLAYER_HEAD.defaultBlockState());
        if (level.getBlockEntity(headPos) instanceof SkullBlockEntity skull)
        {
            skull.setOwner(new GameProfile(ownerId, ownerName));
            stampHeadMarker(skull);
            skull.setChanged();
            BlockState hs = level.getBlockState(headPos);
            level.sendBlockUpdated(headPos, hs, hs, 3);
        }
    }

    // Stamp the grave marker into the head's persistent (ForgeData) NBT. Written and read from disc, so it survives
    // world saves and chunk reloads, and lets isGraveBlock recognise a totem whose GraveStorage record is gone.
    private static void stampHeadMarker(SkullBlockEntity skull)
    {
        skull.getPersistentData().putBoolean(HEAD_MARKER_TAG, true);
    }

    /**
     * Whether the head block entity at this position carries our stamped grave marker. Only worth calling when the
     * state at headPos is a PLAYER_HEAD and the cheap storage check has already missed, because it pays for a
     * block-entity read. A head with no block entity, or one that is not ours, answers false.
     */
    public static boolean headCarriesMarker(ServerLevel level, BlockPos headPos)
    {
        return level.getBlockEntity(headPos) instanceof SkullBlockEntity skull
                && skull.getPersistentData().getBoolean(HEAD_MARKER_TAG);
    }

    /**
     * Lift any grave inside this box that the repair has buried, so a totem never ends up sealed underground.
     *
     * <p>Called by the terrain repair engine once a crater has been filled. The repair only ever writes into empty space,
     * so it cannot overwrite the fence or the head, but it can and does close the ground back over them: the totem
     * survives while becoming unreachable, which for a ball totem means a set nobody can collect.
     */
    public static int liftBuriedGraves(ServerLevel level, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
    {
        if (level == null)
            return 0;
        GraveStorage storage = GraveStorage.get(level);
        List<BlockPos> buried = new ArrayList<>();
        for (GraveData data : storage.all())
        {
            BlockPos pos = data.pos();
            if (pos.getX() < minX || pos.getX() > maxX || pos.getY() < minY || pos.getY() > maxY
                    || pos.getZ() < minZ || pos.getZ() > maxZ)
                continue;
            if (isBuried(level, pos))
                buried.add(pos);
        }
        int moved = 0;
        for (BlockPos pos : buried)
        {
            BlockPos target = findPlacement(level, surfaceAt(level, pos.getX(), pos.getZ(), pos.getY()), storage);
            if (target != null && relocate(level, pos, target))
                moved++;
        }
        return moved;
    }

    /** Buried means something solid sits straight on top of the head, so the totem cannot be walked up to. */
    private static boolean isBuried(ServerLevel level, BlockPos base)
    {
        BlockPos above = base.above(2); // base fence, head, then whatever is over it
        BlockState state = level.getBlockState(above);
        return !state.isAir() && !state.canBeReplaced();
    }

    // air/replaceable (grass, water) blocks are OK to overwrite
    private static boolean isPlaceable(ServerLevel level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        return state.isAir() || state.canBeReplaced();
    }

    // spawn the floating name marker; null if entity creation failed
    private static ArmorStand spawnNameMarker(ServerLevel level, BlockPos base, String name)
    {
        ArmorStand stand = EntityType.ARMOR_STAND.create(level);
        if (stand == null)
            return null;
        // float the name above the head (base + fence + head + a bit)
        stand.moveTo(base.getX() + 0.5, base.getY() + 1.4, base.getZ() + 0.5, 0.0F, 0.0F);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setNoBasePlate(true);
        stand.setCustomName(Component.literal(name));
        stand.setCustomNameVisible(true);
        // Marker flag (no hitbox, not interactable/pushable) is protected in ArmorStand; set via NBT
        CompoundTag tag = new CompoundTag();
        stand.addAdditionalSaveData(tag);
        tag.putBoolean("Marker", true);
        stand.readAdditionalSaveData(tag);
        stand.getPersistentData().putBoolean(MARKER_TAG, true);
        level.addFreshEntity(stand);
        return stand;
    }

    // open the grave's chest GUI; first open releases stored XP as orbs
    public static void open(ServerPlayer opener, ServerLevel level, BlockPos gravePos)
    {
        GraveStorage storage = GraveStorage.get(level);
        GraveData data = storage.get(gravePos);
        if (data == null)
            return;

        // Lazy backfill for totems created before the head marker existed: the record IS present, so this is a real
        // grave, and the head's chunk is loaded because a player is opening it. Stamp the marker now so the totem
        // stays protected if its record is ever lost. Not a world scan, just this one grave as it is touched.
        BlockPos headPos = gravePos.above();
        if (level.getBlockState(headPos).is(Blocks.PLAYER_HEAD)
                && level.getBlockEntity(headPos) instanceof SkullBlockEntity skull
                && !skull.getPersistentData().getBoolean(HEAD_MARKER_TAG))
        {
            stampHeadMarker(skull);
            skull.setChanged();
        }

        if (!data.isXpReleased() && data.xp() > 0)
        {
            releaseXp(level, gravePos, data.xp());
            data.consumeXp();
            storage.setDirty();
            // releasing XP alone can empty the grave (no items + XP gone) -> clean up now
            if (data.isEmpty())
            {
                removeGrave(level, gravePos);
                return;
            }
        }

        SimpleContainer container = data.container();
        // persist + auto-clean on content change while open. Attach at most once over the grave's lifetime, not
        // per-open, so repeated opens don't stack duplicate listeners.
        if (data.markListenerAttached())
            container.addListener(inv -> {
                storage.setDirty();
                if (data.isEmpty())
                    removeGrave(level, gravePos);
            });

        Component title = Component.translatable("container.dmz_ragnarok.core.grave", data.ownerName());
        if (opener.containerMenu != opener.inventoryMenu)
            opener.closeContainer();
        opener.nextContainerCounter();
        opener.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> ChestMenu.threeRows(id, inv, container), title));
    }

    /**
     * True when {@code c} is the backing {@link SimpleContainer} of a grave / totem in this level. Compared by object
     * identity against the live grave records, so it recognises the exact instance a {@code ChestMenu} was opened over.
     * Used by {@code DragonBallContainerEject} to leave a grave's dragon balls alone: the totem is the suite's own
     * internal ball holder, and its balls are collected through the grave GUI, not swept out from under it. Never
     * throws; any read error answers false (treat as not a grave), which at worst lets the eject net act on it.
     */
    /**
     * Whether this position is part of a standing grave: its fence, or the player head one block above it.
     *
     * <p>Used by the terrain repair engine and the ki-grief gate to leave graves alone. A grave is not terrain: its
     * items live in {@link GraveStorage} keyed by the fence position, and the blocks are the only handle on them, so
     * a blast that takes the fence and head leaves a record nobody can open. Only a PLAYER break routes through
     * {@link #breakGrave}, which hands the contents back; an explosion or a ki blast removes the blocks with no drops
     * at all, and the repair only puts them back if the space is still free when the debt comes due. Dragon ball
     * totems are graves too, so that path was quietly eating whole ball sets (reported 2026-09-16).
     *
     * <p>Cheap on purpose: the block state is tested first, so only an actual fence or head ever pays for the
     * storage lookup.
     */
    /**
     * Whether this level has any grave at all. A one-map-size check, so a blast on a server where nobody has died
     * pays nothing for grave protection beyond this.
     */
    public static boolean hasAnyGrave(ServerLevel level)
    {
        return level != null && !GraveStorage.get(level).all().isEmpty();
    }

    public static boolean isGraveBlock(Level level, BlockPos pos)
    {
        if (!(level instanceof ServerLevel serverLevel) || pos == null)
            return false;
        return isGraveBlock(serverLevel, pos, serverLevel.getBlockState(pos));
    }

    /** As above, for callers that already hold the state and should not pay to look it up twice. */
    public static boolean isGraveBlock(ServerLevel level, BlockPos pos, BlockState state)
    {
        if (level == null || pos == null || state == null)
            return false;
        boolean isFence = state.is(Blocks.OAK_FENCE);
        boolean isHead = state.is(Blocks.PLAYER_HEAD);
        if (!isFence && !isHead)
            return false;
        GraveStorage storage = GraveStorage.get(level);
        // Cheap common path first: a break may land on the fence (the grave position) or on the head one above it.
        if (storage.has(pos) || storage.has(pos.below()))
            return true;
        // Miss path only: fall back to the marker stamped into the head's block entity, so a totem whose record has
        // desynced is still protected. The block-entity read is paid only here, never on the hot storage-hit path,
        // and only when the state is actually a head (or the head sits one above a fence we were asked about).
        if (isHead)
            return headCarriesMarker(level, pos);
        BlockPos headPos = pos.above();
        return level.getBlockState(headPos).is(Blocks.PLAYER_HEAD) && headCarriesMarker(level, headPos);
    }

    public static boolean isGraveContainer(ServerLevel level, Container c)
    {
        if (level == null || c == null)
        {
            return false;
        }
        try
        {
            for (GraveData data : GraveStorage.get(level).all())
            {
                if (data != null && data.container() == c)
                {
                    return true;
                }
            }
        }
        catch (Throwable ignored)
        {
            // treat an unreadable grave store as "not a grave"; the eject net erring toward acting is the safe side.
        }
        return false;
    }

    private static void releaseXp(ServerLevel level, BlockPos pos, int amount)
    {
        if (amount <= 0)
            return;
        net.minecraft.world.entity.ExperienceOrb.award(level,
                net.minecraft.world.phys.Vec3.atCenterOf(pos), amount);
    }

    // fully remove a grave: fence + head + marker gone, storage entry purged. Does NOT drop remaining contents
    // (used when the container empties naturally, or after breakGrave already dropped them).
    public static void removeGrave(ServerLevel level, BlockPos gravePos)
    {
        GraveStorage storage = GraveStorage.get(level);
        GraveData data = storage.get(gravePos);

        // block clearing needs the block chunk loaded. getChunk(FULL, true) loads/generates + indexes its block
        // sections synchronously, so clearBlocks is safe now. (Player-present loot/break paths already have it
        // loaded; this is a no-op re-request there.)
        level.getChunk(gravePos.getX() >> 4, gravePos.getZ() >> 4,
                net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
        clearBlocks(level, gravePos);

        // try to kill the marker THIS tick. Player-present paths have the entity section loaded/ticking so this
        // succeeds synchronously. In the expiry-in-unloaded-chunk path the entity section is still loading async
        // (1.17+ drains it on the level's own tick, already run by the time an END-phase sweep calls us), so both
        // getEntity(markerId) and the AABB scan return nothing this tick.
        if (removeMarker(level, gravePos, data))
        {
            storage.remove(gravePos);
            clearRetryState(level, gravePos);
            return;
        }

        // marker not found yet: DEFER. Do NOT purge storage now, or we orphan the still-loading ArmorStand. Add a
        // chunk ticket so the chunk stays loaded across the retry window and enqueue a pending record that
        // drainPending retries at tick END until the marker resolves or the deadline passes. Legacy graves
        // (markerId == null) take the same path: their AABB scan also fails same-tick, retried too.
        MinecraftServer server = level.getServer();
        long deadline = server.getTickCount() + DEFER_DEADLINE_TICKS;
        UUID markerId = data != null ? data.markerId() : null;
        AABB box = new AABB(gravePos).inflate(1.5, 3.0, 1.5);

        ChunkPos chunkPos = new ChunkPos(gravePos);
        level.getChunkSource().addRegionTicket(GRAVE_MARKER_TICKET, chunkPos, TICKET_RADIUS, chunkPos);
        PENDING.add(new PendingMarkerRemoval(level.dimension(), gravePos, markerId, box, deadline));
    }

    // Drain the deferred marker-removal queue. Call once per server tick at tick END, AFTER every level ticked
    // (so async entity sections drained). Per entry: resolve level, re-request chunk, retry removal (id then
    // AABB). On success purge storage + release ticket. On deadline KEEP the record + release ticket + warn (see
    // below). Fully guarded so nothing thrown here escapes the tick handler.
    public static void drainPending(MinecraftServer server)
    {
        if (PENDING.isEmpty())
            return;
        long now = server.getTickCount();
        for (Iterator<PendingMarkerRemoval> it = PENDING.iterator(); it.hasNext(); )
        {
            PendingMarkerRemoval pending = it.next();
            try
            {
                ServerLevel level = server.getLevel(pending.dimension);
                if (level == null)
                {
                    // dimension gone: can't find or keep the marker. Drop the entry (no ticket to release on a
                    // dead level).
                    it.remove();
                    continue;
                }

                ChunkPos chunkPos = new ChunkPos(pending.gravePos);
                // re-request each retry so the async entity section keeps loading / stays drained
                level.getChunk(chunkPos.x, chunkPos.z, net.minecraft.world.level.chunk.ChunkStatus.FULL, true);

                boolean found = removeMarkerById(level, pending.markerId)
                        || removeMarkerByBox(level, pending.box);
                if (found)
                {
                    finishPending(level, pending);
                    it.remove();
                    continue;
                }

                if (now >= pending.deadlineTick)
                {
                    // Deadline passed without finding the marker. Release the ticket (done retrying for now) and
                    // drop the queue entry either way; what differs is whether the RECORD survives.
                    level.getChunkSource().removeRegionTicket(GRAVE_MARKER_TICKET, chunkPos, TICKET_RADIUS, chunkPos);
                    it.remove();

                    String key = retryKey(level, pending.gravePos);
                    MarkerAttempts attempts = RETRIES.computeIfAbsent(key, k -> new MarkerAttempts());
                    attempts.tries++;

                    if (attempts.tries < MAX_MARKER_ATTEMPTS)
                    {
                        // Keep the record, for the original reason: dropping it while the ArmorStand is still
                        // mid-load in an unloaded chunk leaves an untracked live marker, which is exactly what
                        // orphaned the floating death names. Back off so the five-second sweep stops hammering it.
                        attempts.nextTick = now + RETRY_BACKOFF_TICKS;
                        LoggingHandler.sulog.debug(
                                "[Grave] marker {} for grave at {} not found (attempt {}/{}); retrying in {}s",
                                pending.markerId, pending.gravePos, attempts.tries, MAX_MARKER_ATTEMPTS,
                                RETRY_BACKOFF_TICKS / 20);
                    }
                    else
                    {
                        giveUpAndPurge(level, pending);
                        RETRIES.remove(key);
                    }
                }
            }
            catch (Throwable t)
            {
                // a single bad entry must not stall the queue or break the tick. Drop it (may leak one storage
                // entry, better than a thrown exception aborting the tick handler).
                LoggingHandler.sulog.error("[Grave] error draining deferred marker removal for {}: {}",
                        pending.gravePos, t.toString());
                it.remove();
            }
        }
    }

    /**
     * Last resort after {@link #MAX_MARKER_ATTEMPTS} failures: take the name off the world and the record out of
     * storage, so this grave can never be selected by the sweep again.
     *
     * <p>Sweeps a wider box than the per-grave retry did, because the marker being unfindable at the exact
     * position is the whole reason we are here: an ArmorStand can be a little off, and this is the last look.
     * Whether or not it finds one, the record goes. Leaving it would restore the loop this exists to end, and a
     * missed name is recoverable by {@code /grave cleannames}, which is precisely what that command is for.
     */
    private static void giveUpAndPurge(ServerLevel level, PendingMarkerRemoval pending)
    {
        boolean nameRemoved = false;
        try
        {
            nameRemoved = removeMarkerById(level, pending.markerId)
                    || removeMarkerByBox(level, new AABB(pending.gravePos).inflate(GIVE_UP_RADIUS));
        }
        catch (Throwable ignored)
        {
            // a failed last look must not stop the record being purged; that is the part that ends the loop
        }
        GraveStorage.get(level).remove(pending.gravePos);
        LoggingHandler.sulog.info(
                "[Grave] gave up on marker {} for grave at {} after {} attempts; record purged, name {}",
                pending.markerId, pending.gravePos, MAX_MARKER_ATTEMPTS,
                nameRemoved ? "removed" : "not found (use /grave cleannames if one is left floating)");
    }

    // marker found on a deferred retry: purge storage + release the chunk ticket
    private static void finishPending(ServerLevel level, PendingMarkerRemoval pending)
    {
        GraveStorage.get(level).remove(pending.gravePos);
        // A grave that finally succeeded must not leave its attempt count behind: it would leak an entry per
        // grave for the server's lifetime, and would back off a NEW grave that later occupied the same block.
        clearRetryState(level, pending.gravePos);
        ChunkPos chunkPos = new ChunkPos(pending.gravePos);
        level.getChunkSource().removeRegionTicket(GRAVE_MARKER_TICKET, chunkPos, TICKET_RADIUS, chunkPos);
    }

    // player breaks the grave blocks directly: drop remaining contents + XP orbs, then clean up. True if a grave
    // existed at brokenPos (fence or the head above it).
    public static boolean breakGrave(ServerLevel level, BlockPos brokenPos)
    {
        GraveStorage storage = GraveStorage.get(level);
        // break may hit the fence (grave pos) or the head one up (grave pos below it)
        BlockPos gravePos = storage.has(brokenPos) ? brokenPos
                : (storage.has(brokenPos.below()) ? brokenPos.below() : null);
        if (gravePos == null)
            return false;

        GraveData data = storage.get(gravePos);
        if (data != null)
        {
            SimpleContainer container = data.container();
            for (int i = 0; i < container.getContainerSize(); i++)
            {
                ItemStack stack = container.getItem(i);
                if (!stack.isEmpty())
                    level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,
                            gravePos.getX() + 0.5, gravePos.getY() + 1.0, gravePos.getZ() + 0.5, stack));
            }
            container.clearContent();
            if (!data.isXpReleased() && data.xp() > 0)
                releaseXp(level, gravePos, data.xp());
        }
        removeGrave(level, gravePos);
        return true;
    }

    private static void clearBlocks(ServerLevel level, BlockPos gravePos)
    {
        BlockPos headPos = gravePos.above();
        if (level.getBlockState(headPos).is(Blocks.PLAYER_HEAD))
            level.removeBlock(headPos, false);
        if (level.getBlockState(gravePos).is(Blocks.OAK_FENCE))
            level.removeBlock(gravePos, false);
    }

    // Remove the grave's floating name marker THIS tick. Prefers lookup by stored marker UUID (robust to the
    // zero-size marker AABB); falls back to a position AABB scan for legacy graves with no recorded id. True only
    // if a marker was found + discarded; false means the entity section hasn't drained, so removeGrave defers to
    // drainPending instead of purging storage.
    private static boolean removeMarker(ServerLevel level, BlockPos gravePos, GraveData data)
    {
        UUID markerId = data != null ? data.markerId() : null;
        if (removeMarkerById(level, markerId))
            return true;
        // legacy graves (no stored markerId) or an id that failed to resolve: scan the grave's AABB
        return removeMarkerByBox(level, new AABB(gravePos).inflate(1.5, 3.0, 1.5));
    }

    // discard the marker by exact UUID if it resolves to a tagged ArmorStand
    private static boolean removeMarkerById(ServerLevel level, UUID markerId)
    {
        if (markerId == null)
            return false;
        net.minecraft.world.entity.Entity entity = level.getEntity(markerId);
        if (entity instanceof ArmorStand stand && stand.getPersistentData().getBoolean(MARKER_TAG))
        {
            stand.discard();
            return true;
        }
        return false;
    }

    // discard any tagged marker ArmorStands within box (legacy/fallback path); true if any went
    private static boolean removeMarkerByBox(ServerLevel level, AABB box)
    {
        List<ArmorStand> found = level.getEntitiesOfClass(ArmorStand.class, box,
                s -> s.getPersistentData().getBoolean(MARKER_TAG));
        for (ArmorStand stand : found)
            stand.discard();
        return !found.isEmpty();
    }

    // Admin cleanup (/grave cleannames): discard orphaned grave name-marker ArmorStands across every loaded
    // level; returns the count. A stand is ORPHANED only when ALL hold, so an active grave whose chunk merely
    // loaded is never touched:
    //   - carries the su_grave_marker flag (one of ours), AND
    //   - its UUID is NOT in this level's live marker ids (GraveStorage.liveMarkerIds()), AND
    //   - no grave record within tolerance of the fence directly below (legacy safety for graves whose markerId
    //     was never recorded, so their marker isn't in the live-id set).
    // discard() (no drops/sound), not kill().
    public static int sweepOrphanedMarkers(MinecraftServer server)
    {
        int discarded = 0;
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
                continue;
            GraveStorage storage = GraveStorage.get(level);
            java.util.Set<UUID> live = storage.liveMarkerIds();

            List<ArmorStand> stands = new ArrayList<>();
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities())
            {
                if (entity instanceof ArmorStand stand
                        && stand.getPersistentData().getBoolean(MARKER_TAG))
                    stands.add(stand);
            }

            for (ArmorStand stand : stands)
            {
                // belongs to a current grave record: never discard
                if (live.contains(stand.getUUID()))
                    continue;
                // legacy safety: no id link, but a grave record sits ~2 below (the fence). Keep it.
                if (hasGraveRecordBelow(storage, stand))
                    continue;
                stand.discard();
                discarded++;
            }
        }
        return discarded;
    }

    // Orphan-sweep legacy check: is there a grave record at the fence pos implied by this marker? Marker spawns
    // at fence.y + 1.4, so fence y = round(marker.y - 1.4); probe that y +/- 1 for rounding/drift. True means the
    // stand is a live/legacy grave's marker and must NOT be discarded.
    private static boolean hasGraveRecordBelow(GraveStorage storage, ArmorStand stand)
    {
        int x = (int) Math.floor(stand.getX());
        int z = (int) Math.floor(stand.getZ());
        int fenceY = (int) Math.round(stand.getY() - 1.4);
        for (int dy = -1; dy <= 1; dy++)
        {
            if (storage.has(new BlockPos(x, fenceY + dy, z)))
                return true;
        }
        return false;
    }
}
