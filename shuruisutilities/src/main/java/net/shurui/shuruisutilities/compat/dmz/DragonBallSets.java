package net.shurui.shuruisutilities.compat.dmz;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.corrupted.CorruptedBalls;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * DMZ-facing lookup that answers two questions the dragon ball bag needs: "is this item a dragon ball?" and, if
 * so, "which ball SET does it belong to?" (earth, namek, blackstar, super, or any future data-driven set).
 *
 * <p>The DMZ set list is NEVER hardcoded. It is derived at runtime from DMZ's {@link DragonBallDefinitions}: every
 * registered ball set exposes its stars and the {@link Block} for each star, and the ball ITEM is simply that
 * block's {@link Block#asItem() item form}. Mapping item -> set id this way means a set added by a future DMZ
 * datapack (or by one of our own datapacks, like blackstar / super) is picked up automatically with no code change.
 *
 * <p>SU's own {@link CorruptedBalls corrupted balls} are the one exception that cannot come from DMZ: they are
 * registered as plain SU blocks entirely outside DMZ's ball-set system, so the DMZ walk never sees them. By explicit
 * design decision every dragon ball set, corrupted included, must obey the bag and inventory rules, so they are folded
 * into the same item -> set map under the synthetic set id {@link #CORRUPTED_SET_ID}. This is our own registry, so the
 * reference is direct with no ModList guard and no classload risk.
 *
 * <p>Standing project rule: DMZ being present is not the same as DMZ being API compatible. Every read of DMZ's
 * definitions is wrapped in try/catch(Throwable); a single failure latches a one-shot warning and then degrades to
 * "this is not a dragon ball" (an empty map) forever after. That is the safe direction: the worst outcome of a
 * DMZ API shift is that balls behave like ordinary items again, never a crash and never a lost ball.
 */
public final class DragonBallSets
{
    private DragonBallSets()
    {
    }

    // synthetic set id for SU's corrupted balls, which are not part of DMZ's ball-set system. Grouped as their own
    // set so BagSlot.mayPlace's same-set rule keeps them from mixing into a bag of earth/namek/etc balls.
    public static final String CORRUPTED_SET_ID = "corrupted";

    // item -> owning ball set id (e.g. "earth", "namek", "corrupted"). null value is never stored; absence means
    // "not a ball".
    private static volatile Map<Item, String> cache = null;

    // how many ball sets the cache was built from. a datapack reload can add or drop sets, so when the live set
    // count no longer matches this we rebuild rather than trust a stale map. cheap: a handful of sets total.
    private static volatile int cachedSetCount = -1;

    // one-shot latch so a DMZ API mismatch logs exactly once instead of spamming on every pickup / slot check.
    private static volatile boolean loggedFailure = false;

    /** True when the given stack is a registered DMZ dragon ball of any set. Never throws. */
    public static boolean isDragonBall(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return false;
        }
        return setIdOf(stack.getItem()) != null;
    }

    /** The ball set id this stack belongs to, or null when the stack is not a dragon ball. Never throws. */
    public static String setIdOf(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return null;
        }
        return setIdOf(stack.getItem());
    }

    /** The ball set id this item belongs to, or null when the item is not a dragon ball. Never throws. */
    public static String setIdOf(Item item)
    {
        if (item == null)
        {
            return null;
        }
        Map<Item, String> map = map();
        return map == null ? null : map.get(item);
    }

    // return the live item->set map, rebuilding it when the ball set count has changed since the last build. any
    // failure degrades to an empty map (never null contents), so callers always see "no dragon balls" rather than
    // an exception.
    private static Map<Item, String> map()
    {
        try
        {
            Collection<DragonBallSetDefinition> sets = DragonBallDefinitions.getBallSets();
            int liveCount = sets == null ? 0 : sets.size();

            Map<Item, String> current = cache;
            if (current != null && liveCount == cachedSetCount)
            {
                return current;
            }

            Map<Item, String> rebuilt = new HashMap<>();
            if (sets != null)
            {
                for (DragonBallSetDefinition set : sets)
                {
                    if (set == null)
                    {
                        continue;
                    }
                    String id = set.getId();
                    if (id == null)
                    {
                        continue;
                    }
                    for (Integer star : set.getStars())
                    {
                        if (star == null)
                        {
                            continue;
                        }
                        Block block = set.getBlockForStar(star);
                        if (block == null)
                        {
                            continue;
                        }
                        Item item = block.asItem();
                        // asItem() returns AIR for a block with no item form; a dragon ball always has one, but
                        // guard anyway so a malformed set never maps AIR to a ball set.
                        if (item == null || item == Items.AIR)
                        {
                            continue;
                        }
                        rebuilt.put(item, id);
                    }
                }
            }

            // fold in SU's own corrupted balls under the synthetic set id. Not derived from DMZ (they live outside its
            // ball-set system), so they are added by hand from our own registry.
            addCorruptedBalls(rebuilt);

            cache = rebuilt;
            cachedSetCount = liveCount;
            return rebuilt;
        }
        catch (Throwable t)
        {
            if (!loggedFailure)
            {
                loggedFailure = true;
                LoggingHandler.sulog.warn(
                        "[dragonballbag] Could not read DMZ dragon ball definitions; dragon balls will behave as "
                                + "ordinary items (no bag routing, containment or one-type rule). Cause: {}",
                        t.toString());
            }
            // degrade to a stable empty map so nothing downstream ever NPEs; keep it cached so we do not re-throw
            // every call.
            Map<Item, String> empty = new HashMap<>();
            cache = empty;
            cachedSetCount = -1;
            return empty;
        }
    }

    // add SU's seven corrupted ball items to the map under CORRUPTED_SET_ID. Wrapped in its own try/catch so a
    // corrupted-registry hiccup (called before registration, or a shape change) can never break the DMZ ball
    // detection already built above: worst case the corrupted balls behave as ordinary items until the next rebuild.
    private static void addCorruptedBalls(Map<Item, String> map)
    {
        try
        {
            for (int star = 1; star <= CorruptedBalls.COUNT; star++)
            {
                RegistryObject<Block> ball = CorruptedBalls.BALLS[star];
                if (ball == null || !ball.isPresent())
                {
                    continue;
                }
                Item item = ball.get().asItem();
                if (item == null || item == Items.AIR)
                {
                    continue;
                }
                map.put(item, CORRUPTED_SET_ID);
            }
        }
        catch (Throwable ignored)
        {
            // leave corrupted balls out this pass rather than break the DMZ sets built above.
        }
    }
}
