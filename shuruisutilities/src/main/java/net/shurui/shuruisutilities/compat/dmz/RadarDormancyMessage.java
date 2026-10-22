package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dragonminez.common.dragonball.DragonRadarDefinition;
import com.dragonminez.common.init.item.DragonRadarItem;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Right-clicking a DragonMineZ dragon radar tells the holder how long that radar's ball SET has before it wakes
 * (the dormancy the wish cycle drives, see {@link BallDormancy}), or that it is active. This covers DMZ's own
 * earth and namek radars and every set we add through DMZ's datapack radar system (Black Star, Super, Cerulean):
 * they are all {@link DragonRadarItem} instances, so one handler serves them all.
 *
 * <h2>Why a Forge event, not a mixin</h2>
 *
 * <p>DMZ's {@code DragonRadarItem.use} already owns right-click: it cycles the radar range and prints its own
 * action-bar line, on its own 320-tick cooldown. Rather than mix into that vanilla-override method (a remap and
 * argument-capture risk for no gain), this listens for the FORGE {@link PlayerInteractEvent.RightClickItem}, which
 * fires for the same click WITHOUT touching DMZ's flow: the range still cycles. Because the event fires BEFORE
 * DMZ's use(), whose own range line then replaced ours the same tick, the line is sent one tick later and repeats the
 * range DMZ showed, followed by each set's timer in hours and minutes. The event is not cancelled and no result is set, so DMZ's behaviour is untouched. It fires
 * even while the range toggle is on its cooldown, so the status is always available on a right-click.
 *
 * <p>Server side only: the deadline lives in {@link BallDormancyStorage} on the server the player is on, which is
 * the authority for that shard, so the message is computed there and sent to the one player. Guarded end to end so
 * a DMZ internals change degrades to no message rather than a crash. Registered on the FORGE bus by annotation,
 * like {@link BallDormancyEvents}; DMZ is a mandatory dependency so this always loads.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class RadarDormancyMessage
{
    private RadarDormancyMessage() {}

    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        try
        {
            if (event.getLevel().isClientSide)
                return;
            ItemStack stack = event.getItemStack();
            if (!(stack.getItem() instanceof DragonRadarItem radar))
                return;
            if (!(event.getEntity() instanceof ServerPlayer player))
                return;
            MinecraftServer server = player.getServer();
            if (server == null)
                return;

            List<String> sets = radarSets(radar);
            if (sets.isEmpty())
                return;
            int rangeBefore = stack.hasTag() ? stack.getTag().getInt(RANGE_TAG) : 0;
            boolean onCooldown = player.getCooldowns().isOnCooldown(stack.getItem());
            InteractionHand hand = event.getHand();
            // Sent on the NEXT tick, after DMZ's own use() has put its range line on the action bar this tick (Forge's
            // RightClickItem fires BEFORE the item's use, so a line sent here was replaced at once; with two sets the
            // second also replaced the first). The range DMZ just showed is repeated in front, so nothing is lost.
            server.tell(new TickTask(server.getTickCount() + 1, () -> {
                try
                {
                    if (player.hasDisconnected())
                        return;
                    MutableComponent line = Component.empty();
                    ItemStack held = player.getItemInHand(hand);
                    if (!onCooldown && held.getItem() == stack.getItem() && held.hasTag())
                    {
                        int range = held.getTag().getInt(RANGE_TAG);
                        if (range > 0 && range != rangeBefore)
                            line.append(Component.translatable(RANGE_KEY, range)).append(Component.literal("  |  "));
                    }
                    for (int i = 0; i < sets.size(); i++)
                    {
                        if (i > 0)
                            line.append(Component.literal("  |  "));
                        line.append(timerLine(server, sets.get(i)));
                    }
                    player.displayClientMessage(line, true);
                }
                catch (Throwable t)
                {
                    LoggingHandler.sulog.warn("[dormancy] radar status message failed: {}", t.toString());
                }
            }));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] radar status message failed: {}", t.toString());
        }
    }

    /** DMZ's radar NBT key for the current range, and its action-bar text key (DMZ's own lang). */
    private static final String RANGE_TAG = "RadarRange";
    private static final String RANGE_KEY = "gui.dmzradar.range";

    /**
     * "Earth dragon balls dormant: 3h 12m left" or "Earth dragon balls active", from the same source of truth as
     * {@code /dormancystatus} ({@link BallDormancy#remainingMillis}, network-wide through {@link BallDormancyNetwork}).
     * Literal text, so it needs nothing on the client.
     */
    static MutableComponent timerLine(MinecraftServer server, String setId)
    {
        String name = BallDormancy.displayName(setId).getString();
        String noun = name.toLowerCase(java.util.Locale.ROOT).endsWith("dragon ball") ? name + "s" : name + " dragon balls";
        long remaining = BallDormancy.remainingMillis(server, setId);
        if (remaining < 0L)
            return Component.literal(noun + " active").withStyle(ChatFormatting.AQUA);
        return Component.literal(noun + " dormant: " + hoursMinutes(remaining) + " left").withStyle(ChatFormatting.GOLD);
    }

    /** Hours and minutes, e.g. "3h 12m" (hours can exceed 24), rounded up to the next minute. */
    static String hoursMinutes(long millis)
    {
        long minutes = Math.max(1L, (millis + 59_999L) / 60_000L);
        return (minutes / 60L) + "h " + (minutes % 60L) + "m";
    }

    // The ball set id(s) a radar reports on. Almost every radar is a single set (getBallSetId); getValidBallSetIds
    // covers a radar that DMZ or a datapack maps to more than one. Distinct, insertion-ordered, blanks dropped.
    private static List<String> radarSets(DragonRadarItem radar)
    {
        Set<String> ids = new LinkedHashSet<>();
        try
        {
            DragonRadarDefinition def = radar.getDefinition();
            if (def != null)
            {
                if (def.getValidBallSetIds() != null)
                    ids.addAll(def.getValidBallSetIds());
                if (def.getBallSetId() != null && !def.getBallSetId().isBlank())
                    ids.add(def.getBallSetId());
            }
        }
        catch (Throwable ignored)
        {
        }
        ids.remove("");
        return new ArrayList<>(ids);
    }
}
