package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.misc.TaskRegistry;
import net.shurui.shuruisutilities.guilds.GuildChat;
import net.shurui.shuruisutilities.guilds.GuildConfig;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.raid.GuildRaidSalvageVault;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The RAID LOSS RECOVERY capture: when a planet OWNED by a guild is destroyed, walk its surface region and record a
 * configurable share of what the guild loses into the per-guild {@link GuildRaidSalvageVault}. Scheduled from the single
 * {@link PlanetDestruction#apply} destroy sequence, right before the surface-generated flag is cleared, because that is
 * the last point at which the exact stamped geometry is still readable (the {@link NaturalSurfaceOracle} snapshots it
 * then). The surface BLOCKS themselves persist well past the destroy (the slot stays rubble for days), so the actual
 * walk runs afterwards, spread across ticks.
 *
 * <h2>What is recovered</h2>
 * <ul>
 *   <li><b>Container CONTENTS</b> at {@link GuildConfig#salvageContainerRate} (default 1.0 == 100%), UNCONDITIONALLY for
 *       every block entity on the surface: natural or built, if it holds items those items are recovered. Full item
 *       stacks (NBT preserved), so an enchanted book or a named tool comes back intact.</li>
 *   <li><b>Only the PLAYER-PLACED blocks</b> at {@link GuildConfig#salvageBlockRate} (default 0.5 == 50%), as the
 *       block's item form, aggregated by type. A block is player-placed when it differs from the deterministic natural
 *       terrain the {@link NaturalSurfaceOracle} reconstructs for that exact position AND is not inside a natural
 *       vegetation or structure footprint. This is a POSITION test, never a type test: stone is natural on a stony world
 *       yet is also what a player builds with, so a type filter would delete a player's stone house. A per-block-type
 *       override table ({@link GuildConfig#salvageBlockRateOverrides}) still tunes or zeroes the rate of any type.</li>
 * </ul>
 * A WILD (unowned) planet salvages nothing: the owner guild id is null and this returns immediately.
 *
 * <h2>Order and capacity</h2>
 * Container contents are deposited into the vault BEFORE bulk blocks (see {@link #buildDeposit}), so when the vault's
 * per-guild stack cap is hit it is always cheap bulk material that overflows and is dropped, never the valuable loot.
 *
 * <h2>Performance (the walk is tick-budgeted, not synchronous)</h2>
 * The largest legal surface is a 500-wide disc, ~196,000 columns over a ~195-block band, on the order of 38 million
 * block reads, many of them forcing a chunk load. Doing that inside the destroy would stall the server for seconds, and
 * the old synchronous walk also scanned from the far corner INWARD, so a truncated scan covered an empty rim strip and
 * never reached the player's base at the centre. So the walk now runs as a {@link SalvageTask} on the
 * {@link TaskRegistry} tick loop:
 * <ul>
 *   <li>It scans <b>centre-outward</b> in expanding rings, so the player's base (they land at the centre) is covered
 *       first and a truncation only ever drops the outer, mostly natural/empty rim.</li>
 *   <li>Each tick is bounded to {@link SalvageTask#READS_PER_TICK} block reads, {@link SalvageTask#COLUMNS_PER_TICK}
 *       columns examined and {@link SalvageTask#CHUNK_LOADS_PER_TICK} freshly loaded chunks, so no single tick is heavy
 *       and the destroy itself never stalls. A whole planet's worth of salvage finishes over a few seconds of ticks.</li>
 *   <li>A hard {@link SalvageTask#MAX_POSITIONS_SCANNED} ceiling still bounds the total, but because the scan is
 *       centre-first the ceiling now guarantees the central base region is covered before it stops.</li>
 *   <li>The vault's own {@link GuildRaidSalvageVault#MAX_STACKS_PER_GUILD} stack cap bounds what is actually stored.</li>
 * </ul>
 * A truncation of either bound is logged exactly once (latched). Because natural terrain is now filtered out by position,
 * the vault no longer fills with bulk stone even when the rim IS scanned, so a truncation loses only real overflow.
 *
 * <h2>Defensiveness (why the destroy always completes)</h2>
 * {@link #capture} only builds the oracle and schedules the task; it wraps that in a latched try/catch so the surrounding
 * {@link PlanetDestruction#apply} eviction/unclaim/resync always completes. The task's own tick body is likewise wrapped,
 * so a misbehaving block entity or an out-of-memory on a giant pile is swallowed, logged once, and the salvage gathered
 * so far is deposited. Losing salvage is acceptable; breaking the destroy is not.
 */
public final class PlanetSalvage
{
    private PlanetSalvage()
    {
    }

    // one-shot latch so an unexpected capture failure is logged once, not on every destroy.
    private static final AtomicBoolean CAPTURE_WARNED = new AtomicBoolean(false);
    // one-shot latch so hitting either bound (scan ceiling or vault cap) is logged once.
    private static final AtomicBoolean TRUNCATE_WARNED = new AtomicBoolean(false);
    // one-shot latch so the "no persisted stamp snapshot, recomputed from live config" note is logged once.
    private static final AtomicBoolean FALLBACK_WARNED = new AtomicBoolean(false);

    /**
     * Schedule the salvage capture for a just-destroyed planet into its owning guild's vault. Never throws. A null owner
     * guild id (a wild planet) or a missing surface dimension is a no-op. The oracle is built HERE, synchronously, before
     * the caller clears the stamped geometry, so it captures the exact size/theme/params the ground was built with; the
     * walk then runs over the following ticks.
     *
     * <p>This needs NO moon-specific overload: it reads only {@code planet.id}, and a moon reaches here as a
     * {@link GeneratedPlanets#forMoon} adapter whose id is the {@code sumoon:} moon id. A moon's surface is stamped by the
     * identical id-keyed {@link SurfaceStamp}/{@link NaturalSurfaceOracle} pipeline a generated planet's is, so a busted
     * OWNED moon salvages its surface (container contents plus player-placed blocks) exactly like a busted planet, and a
     * wild moon salvages nothing (null owner).
     *
     * @param ownerGuildId the guild that OWNED the planet, captured by the caller BEFORE it unclaimed the planet.
     */
    public static void capture(MinecraftServer server, GeneratedPlanets.Generated planet, String ownerGuildId)
    {
        if (server == null || planet == null || ownerGuildId == null)
        {
            return;   // wild planet, or nothing to key on: salvage nothing.
        }
        try
        {
            ServerLevel surface = SurfaceDimension.level(server);
            if (surface == null)
            {
                return;
            }
            GuildConfig cfg = GuildManager.config();
            double containerRate = clampRate(cfg.salvageContainerRate);
            double blockRate = clampRate(cfg.salvageBlockRate);

            String planetId = planet.id;
            Vec3 centre = SurfaceDimension.cellCentre(planetId);
            NaturalSurfaceOracle oracle = new NaturalSurfaceOracle(server, planetId);
            if (!oracle.hadSnapshot() && FALLBACK_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info("[PlanetSalvage] Planet {} had no persisted stamp snapshot (stamped by an "
                        + "older build); the natural-terrain filter was recomputed from the live config.", planetId);
            }
            int half = oracle.half();
            int cx = (int) Math.floor(centre.x);
            int cz = (int) Math.floor(centre.z);

            // vertical band: from the deepest terrain block up to the play altitude plus the configured build allowance.
            // Below is void; above is empty sky (cheap air skips). yMin is kept at the terrain floor deliberately: the
            // user wants EVERY inventory recovered, so a deep player chest must still be reached; the per-tick budget
            // below is what actually bounds the cost, not a tighter band.
            int yMin = SurfaceStamp.lowestTerrainY();
            int yMax = (int) Math.floor(SurfaceDimension.SURFACE_Y) + Math.max(0, cfg.salvageScanHeightAbove);

            SalvageTask task = new SalvageTask(server, surface, cx, cz, half, oracle, ownerGuildId, yMin, yMax,
                    containerRate, blockRate, cfg);
            TaskRegistry.schedule(task);
        }
        catch (Throwable t)
        {
            // The destroy MUST complete: swallow everything, log once, proceed with no salvage scheduled.
            if (CAPTURE_WARNED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.warn("[PlanetSalvage] Salvage scheduling failed for a destroyed planet; "
                        + "the destroy proceeds without salvage.", t);
            }
        }
    }

    /**
     * The resumable worker that walks a destroyed planet's surface disc across ticks, gathering container contents and
     * player-placed blocks. One task per destroyed planet; it runs to completion regardless of who is (or is not) on the
     * surface, since the destroyer is usually off in space. Read-only: it never edits blocks, so it does not count
     * against the registry's block-task throttle, but it self-limits its per-tick cost with the three budgets below.
     */
    static final class SalvageTask implements TaskRegistry.TickTask
    {
        // hard ceiling on total block reads for one capture. Raised from the old synchronous 3,000,000 now that the work
        // is spread across ticks; combined with the centre-outward sweep this comfortably covers the central base region
        // of even the largest planet (a ~280-block-wide centred disc at the default band) before it stops.
        static final int MAX_POSITIONS_SCANNED = 16_000_000;
        // per-tick budgets. Reads bound the getBlockState cost of a tick (checked at column boundaries, so a tick may
        // overshoot by at most one column's band). Columns bound the cheap cell iteration over the disc's empty corners.
        // Chunk loads bound the ONE genuinely heavy per-tick cost: forcing an unloaded surface chunk in from disk.
        static final int READS_PER_TICK = 262_144;
        static final int COLUMNS_PER_TICK = 8_192;
        static final int CHUNK_LOADS_PER_TICK = 8;

        private final MinecraftServer server;
        private final ServerLevel surface;
        private final int cx;
        private final int cz;
        private final int half;
        private final NaturalSurfaceOracle oracle;
        private final String ownerGuildId;
        private final int yMin;
        private final int yMax;
        private final double containerRate;
        private final double blockRate;
        private final GuildConfig cfg;

        // container CONTENTS as full stacks (NBT preserved), and player-placed blocks aggregated by type so the rate can
        // be applied once per type at the end.
        private final List<ItemStack> containerStacks = new ArrayList<>();
        private final Map<Block, Long> blockCounts = new HashMap<>();
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final int[] cell = new int[2];

        // centre-outward ring cursor over the disc's bounding square: ring is the Chebyshev radius, edge/idx walk that
        // ring's perimeter (top, bottom, left, right edges). Ring 0 is the single centre column.
        private int ring;
        private int edge;
        private int idx;
        private long scanned;
        private boolean truncated;
        private boolean finished;

        SalvageTask(MinecraftServer server, ServerLevel surface, int cx, int cz, int half, NaturalSurfaceOracle oracle,
                String ownerGuildId, int yMin, int yMax, double containerRate, double blockRate, GuildConfig cfg)
        {
            this.server = server;
            this.surface = surface;
            this.cx = cx;
            this.cz = cz;
            this.half = half;
            this.oracle = oracle;
            this.ownerGuildId = ownerGuildId;
            this.yMin = yMin;
            this.yMax = yMax;
            this.containerRate = containerRate;
            this.blockRate = blockRate;
            this.cfg = cfg;
        }

        @Override
        public boolean tick()
        {
            int columnsThisTick = 0;
            int readsThisTick = 0;
            int loadsThisTick = 0;
            try
            {
                while (true)
                {
                    if (ring > half)
                    {
                        finish();
                        return true;   // whole disc covered.
                    }
                    if (columnsThisTick >= COLUMNS_PER_TICK || readsThisTick >= READS_PER_TICK)
                    {
                        return false;   // per-tick budget spent: resume next tick from the same cursor.
                    }
                    currentCell(cell);
                    int dx = cell[0];
                    int dz = cell[1];
                    columnsThisTick++;

                    if (!oracle.columnInside(dx, dz))
                    {
                        advance();
                        continue;   // outside the disc/rim: void, nothing to read.
                    }

                    int wx = cx + dx;
                    int wz = cz + dz;
                    if (!surface.getChunkSource().hasChunk(wx >> 4, wz >> 4))
                    {
                        if (loadsThisTick >= CHUNK_LOADS_PER_TICK)
                        {
                            return false;   // chunk-load budget spent: retry THIS column next tick (cursor not advanced).
                        }
                        loadsThisTick++;
                    }

                    readsThisTick += (yMax - yMin + 1);
                    if (scanColumn(dx, dz, wx, wz))
                    {
                        // ceiling hit inside the column: stop the whole scan, report truncation.
                        truncated = true;
                        finish();
                        return true;
                    }
                    advance();
                }
            }
            catch (Throwable t)
            {
                // never wedge the tick loop or lose the destroy's aftermath: swallow, log once, deposit what we have.
                if (CAPTURE_WARNED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.warn("[PlanetSalvage] Salvage walk failed for a destroyed planet; "
                            + "depositing what was gathered so far.", t);
                }
                finish();
                return true;
            }
        }

        // read one in-disc column's vertical band. Returns true if the read ceiling was hit (caller stops the scan).
        private boolean scanColumn(int dx, int dz, int wx, int wz)
        {
            for (int y = yMin; y <= yMax; ++y)
            {
                if (++scanned > MAX_POSITIONS_SCANNED)
                {
                    return true;
                }
                pos.set(wx, y, wz);
                BlockState state = surface.getBlockState(pos);
                if (state.isAir())
                {
                    continue;
                }
                // container contents UNCONDITIONALLY for every block entity (natural or built), before the block filter,
                // so an inventory is never dropped by the position test. The hasBlockEntity gate keeps this cheap.
                if (state.hasBlockEntity())
                {
                    BlockEntity be = surface.getBlockEntity(pos);
                    if (be != null)
                    {
                        collectContainer(be, containerStacks);
                    }
                }
                // the block itself, only if it has an item form AND was placed by a player: it must differ from the
                // deterministic natural terrain at this position AND not sit inside a natural vegetation or structure
                // footprint. This is a POSITION test, so a player's stone house on a stony world is salvaged while the
                // stony world's own stone is not.
                Item item = state.getBlock().asItem();
                if (item == Items.AIR)
                {
                    continue;
                }
                BlockState expected = oracle.expectedTerrain(dx, dz, y);
                if (expected != null && expected.getBlock() == state.getBlock())
                {
                    continue;   // matches the natural terrain here: originally generated.
                }
                if (oracle.inVegetationFootprint(dx, dz, y) || oracle.inStructureFootprint(dx, dz, y))
                {
                    continue;   // natural vegetation/rock-decor or a hut/village pad: originally generated.
                }
                blockCounts.merge(state.getBlock(), 1L, Long::sum);
            }
            return false;
        }

        // fill c[0]=dx, c[1]=dz for the current ring/edge/idx cursor.
        private void currentCell(int[] c)
        {
            if (ring == 0)
            {
                c[0] = 0;
                c[1] = 0;
                return;
            }
            switch (edge)
            {
                case 0:            // top edge: dz = -ring, dx = -ring .. ring
                    c[0] = -ring + idx;
                    c[1] = -ring;
                    break;
                case 1:            // bottom edge: dz = ring, dx = -ring .. ring
                    c[0] = -ring + idx;
                    c[1] = ring;
                    break;
                case 2:            // left edge: dx = -ring, dz = -ring+1 .. ring-1 (corners already covered above)
                    c[0] = -ring;
                    c[1] = -ring + 1 + idx;
                    break;
                default:           // right edge: dx = ring, dz = -ring+1 .. ring-1
                    c[0] = ring;
                    c[1] = -ring + 1 + idx;
                    break;
            }
        }

        // advance the ring cursor to the next perimeter cell, stepping out to the next ring when this one is exhausted.
        private void advance()
        {
            if (ring == 0)
            {
                ring = 1;
                edge = 0;
                idx = 0;
                return;
            }
            int max = (edge == 0 || edge == 1) ? 2 * ring : 2 * ring - 2;
            if (idx < max)
            {
                idx++;
                return;
            }
            idx = 0;
            if (edge < 3)
            {
                edge++;
                return;
            }
            ring++;
            edge = 0;
            idx = 0;
        }

        // deposit what was gathered, once, and notify the owning guild. Idempotent via the finished latch.
        private void finish()
        {
            if (finished)
            {
                return;
            }
            finished = true;
            List<ItemStack> toStore = buildDeposit(containerStacks, blockCounts, containerRate, blockRate, cfg);
            GuildRaidSalvageVault vault = GuildRaidSalvageVault.get(server);
            boolean allStored = vault.deposit(ownerGuildId, toStore);
            if ((truncated || !allStored) && TRUNCATE_WARNED.compareAndSet(false, true))
            {
                GuildRaidSalvageVault.logTruncated(ownerGuildId);
            }
            notifyGuild(ownerGuildId, vault.size(ownerGuildId));
        }

        @Override
        public boolean editsBlocks()
        {
            return false;   // read-only walk; it self-limits per tick rather than riding the block-task throttle.
        }
    }

    // copy a block entity's contents as full stacks. Prefers the Container interface (a chest reports its OWN slots,
    // so a double chest is not double counted through a combined handler), falling back to the Forge item-handler
    // capability for block entities that only expose items that way (some DMZ machines). Read-only: the source is on a
    // now-unreachable destroyed surface, so copying rather than clearing keeps this side-effect-free and is the safe
    // choice for a step that must never throw.
    private static void collectContainer(BlockEntity be, List<ItemStack> out)
    {
        if (be instanceof Container container)
        {
            int size = container.getContainerSize();
            for (int i = 0; i < size; i++)
            {
                ItemStack stack = container.getItem(i);
                if (stack != null && !stack.isEmpty())
                {
                    out.add(stack.copy());
                }
            }
            return;
        }
        IItemHandler handler = be.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (handler != null)
        {
            for (int i = 0; i < handler.getSlots(); i++)
            {
                ItemStack stack = handler.getStackInSlot(i);
                if (stack != null && !stack.isEmpty())
                {
                    out.add(stack.copy());
                }
            }
        }
    }

    // turn the gathered container stacks and per-type block counts into the flat list to deposit, applying the rates.
    // Containers go FIRST so they win the vault's capacity race; bulk block stacks follow.
    private static List<ItemStack> buildDeposit(List<ItemStack> containerStacks, Map<Block, Long> blockCounts,
                                                double containerRate, double blockRate, GuildConfig cfg)
    {
        List<ItemStack> toStore = new ArrayList<>();

        // container contents at the container rate (default 100%). Scale each stack's count when the rate is below 1,
        // preserving its NBT; a stack that scales to zero is dropped.
        for (ItemStack stack : containerStacks)
        {
            if (containerRate >= 1.0)
            {
                toStore.add(stack);
                continue;
            }
            int kept = (int) Math.floor(stack.getCount() * containerRate);
            if (kept > 0)
            {
                ItemStack scaled = stack.copy();
                scaled.setCount(kept);
                toStore.add(scaled);
            }
        }

        // player-placed blocks at the per-type override, else the flat block rate. Aggregate counts let a whole base's
        // cobblestone be rated once and split into full stacks, instead of rate-rounding every single block.
        for (Map.Entry<Block, Long> e : blockCounts.entrySet())
        {
            Block block = e.getKey();
            double rate = rateForBlock(block, blockRate, cfg);
            if (rate <= 0.0)
            {
                continue;   // explicitly excluded by an override of 0.
            }
            long salvage = (long) Math.floor(e.getValue() * rate);
            if (salvage <= 0)
            {
                continue;
            }
            Item item = block.asItem();
            if (item == Items.AIR)
            {
                continue;
            }
            int max = new ItemStack(item).getMaxStackSize();
            while (salvage > 0)
            {
                int n = (int) Math.min((long) max, salvage);
                toStore.add(new ItemStack(item, n));
                salvage -= n;
            }
        }
        return toStore;
    }

    // the recovery rate for a block type: its override in the config if present, else the flat block rate.
    private static double rateForBlock(Block block, double blockRate, GuildConfig cfg)
    {
        if (cfg.salvageBlockRateOverrides != null && !cfg.salvageBlockRateOverrides.isEmpty())
        {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
            if (id != null)
            {
                Double override = cfg.salvageBlockRateOverrides.get(id.toString());
                if (override != null)
                {
                    return clampRate(override);
                }
            }
        }
        return blockRate;
    }

    // tell the owning guild's online members their world was destroyed and salvage awaits in the guild GUI. Wrapped so
    // a messaging hiccup can never touch the destroy. A zero pile (nothing recovered) still notifies, so the loss is
    // acknowledged even when the split or the cap left nothing.
    private static void notifyGuild(String guildId, int stackCount)
    {
        try
        {
            Guild guild = GuildManager.byId(guildId);
            if (guild != null)
            {
                GuildChat.sendToGuild(guild, Component.translatable(
                        "message.dmz_ragnarok.core.planet_salvage_recovered", stackCount));
            }
        }
        catch (Throwable ignored)
        {
            // never let a notification failure affect the destroy.
        }
    }

    // clamp a configured rate into 0..1 so a stray value in config.json can never produce negative or >100% salvage.
    private static double clampRate(double rate)
    {
        if (rate < 0.0)
        {
            return 0.0;
        }
        return Math.min(rate, 1.0);
    }
}
