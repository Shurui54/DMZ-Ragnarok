package net.shurui.shuruisutilities.regen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import net.shurui.shuruisutilities.grave.GraveManager;
import net.shurui.shuruisutilities.regions.RegionEventHandler;

/**
 * Where the repair engine attaches to the world.
 *
 * <p>Scope is deliberately narrow: this captures BLAST damage, not mining. Ordinary block breaking is left completely
 * alone, because a rule that put back every block a player dug would make survival unplayable, and "the world rebuilds
 * itself after it is destroyed" means craters, not quarries. In practice that covers what it needs to: DMZ's ki
 * attacks destroy terrain through explosions, so the explosion hook is the ki damage hook.
 *
 * <p>The capture runs at LOWEST priority, after the protection, guild and region handlers have had their say. Those
 * handlers work by REMOVING positions from the blast list, so by the time we see it the list holds only blocks that are
 * really going to break. That is what keeps this from arguing with the regions system: a region with ki griefing
 * denied never lets the block break at all, so there is nothing owed and nothing to restore. Prevention and repair
 * layer cleanly instead of both claiming the same block.
 */
public final class TerrainRegenHandler
{
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onExplosion(ExplosionEvent.Detonate event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        // GRAVES FIRST, and outside the early-out below. A grave is protected whether or not regen is on anywhere,
        // because the blast deletes the fence and head with no drops in either case and the repair is the only thing
        // that would ever have put them back. It also has to happen here rather than in the capture loop for the DMZ
        // laser, whose real vanilla Explosion never consults the ki-grief gate at all.
        if (GraveManager.hasAnyGrave(level))
            pullGraves(level, event.getAffectedBlocks());
        // Cheap early-out: with the gamerule off world wide AND no regions defined at all, no single position could
        // have regen enabled, so there is nothing to capture, suppress or protect and the blast proceeds untouched.
        // The moment either is true we fall through and decide per block with isEnabledAt, so a region flag alone still
        // captures inside a world whose gamerule is off. This is the fix for the region-flag gap: the old code gated on
        // the gamerule-only isEnabled(level) and never captured a region-only arena.
        if (!TerrainRegenRule.isEnabled(level) && !RegionEventHandler.hasRegions())
            return;
        // getAffectedBlocks() hands back the explosion's own live toBlow list, which is mutable in Forge 1.20.1 and is
        // the supported way to take a block away from the blast (the Detonate javadoc says as much). We iterate a copy
        // because we mutate that backing list as we go, and remove positions in one pass at the end.
        List<BlockPos> affected = event.getAffectedBlocks();
        List<BlockPos> pulled = null;
        for (BlockPos pos : new ArrayList<>(affected))
        {
            if (!TerrainRegenRule.isEnabledAt(level, pos))
                continue;
            // An item holder is left completely intact: dropped from the blast list and NOT cleared, so its contents
            // neither spill through vanilla onRemove/dropContents nor get captured and later restored, which together
            // would duplicate every item it held. holdsItems covers chests and every other Container, and also the
            // campfire and the lectern, which drop what they hold on removal without being Containers at all.
            if (TerrainRegenService.holdsItems(level, pos))
            {
                if (pulled == null)
                    pulled = new ArrayList<>();
                pulled.add(pos);
                continue;
            }
            // Ordinary block. Snapshot the old state and any block entity NBT BEFORE clearing, because capture() reads
            // the live level and would otherwise record air. Then clear it ourselves with no drops via the same path a
            // vanilla explosion uses to remove a block (removeBlock, which drops nothing and preserves waterlogging),
            // and take it off the blast list so the explosion's own drop logic never runs for it. With regen inactive
            // this whole branch is unreachable, so a normal explosion still drops its items exactly as before.
            //
            // Clearing is conditional on the snapshot having actually been taken. We destroy this block ourselves and
            // suppress its drops, so doing that against a capture that declined would delete it outright with nothing
            // owed to put it back. If the answer is no, the block stays on the blast list and the explosion handles it
            // the ordinary way, drops and all.
            if (!TerrainRegenService.capture(level, pos))
                continue;
            // removeBlock writes with neighbour updates on, so taking this block can knock over whatever was
            // resting on it. Those go through Level.destroyBlock without ever reaching a capture hook, so the
            // window is opened around it (see MixinLevelRegenCascade) to have them remembered and to keep them
            // from dropping loot for a block the repair already owes back.
            TerrainRegenService.beginCascadeCapture();
            try
            {
                level.removeBlock(pos, false);
            }
            finally
            {
                TerrainRegenService.endCascadeCapture();
            }
            if (pulled == null)
                pulled = new ArrayList<>();
            pulled.add(pos);
        }
        if (pulled != null)
            affected.removeAll(pulled);
    }

