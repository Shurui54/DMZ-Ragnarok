package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.dragonminez.common.dragonball.DragonRadarDefinition;
import com.dragonminez.common.init.item.DragonRadarItem;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
 * fires for the same click WITHOUT touching DMZ's flow: the range still cycles, and this simply adds the dormancy
 * line ALONGSIDE it. The event is not cancelled and no result is set, so DMZ's behaviour is untouched. It fires
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

            for (String setId : radarSets(radar))
                player.displayClientMessage(BallDormancy.radarStatus(server, setId), true);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dormancy] radar status message failed: {}", t.toString());
        }
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
