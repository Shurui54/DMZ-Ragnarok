package net.shurui.shuruisutilities.racing.block;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import net.shurui.shuruisutilities.api.key.RaceHooks;
import net.shurui.shuruisutilities.racing.RaceRegistries;

/**
 * The block entity behind a manually placed item spawner (a pedestal that holds a race item box). It remembers
 * the track it belongs to and an optional respawn-time override; the Ragnarok Key reads these each server tick
 * through {@link RaceHooks#spawnerTick} to place and respawn the box. Keyless, the hook default is a no-op, so the
 * pedestal is inert decoration and this block entity does nothing.
 *
 * <p>The two fields ARE persisted (a placed spawner is real world data an operator authored), so they survive a
 * save. Everything else about the box lives in race state and is never saved.
 */
public class RaceItemSpawnerBlockEntity extends BlockEntity
{
    /** The track this spawner belongs to (empty until an admin binds it). */
    private String trackId = "";
    /** Respawn ticks for this spawner's box; -1 means "use the track / global default". */
    private int respawnOverride = -1;

    public RaceItemSpawnerBlockEntity(BlockPos pos, BlockState state)
    {
        super(RaceRegistries.RACE_ITEM_SPAWNER_BE.get(), pos, state);
    }

    public String getTrackId()
    {
        return trackId;
    }

    public void setTrackId(String id)
    {
        this.trackId = id == null ? "" : id;
        this.setChanged();
    }

    public int getRespawnOverride()
    {
        return respawnOverride;
    }

    public void setRespawnOverride(int ticks)
    {
        this.respawnOverride = ticks;
        this.setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag)
    {
        super.saveAdditional(tag);
        tag.putString("TrackId", trackId);
        tag.putInt("Respawn", respawnOverride);
    }

    @Override
    public void load(CompoundTag tag)
    {
        super.load(tag);
        this.trackId = tag.getString("TrackId");
        this.respawnOverride = tag.contains("Respawn") ? tag.getInt("Respawn") : -1;
    }

    /** Server ticker (bound in the block). Hands off to the key's hook; keyless it does nothing. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, RaceItemSpawnerBlockEntity be)
    {
        if (level instanceof ServerLevel server)
            RaceHooks.get().spawnerTick(server, pos, be);
    }
}
