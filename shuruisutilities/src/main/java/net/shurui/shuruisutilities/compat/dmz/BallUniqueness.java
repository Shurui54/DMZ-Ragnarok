package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.item.ItemExpireEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;

import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagInventory;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagItem;
import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * A6: at most one of each dragon ball (per set and star; a set configured with more copies allows that many) exists
 * on this server. A duplicate found anywhere this server can see is deleted, and the copy that is kept is chosen so
 * the tracked original always survives.
 *
 * <h2>What counts, and in what order a copy is kept</h2>
 * <ol>
 *   <li>A placed ball BLOCK DragonMineZ tracks, verified: its chunk is loaded and the block is really there. This is
 *       the tracked original and is never removed here.</li>
 *   <li>A grave TOTEM holding the ball (a dead or logged-out player's set, which the radar leads to).</li>
 *   <li>A ball HELD by an online player (inventory, ender chest, equipped bag, a bag carried in the inventory), the
 *       player who has been online longest first, so a copy that arrives with a login (a vault copy restored next to
 *       a totem, the restart duplicate) is the one that goes.</li>
 *   <li>A ball lying on the ground as an ITEM in a loaded chunk.</li>
 * </ol>
 * A PENDING scatter spot (a ball DMZ has not generated yet) never wins over a real copy: when the star already exists,
 * the pending spot is dropped instead, so nothing anybody holds is ever replaced by a ball that does not exist yet.
 * A tracked block whose chunk is not loaded cannot be verified and is neither counted nor removed.
 *
 * <h2>When</h2>
 * Every five seconds over online players, totems, items and loaded blocks (all in-memory or already loaded; no chunk
 * is loaded), and again a few seconds after each login. That covers spawn, scatter, wish, creative / give, item
 * duplication and restart: whatever made the extra copy, it is gone at the next pass. Every deletion is logged with
 * where the kept copy is. Containers other than these cannot hold a ball (the containment hooks and the menu sweep).
 *
 * <p>Off the network this is the whole server. On the shard network it is per server (balls held on another shard
 * are not visible here); the cross-shard authority keeps the twin worlds' blocks single.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallUniqueness
{
    private BallUniqueness() {}

    private static final int INTERVAL_TICKS = 100;

    private enum Kind { BLOCK, TOTEM, HELD, ITEM, PENDING }

    /** One copy of a ball and how to remove it. */
    private record Copy(Kind kind, long order, String where, Runnable remove) {}

    private static final Map<UUID, Long> LOGIN_TICK = new HashMap<>();

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            MinecraftServer server = player.getServer();
            LOGIN_TICK.put(player.getUUID(), (long) server.getTickCount());
            server.tell(new net.minecraft.server.TickTask(server.getTickCount() + 60, () -> enforce(server)));
        }
    }

    /**
     * A ball lost as an item (it despawned, burned, or fell into the void) is put back as a pending scatter spot in
     * its set's home, so a set never ends up short a star. DragonMineZ 2.1.3 has no such path; 2.2 adds one, and whichever
     * listener runs second sees the star already tracked and adds nothing (see {@code BallDimensionSweep.rehome}). A
     * ball item removed by this class as a duplicate is DISCARDED in the world and is not re-added.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemExpire(ItemExpireEvent event)
    {
        if (!event.isCanceled() && event.getEntity().level() instanceof ServerLevel level)
            lost(level, event.getEntity(), "despawned");
    }

    @SubscribeEvent
    public static void onItemLeave(EntityLeaveLevelEvent event)
    {
        if (!(event.getEntity() instanceof ItemEntity item) || !(event.getLevel() instanceof ServerLevel level))
            return;
        Entity.RemovalReason reason = item.getRemovalReason();
        boolean lostForGood = reason == Entity.RemovalReason.KILLED
                || (reason == Entity.RemovalReason.DISCARDED && item.getY() < level.getMinBuildHeight() - 32);
        if (lostForGood)
            lost(level, item, reason == Entity.RemovalReason.KILLED ? "destroyed" : "fell out of the world");
    }

    private static void lost(ServerLevel level, ItemEntity item, String how)
    {
        ItemStack stack = item.getItem();
        DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(stack);
        if (info == null || info.star <= 0)
            return;
        List<Integer> stars = new ArrayList<>();
        for (int n = 0; n < Math.max(1, stack.getCount()); n++)
            stars.add(info.star);
        LoggingHandler.sulog.info("[dragonball] a {} star {} ball item {} at {} {}; re-scattering it at home", info.setId,
                info.star, how, level.dimension().location(), item.blockPosition().toShortString());
        BallDimensionSweep.rehome(level.getServer(), info.setId, stars);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        LOGIN_TICK.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || event.getServer() == null)
            return;
        if (event.getServer().getTickCount() % INTERVAL_TICKS != 0)
            return;
        enforce(event.getServer());
    }

    /** Find and delete duplicates now. Returns how many copies were deleted. Never throws. */
    public static int enforce(MinecraftServer server)
    {
        try
        {
            Map<String, List<Copy>> byStar = new HashMap<>();
            collectWorld(server, byStar);
            collectTotems(server, byStar);
            collectPlayers(server, byStar);
            collectItems(server, byStar);
            int deleted = 0;
            for (Map.Entry<String, List<Copy>> e : byStar.entrySet())
            {
                List<Copy> copies = e.getValue();
                String[] key = e.getKey().split("#");
                int allowed = allowed(key[0]);
                long real = copies.stream().filter(c -> c.kind() != Kind.PENDING).count();
                if (copies.size() <= allowed)
                    continue;
                copies.sort(Comparator.comparing(Copy::kind).thenComparingLong(Copy::order));
                // Real copies keep their places first; pending spots fill only what is left of the allowance.
                int keep = 0;
                Copy kept = null;
                for (Copy c : copies)
                {
                    boolean isPending = c.kind() == Kind.PENDING;
                    if (keep < allowed && (!isPending || real < allowed))
                    {
                        keep++;
                        if (kept == null)
                            kept = c;
                        if (isPending)
                            real++;
                        continue;
                    }
                    c.remove().run();
                    deleted++;
                    LoggingHandler.sulog.warn("[dragonball] duplicate {} star {} deleted ({}); kept: {}", key[0], key[1],
                            c.where(), kept == null ? "?" : kept.where());
                }
            }
            return deleted;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragonball] duplicate check failed: {}", t.toString());
            return 0;
        }
    }

    private static int allowed(String setId)
    {
        try
        {
            DragonBallSetDefinition def = DragonBallDefinitions.getBallSet(setId);
            return def == null ? 1 : Math.max(1, def.getCopies());
        }
        catch (Throwable t)
        {
            return 1;
        }
    }

    private static void add(Map<String, List<Copy>> out, String setId, int star, Copy copy)
    {
        if (setId == null || star <= 0)
            return;
        out.computeIfAbsent(setId + "#" + star, k -> new ArrayList<>()).add(copy);
    }

    /* ---------------------------------------------------------------- sources */

    private static void collectWorld(MinecraftServer server, Map<String, List<Copy>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            DragonBallSavedData data = DragonBallSavedData.get(level);
            String dim = level.dimension().location().toString();
            for (DragonBallSetDefinition set : DragonBallDefinitions.getBallSets())
            {
                if (set == null || set.getId() == null)
                    continue;
                // A record outside the set's home is never a keeper (nor removed here): the start-up sweep
                // (BallDimensionSweep) moves or clears it. Counting it let a pre-1.0 overworld Black Star leftover
                // win over the real ball on a generated planet, which was then deleted.
                if (!set.supportsDimension(level.dimension()))
                    continue;
                String setId = set.getId();
                for (Map.Entry<Integer, List<BlockPos>> e : data.getActiveBalls(setId).entrySet())
                {
                    if (e.getValue() == null)
                        continue;
                    for (BlockPos pos : new ArrayList<>(e.getValue()))
                    {
                        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null)
                            continue; // cannot verify without loading it: neither counted nor removed
                        BlockState state = level.getBlockState(pos);
                        List<BlockPos> list = e.getValue();
                        // Two verified blocks of one star: the second one is a world duplicate and goes.
                        if (state.getBlock() instanceof DragonBallBlock ball && setId.equals(ball.getBallSetId()))
                            add(out, setId, e.getKey(), new Copy(Kind.BLOCK, pos.asLong(), "block " + dim + " "
                                    + pos.toShortString(), () -> {
                                        level.removeBlock(pos, false);
                                        list.remove(pos);
                                        data.setDirty();
                                    }));
                    }
                }
                for (Map.Entry<Integer, List<BlockPos>> e : data.getPendingBalls(setId).entrySet())
                {
                    if (e.getValue() == null)
                        continue;
                    for (BlockPos pos : new ArrayList<>(e.getValue()))
                    {
                        List<BlockPos> list = e.getValue();
                        add(out, setId, e.getKey(), new Copy(Kind.PENDING, 0, "pending spot " + dim + " "
                                + pos.getX() + " " + pos.getZ(), () -> {
                                    list.remove(pos);
                                    data.setDirty();
                                }));
                    }
                }
            }
        }
    }

    private static void collectTotems(MinecraftServer server, Map<String, List<Copy>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            for (GraveData grave : GraveStorage.get(level).all())
            {
                Container c = grave.container();
                String where = "totem of " + grave.ownerName() + " " + level.dimension().location() + " "
                        + grave.pos().toShortString();
                for (int i = 0; i < c.getContainerSize(); i++)
                {
                    final int slot = i;
                    DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(c.getItem(i));
                    if (info != null)
                        add(out, info.setId, info.star, new Copy(Kind.TOTEM, 0, where, () -> {
                            c.setItem(slot, ItemStack.EMPTY);
                            GraveStorage.get(level).setDirty();
                        }));
                }
            }
        }
    }

    private static void collectPlayers(MinecraftServer server, Map<String, List<Copy>> out)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            long since = LOGIN_TICK.getOrDefault(player.getUUID(), 0L);
            String name = player.getGameProfile().getName();
            scanContainer(player.getInventory(), since, name + " inventory", out, player);
            scanContainer(player.getEnderChestInventory(), since, name + " ender chest", out, player);
            try
            {
                ItemStack bag = DragonBallBagCurios.findEquipped(player);
                if (!bag.isEmpty())
                    scanBag(bag, since, name + " equipped bag", out, () -> DragonBallBagCurios.persist(player, bag));
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static void scanContainer(Container c, long since, String where, Map<String, List<Copy>> out,
            ServerPlayer player)
    {
        for (int i = 0; i < c.getContainerSize(); i++)
        {
            final int slot = i;
            ItemStack stack = c.getItem(i);
            if (stack.getItem() instanceof DragonBallBagItem)
            {
                scanBag(stack, since, where + " bag", out, () -> { });
                continue;
            }
            DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(stack);
            if (info == null)
                continue;
            for (int n = 0; n < stack.getCount(); n++)
                add(out, info.setId, info.star, new Copy(Kind.HELD, since, "held by " + where, () -> {
                    ItemStack now = c.getItem(slot);
                    now.shrink(1);
                    c.setItem(slot, now.isEmpty() ? ItemStack.EMPTY : now);
                    c.setChanged();
                }));
        }
    }

    private static void scanBag(ItemStack bag, long since, String where, Map<String, List<Copy>> out, Runnable persist)
    {
        ItemStackHandler handler = DragonBallBagInventory.read(bag);
        for (int i = 0; i < handler.getSlots(); i++)
        {
            final int slot = i;
            DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(handler.getStackInSlot(i));
            if (info == null)
                continue;
            add(out, info.setId, info.star, new Copy(Kind.HELD, since, "held by " + where, () -> {
                ItemStackHandler live = DragonBallBagInventory.read(bag);
                live.setStackInSlot(slot, ItemStack.EMPTY);
                DragonBallBagInventory.write(bag, live);
                persist.run();
            }));
        }
    }

    private static void collectItems(MinecraftServer server, Map<String, List<Copy>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity entity : level.getAllEntities())
            {
                if (!(entity instanceof ItemEntity item) || item.isRemoved())
                    continue;
                DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(item.getItem());
                if (info == null)
                    continue;
                for (int n = 0; n < item.getItem().getCount(); n++)
                    add(out, info.setId, info.star, new Copy(Kind.ITEM, item.getId(), "dropped item "
                            + level.dimension().location() + " " + item.blockPosition().toShortString(), () -> {
                                ItemStack now = item.getItem().copy();
                                now.shrink(1);
                                if (now.isEmpty())
                                    item.discard();
                                else
                                    item.setItem(now);
                            }));
            }
        }
    }
}
