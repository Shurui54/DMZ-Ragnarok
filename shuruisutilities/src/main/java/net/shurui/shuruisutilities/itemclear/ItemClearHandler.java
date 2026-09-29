package net.shurui.shuruisutilities.itemclear;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;

import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.compat.dmz.DragonBallSets;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * Periodically deletes dropped item entities, with a broadcast countdown, the way a clearlag plugin does.
 *
 * <p><b>Off by default and it stays off until an operator says otherwise.</b> This destroys players' property, so
 * it is not something a server should begin doing because it updated the mod. Everything about it is config: see
 * the {@code ItemClear*} keys in SU's main config for the interval, the warning seconds, both message lines and the
 * exclusion list.
 *
 * <h2>Dragon balls are never cleared</h2>
 *
 * <p>Not as a default, as a rule: {@link DragonBallSets#isDragonBall} is checked before the operator's exclusion
 * list is even consulted, and there is no setting that turns it off. A dragon ball on the floor is one seventh of
 * something the whole server is hunting, and a timer quietly deleting it while nobody is watching is not a
 * recoverable mistake. Balls a player died or logged out with do not reach this code at all; they are in a totem.
 *
 * <h2>Timing</h2>
 *
 * <p>The countdown is driven off the server tick and only advances while the feature is enabled, so turning it off
 * resets it rather than leaving a clear pending. Warnings fire on whole-second boundaries, and a warning second
 * larger than the interval simply never comes up. A clear that removed nothing is silent, so an idle server does
 * not announce itself every few minutes.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class ItemClearHandler
{
    private ItemClearHandler() {}

    private static final String SECONDS_TOKEN = "{seconds}";
    private static final String COUNT_TOKEN = "{count}";

    /** Ticks counted since the last clear. Server thread only, so no synchronisation. */
    private static int ticks;

    /**
     * Ticks left in a countdown an operator started by hand, or -1 when none is running.
     *
     * <p>Kept separate from {@link #ticks} because it must run even when the scheduled clear is switched off:
     * {@code ItemClearEnabled} governs the TIMER, not an operator's ability to clear on demand, and folding the two
     * together would make {@code /clearitems warn} silently do nothing on the servers most likely to use it.
     */
    private static int manualTicks = -1;

    // parsed forms of the two comma-separated config strings, rebuilt only when the string itself changes, so a
    // per-second parse is not done on every tick of every cycle.
    private static String cachedWarnSpec;
    private static Set<Integer> cachedWarnSeconds = Set.of();
    private static String cachedExcludeSpec;
    private static Set<ResourceLocation> cachedExcluded = Set.of();

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null)
        {
            return;
        }
        // an operator-started countdown runs regardless of the enabled flag, and is handled first so a clear it
        // triggers also resets the scheduled cycle below rather than leaving one due moments later
        if (manualTicks > 0)
        {
            manualTicks--;
            if (manualTicks % 20 == 0)
            {
                int remaining = manualTicks / 20;
                if (remaining > 0)
                {
                    if (warnSeconds().contains(remaining))
                    {
                        broadcast(SUConfig.itemClearWarnMessage, SECONDS_TOKEN, Integer.toString(remaining));
                    }
                }
                else
                {
                    manualTicks = -1;
                    ticks = 0;
                    int cleared = clearNow(server);
                    if (cleared > 0)
                    {
                        broadcast(SUConfig.itemClearDoneMessage, COUNT_TOKEN, Integer.toString(cleared));
                    }
                    return;
                }
            }
        }

        if (!SUConfig.itemClearEnabled)
        {
            ticks = 0;
            return;
        }

        ticks++;
        if (ticks % 20 != 0)
        {
            return; // only decide on whole-second boundaries
        }

        int interval = Math.max(1, SUConfig.itemClearIntervalSeconds);
        int remaining = interval - (ticks / 20);
        if (remaining > 0)
        {
            if (warnSeconds().contains(remaining))
            {
                broadcast(SUConfig.itemClearWarnMessage, SECONDS_TOKEN, Integer.toString(remaining));
            }
            return;
        }

        ticks = 0;
        int cleared = clearNow(server);
        if (cleared > 0)
        {
            broadcast(SUConfig.itemClearDoneMessage, COUNT_TOKEN, Integer.toString(cleared));
        }
    }

    /**
     * Begin a countdown to a clear, announcing it from the largest configured warning second and warning again at
     * each smaller one, exactly as the scheduled cycle does.
     *
     * @return the seconds until the clear, or 0 when no warning seconds are configured (in which case NOTHING is
     *         started: a countdown nobody is told about is just a delayed surprise, and the caller should clear
     *         outright instead)
     */
    public static int startCountdown()
    {
        int longest = 0;
        for (int seconds : warnSeconds())
        {
            longest = Math.max(longest, seconds);
        }
        if (longest <= 0)
        {
            return 0;
        }
        manualTicks = longest * 20;
        broadcast(SUConfig.itemClearWarnMessage, SECONDS_TOKEN, Integer.toString(longest));
        return longest;
    }

    /**
     * Remove every clearable dropped item in every loaded level and return how many went. Public so a command or
     * another system can force a clear without waiting for the countdown.
     */
    public static int clearNow(MinecraftServer server)
    {
        if (server == null)
        {
            return 0;
        }
        Set<ResourceLocation> excluded = excludedItems();
        int cleared = 0;
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            // materialised into a list by getEntities before anything is discarded, so the level's entity
            // collection is never mutated mid-iteration.
            List<? extends ItemEntity> items =
                    level.getEntities(EntityTypeTest.forClass(ItemEntity.class), entity -> true);
            for (ItemEntity item : items)
            {
                if (item == null || !item.isAlive() || !isClearable(item.getItem(), excluded))
                {
                    continue;
                }
                item.discard();
                cleared++;
            }
        }
        return cleared;
    }

    // a stack may be cleared unless it is a dragon ball (never, no setting) or the operator excluded its item id.
    private static boolean isClearable(ItemStack stack, Set<ResourceLocation> excluded)
    {
        if (stack == null || stack.isEmpty())
        {
            return false;
        }
        if (DragonBallSets.isDragonBall(stack))
        {
            return false;
        }
        if (excluded.isEmpty())
        {
            return true;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id == null || !excluded.contains(id);
    }

    private static void broadcast(String template, String token, String value)
    {
        if (template == null || template.isBlank())
        {
            return; // an empty message is how an operator asks for this step to be silent
        }
        // false = in game only. The single-argument broadcast also relays to Discord, and a countdown that repeats
        // every few minutes forever is exactly the sort of thing that should not go there.
        ChatOutputHandler.broadcast(ChatOutputHandler.formatColors(template.replace(token, value)), false);
    }

    private static Set<Integer> warnSeconds()
    {
        String spec = SUConfig.itemClearWarnSeconds;
        if (spec == null)
        {
            spec = "";
        }
        if (!spec.equals(cachedWarnSpec))
        {
            Set<Integer> parsed = new HashSet<>();
            for (String part : spec.split(","))
            {
                String trimmed = part.trim();
                if (trimmed.isEmpty())
                {
                    continue;
                }
                try
                {
                    int seconds = Integer.parseInt(trimmed);
                    if (seconds > 0)
                    {
                        parsed.add(seconds);
                    }
                }
                catch (NumberFormatException ignored)
                {
                    // a typo costs that one warning, not the clear itself
                }
            }
            cachedWarnSpec = spec;
            cachedWarnSeconds = parsed;
        }
        return cachedWarnSeconds;
    }

    private static Set<ResourceLocation> excludedItems()
    {
        String spec = SUConfig.itemClearExcludedItems;
        if (spec == null)
        {
            spec = "";
        }
        if (!spec.equals(cachedExcludeSpec))
        {
            Set<ResourceLocation> parsed = new HashSet<>();
            for (String part : spec.split(","))
            {
                String trimmed = part.trim();
                if (trimmed.isEmpty())
                {
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id != null)
                {
                    parsed.add(id);
                }
                // an unparseable id is skipped rather than failing the whole list
            }
            cachedExcludeSpec = spec;
            cachedExcluded = parsed;
        }
        return cachedExcluded;
    }
}