    /** Take every standing grave out of a blast's block list, so the explosion never reaches one. */
    private static void pullGraves(ServerLevel level, List<BlockPos> affected)
    {
        List<BlockPos> graves = null;
        for (BlockPos pos : affected)
        {
            if (GraveManager.isGraveBlock(level, pos))
            {
                if (graves == null)
                    graves = new ArrayList<>();
                graves.add(pos);
            }
        }
        if (graves != null)
            affected.removeAll(graves);
    }

    /**
     * Mob griefing, snapshotted so it grows back, and containers left alone so it cannot duplicate anything.
     *
     * <p>Same two jobs and the same reasoning as {@link #onExplosion}, through the other door mobs use to take a
     * block: a ravager clearing leaves, a wither blasting its arena, an ender dragon through a build. Those never
     * raise an explosion, so before this they were the one kind of damage a regen region could not repair.
     *
     * <p>An item holder is CANCELLED outright rather than captured. Removing it spills its contents through vanilla
     * {@code onRemove -> Containers.dropContents} the instant the block goes, which no drop flag controls, and the
     * snapshot would then put the chest back still holding the same items: one chest in, two chests' worth of
     * items out. Leaving it standing is the only outcome that keeps the count honest, exactly as the ki-grief
     * container guard concluded for its own path. A ravager walking over a lit campfire is the case a container-only
     * test missed, since a campfire drops what is cooking on it without being a container.
     *
     * <p>LOWEST so every protection handler has already had its say: a break the region or a guild claim refused
     * is cancelled before it reaches here, so nothing is captured for a block that was never going to break.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onMobDestroyBlock(net.minecraftforge.event.entity.living.LivingDestroyBlockEvent event)
    {
        if (event.isCanceled())
            return;
        net.minecraft.world.entity.LivingEntity e = event.getEntity();
        if (e == null || !(e.level() instanceof ServerLevel level))
            return;
        BlockPos pos = event.getPos();
        // Same reasoning as the blast path: a grave (a dragon ball totem included) is never terrain to be regrown,
        // and a mob taking it would delete the only handle on its contents.
        if (GraveManager.isGraveBlock(level, pos))
        {
            event.setCanceled(true);
            return;
        }
        if (!TerrainRegenRule.isEnabledAt(level, pos))
            return;
        if (TerrainRegenService.holdsItems(level, pos))
        {
            event.setCanceled(true);
            return;
        }
        // Snapshot BEFORE the break: capture() reads the live level, so after the fact it would record air. The
        // break itself is left to proceed normally, which keeps whatever drops the mob would ordinarily cause.
        TerrainRegenService.capture(level, pos);
    }

    @SubscribeEvent
    public void onLevelTick(TickEvent.LevelTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        // Safety net for the cascade window. It is opened and closed around each DMZ destroy, but a mixin's RETURN
        // hook does not run if the method throws, and a window left open would start capturing ordinary mining and
        // putting back blocks players deliberately broke. No destruction spans a tick boundary, so anything still
        // open here escaped, and clearing it costs one assignment.
        TerrainRegenService.resetCascadeCapture();
        if (event.level instanceof ServerLevel level)
            TerrainRegenService.drain(level);
    }

    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event)
    {
        // Pick up any crater that was still owed when the server last stopped, so terrain destroyed before a restart
        // heals now instead of standing open forever. This is the half that was missing: the debt was memory only.
        if (event.getLevel() instanceof ServerLevel level)
            TerrainRegenService.hydrateFrom(level, TerrainRegenSaved.get(level));
    }

    @SubscribeEvent
    public void onLevelSave(LevelEvent.Save event)
    {
        // Mirror the live debt into the level's own saved data on every save, so a restart at any moment loses at
        // most the work of the ticks since the last autosave, not the whole crater.
        if (event.getLevel() instanceof ServerLevel level)
        {
            TerrainRegenSaved data = TerrainRegenSaved.get(level);
            TerrainRegenService.persistInto(level, data);
            data.setDirty();
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event)
    {
        // Flush the debt to disk BEFORE dropping the in-memory copy. The old code just forgot it here, which on
        // shutdown meant every unfinished crater was gone for good. Persist first, then clear memory; the level's
        // own save on unload writes the mirror out, and the next load reads it back.
        if (event.getLevel() instanceof ServerLevel level)
        {
            TerrainRegenSaved data = TerrainRegenSaved.get(level);
            TerrainRegenService.persistInto(level, data);
            data.setDirty();
            TerrainRegenService.forget(level);
        }
    }
}
