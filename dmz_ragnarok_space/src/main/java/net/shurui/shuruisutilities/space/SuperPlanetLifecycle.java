package net.shurui.shuruisutilities.space;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The SUPER dragon-ball body lifecycle that lives OUTSIDE the landing/claim path: the safe "ball lost -> relocate"
 * detector (point 5) and the planet-aware radar guidance (point 4). Driven from {@link PlanetSpawnModule} (its
 * {@code ItemExpireEvent} handler and its server tick), so no second Forge listener is registered.
 *
 * <h3>Ball-existence detection: why a definitive DESPAWN, not a scan (the hard part)</h3>
 * A claimed body must revert to grey and relocate when its ball is truly gone, but NEVER while a copy still exists, or
 * the ball is duplicated. The catch is that a ball can sit in an OFFLINE player's inventory or in a container in an
 * UNLOADED chunk, and neither is reachable from a periodic scan through the public API. A scan that concludes "gone"
 * from mere absence would therefore dup a ball parked in a chest or carried by an offline player, which the brief
 * forbids ("prefer erring toward still exists"). So absence NEVER triggers a relocation here.
 *
 * <p>Instead relocation is driven by the ONE definitive, safe loss signal Forge gives us: {@code ItemExpireEvent}, fired
 * when a super-ball item entity reaches its ground despawn timer. That is proof THAT copy is gone. Because a super ball
 * is a single item (the god drops one block; mining it yields one item; there is no crafting/duplication path), an item
 * despawning means the ball's only copy is gone, so relocating is safe. As a belt-and-braces guard we still refuse to
 * relocate if a live copy is positively found (an online player's inventory/ender chest, another loaded item entity, or
 * the drop-site block if its chunk is loaded); positive finds are always safe, only absence is not. The despawn path
 * covers BOTH a collected ball (claimed body) and a dropped-but-uncollected one, which is exactly when a ball on the
 * ground is most likely to time out.
 *
 * <h3>Collection: when a grey body becomes claimed</h3>
 * The god's death only DROPS the ball (a block placed by {@link SuperPlanetGod}); the body stays grey and landable until
 * a player actually COLLECTS it. {@link #onBallCollected} marks the body claimed on that collection. There are two ways a
 * player obtains the ball and both are covered: mining the placed ball BLOCK ({@code BlockEvent.BreakEvent}) and picking
 * up a loose super-ball ITEM ({@code EntityItemPickupEvent}, which catches a ball an explosion or other break turned into
 * an item entity). Collection only claims a body that is in the dropped-but-uncollected state, so a stray super-ball item
 * cannot claim a body whose god is still alive.
 *
 * <p><b>Known, deliberate failure mode (errs toward "still exists"):</b> a ball DESTROYED while in an inventory/hand
 * (lava, void, /clear, fire) fires no despawn event, so that body stays claimed forever rather than risk a duplicate.
 * This is the safe direction the brief asked for; the OP {@code /spaceplanet superrelocate} command is the escape hatch
 * for that genuinely-lost case.
 */
public final class SuperPlanetLifecycle
{
    private SuperPlanetLifecycle()
    {
    }

    // the placed block for a super star, dragonminez:dball<star>_super, or null if not registered in this build.
    static Block ballBlock(int star)
    {
        return ForgeRegistries.BLOCKS.getValue(new ResourceLocation("dragonminez", "dball" + star + "_super"));
    }

    // the star number (1..7) a super-ball item stack is, or 0 if the stack is not a super ball. Matches the stack's item
    // against each of the seven super ball blocks' items, so no DragonMineZ API is touched.
    static int starOfBall(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return 0;
        }
        Item item = stack.getItem();
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            Block b = ballBlock(star);
            if (b != null && b.asItem() == item && item != net.minecraft.world.item.Items.AIR)
            {
                return star;
            }
        }
        return 0;
    }

    // the star number (1..7) a block state is a super ball of, or 0 if it is not one of the seven super ball blocks.
    static int starOfBlock(net.minecraft.world.level.block.state.BlockState state)
    {
        if (state == null)
        {
            return 0;
        }
        for (int star = 1; star <= SuperPlanetPositions.COUNT; ++star)
        {
            Block b = ballBlock(star);
            if (b != null && state.is(b))
            {
                return star;
            }
        }
        return 0;
    }

    /**
     * A player has COLLECTED a super body's ball (mined its block or picked up its item). If that body is in the
     * dropped-but-uncollected state, mark it claimed so it draws as a dragon ball and is no longer landable, and resync
     * every client. A no-op for any other state (already claimed, or no ball dropped), so a stray super-ball item cannot
     * claim a body whose god still lives, and a second collection signal for the same ball does nothing. Never throws.
     */
    public static void onBallCollected(MinecraftServer server, int star)
    {
        try
        {
            if (server == null || star < 1 || star > SuperPlanetPositions.COUNT)
            {
                return;
            }
            String superId = SuperPlanetPositions.keyFor(star);
            SuperPlanetData data = SuperPlanetData.get(server);
            if (!data.isBallDropped(superId))
            {
                return; // nothing to claim: already claimed, or the ball was never dropped for this body.
            }
            if (data.markClaimed(superId))
            {
                // the body is now claimed: repaint it from grey to a dragon ball on every client and make it un-landable.
                SpaceLayoutSync.syncAll();
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SuperPlanet] Failed handling a super-ball collection for star {}.", star, t);
        }
    }

    /**
     * A super-ball ground item just despawned. If it belongs to a body with a ball out (claimed OR dropped-but-
     * uncollected) and no live copy can be positively found anywhere reachable, relocate that body (revert to grey,
     * re-arm its god on the next landing). Never throws.
     */
    public static void onBallDespawn(MinecraftServer server, ItemEntity expiring)
    {
        try
        {
            if (server == null || expiring == null)
            {
                return;
            }
            int star = starOfBall(expiring.getItem());
            if (star == 0)
            {
                return;
            }
            String superId = SuperPlanetPositions.keyFor(star);
            SuperPlanetData data = SuperPlanetData.get(server);
            if (!data.isClaimed(superId) && !data.isBallDropped(superId))
            {
                return; // grey and no ball out (already relocated / never taken): nothing to do.
            }
            if (liveCopyExists(server, star, expiring))
            {
                return; // a copy is still out there: the despawned one was not the last, so do NOT relocate.
            }
            data.relocate(server, superId);
            // repaint the body grey and make it landable again on every client.
            SpaceLayoutSync.syncAll();
            broadcast(server, "super_ball_lost", Component.literal(Integer.toString(star)));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[SuperPlanet] Failed handling a super-ball despawn; body not relocated.", t);
        }
    }

    // true if a live copy of a super star's ball is positively found: in an ONLINE player's inventory or ender chest, as
    // another loaded item entity, or as the drop-site block if its chunk is loaded. Only POSITIVE finds are reported;
    // absence is never reported as "gone" (see the class note), so an unscannable location (offline inventory, unloaded
    // container) simply is not found here and does not, on its own, keep a body claimed past a despawn.
    private static boolean liveCopyExists(MinecraftServer server, int star, ItemEntity exclude)
    {
        Block block = ballBlock(star);
        Item item = block == null ? null : block.asItem();
        if (item == null)
        {
            return false;
        }
        // online inventories + ender chests.
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            if (player.getInventory().hasAnyMatching(s -> s.is(item)))
            {
                return true;
            }
            var ender = player.getEnderChestInventory();
            for (int i = 0; i < ender.getContainerSize(); ++i)
            {
                if (ender.getItem(i).is(item))
                {
                    return true;
                }
            }
        }
        // other loaded item entities across every level.
        for (ServerLevel level : server.getAllLevels())
        {
            for (Entity entity : level.getAllEntities())
            {
                if (entity != exclude && entity instanceof ItemEntity ie && ie.getItem().is(item))
                {
                    return true;
                }
            }
        }
        // the drop-site block, if its surface chunk happens to be loaded.
        ServerLevel surface = SurfaceDimension.level(server);
        if (surface != null)
        {
            var centre = SurfaceDimension.cellCentre(SuperPlanetPositions.keyFor(star));
            int cx = (int) Math.floor(centre.x);
            int cz = (int) Math.floor(centre.z);
            if (surface.isLoaded(new net.minecraft.core.BlockPos(cx, (int) SurfaceDimension.SURFACE_Y, cz)))
            {
                for (int y = (int) SurfaceDimension.SURFACE_Y - 8; y <= (int) SurfaceDimension.SURFACE_Y + 16; ++y)
                {
                    if (surface.getBlockState(new net.minecraft.core.BlockPos(cx, y, cz)).is(block))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void broadcast(MinecraftServer server, String key, Component... args)
    {
        Component msg = Component.translatable("message.dmz_ragnarok.core." + key, (Object[]) args);
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            player.sendSystemMessage(msg);
        }
    }
}
