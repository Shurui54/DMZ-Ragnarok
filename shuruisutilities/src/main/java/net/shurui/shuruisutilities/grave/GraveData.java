package net.shurui.shuruisutilities.grave;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * One grave's persisted state: pos, owner, stored XP, 27 content slots. Contents live in-memory as a
 * {@link SimpleContainer} (so an open ChestMenu mutates them directly); NBT only on save/load.
 */
public final class GraveData
{
    // 27 = three chest rows; main-inv range 9-35 is exactly 27 stacks so it always fits
    public static final int SIZE = 27;

    private final BlockPos pos;
    private final UUID ownerId;
    private final String ownerName;
    private final SimpleContainer container;
    private int xp; // raw experience points; released as orbs on first open
    private boolean xpReleased; // so a second open doesn't re-grant
    private boolean listenerAttached; // container listener attached at most once
    // game-time tick (monotonic, survives restart) this grave was created at. Expiry sweep despawns graves older
    // than the operator-set lifetime (KeepPartialInventory.graveDespawnMinutes()). UNSTAMPED = created before this
    // field existed / not yet stamped; see load() + stampCreatedGameTimeIfUnset for migration.
    private long createdGameTime = UNSTAMPED;
    // marker ArmorStand UUID, or null for legacy graves saved before this field. removeGrave discards the exact
    // marker by id (robust to unloaded chunks + zero-size marker AABBs) instead of an AABB scan alone.
    private UUID markerId;

    // sentinel for an un-stamped creation time (legacy NBT)
    public static final long UNSTAMPED = Long.MIN_VALUE;

    public GraveData(BlockPos pos, UUID ownerId, String ownerName, int xp)
    {
        this.pos = pos.immutable();
        this.ownerId = ownerId;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.container = new SimpleContainer(SIZE);
        this.xp = Math.max(0, xp);
        this.xpReleased = false;
    }

    private GraveData(BlockPos pos, UUID ownerId, String ownerName, int xp, boolean xpReleased,
            SimpleContainer container, long createdGameTime, UUID markerId)
    {
        this.pos = pos.immutable();
        this.ownerId = ownerId;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.container = container;
        this.xp = Math.max(0, xp);
        this.xpReleased = xpReleased;
        this.createdGameTime = createdGameTime;
        this.markerId = markerId;
    }

    public BlockPos pos()
    {
        return pos;
    }

    public UUID ownerId()
    {
        return ownerId;
    }

    public String ownerName()
    {
        return ownerName;
    }

    public SimpleContainer container()
    {
        return container;
    }

    public int xp()
    {
        return xp;
    }

    public boolean isXpReleased()
    {
        return xpReleased;
    }

    public long createdGameTime()
    {
        return createdGameTime;
    }

    public void setCreatedGameTime(long gameTime)
    {
        this.createdGameTime = gameTime;
    }

    public UUID markerId()
    {
        return markerId;
    }

    public void setMarkerId(UUID markerId)
    {
        this.markerId = markerId;
    }

    // migration: if a legacy grave has no recorded creation time, stamp it to nowGameTime so it gets a fresh
    // despawn window instead of expiring instantly. True if stamped (caller marks storage dirty).
    public boolean stampCreatedGameTimeIfUnset(long nowGameTime)
    {
        if (createdGameTime == UNSTAMPED)
        {
            createdGameTime = nowGameTime;
            return true;
        }
        return false;
    }

    // attach the container listener at most once; true the first time (caller then registers it)
    public boolean markListenerAttached()
    {
        if (listenerAttached)
            return false;
        listenerAttached = true;
        return true;
    }

    // mark XP released + zero it, after orbs are spawned
    public void consumeXp()
    {
        this.xp = 0;
        this.xpReleased = true;
    }

    // nothing left worth keeping: no items AND no unreleased XP
    public boolean isEmpty()
    {
        if (!xpReleased && xp > 0)
            return false;
        return container.isEmpty();
    }

    public CompoundTag save()
    {
        CompoundTag tag = new CompoundTag();
        tag.put("pos", NbtUtils.writeBlockPos(pos));
        if (ownerId != null)
            tag.putUUID("owner", ownerId);
        tag.putString("ownerName", ownerName);
        tag.putInt("xp", xp);
        tag.putBoolean("xpReleased", xpReleased);
        if (createdGameTime != UNSTAMPED)
            tag.putLong("createdGameTime", createdGameTime);
        if (markerId != null)
            tag.putUUID("markerId", markerId);
        ListTag items = new ListTag();
        for (int i = 0; i < SIZE; i++)
        {
            ItemStack stack = container.getItem(i);
            if (stack.isEmpty())
                continue;
            CompoundTag slot = new CompoundTag();
            slot.putByte("Slot", (byte) i);
            stack.save(slot);
            items.add(slot);
        }
        tag.put("items", items);
        return tag;
    }

    public static GraveData load(CompoundTag tag)
    {
        BlockPos pos = NbtUtils.readBlockPos(tag.getCompound("pos"));
        UUID owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        String ownerName = tag.getString("ownerName");
        int xp = tag.getInt("xp");
        boolean released = tag.getBoolean("xpReleased");
        // migration: no "createdGameTime" key -> UNSTAMPED, so the first expiry sweep stamps it to now (fresh
        // window) instead of despawning it immediately
        long created = tag.contains("createdGameTime") ? tag.getLong("createdGameTime") : UNSTAMPED;
        // no "markerId" key on legacy graves -> null (AABB fallback cleans it)
        UUID markerId = tag.hasUUID("markerId") ? tag.getUUID("markerId") : null;
        SimpleContainer container = new SimpleContainer(SIZE);
        ListTag items = tag.getList("items", 10); // 10 = CompoundTag
        for (int i = 0; i < items.size(); i++)
        {
            CompoundTag slot = items.getCompound(i);
            int index = slot.getByte("Slot") & 0xFF;
            if (index >= 0 && index < SIZE)
                container.setItem(index, ItemStack.of(slot));
        }
        return new GraveData(pos, owner, ownerName, xp, released, container, created, markerId);
    }
}
