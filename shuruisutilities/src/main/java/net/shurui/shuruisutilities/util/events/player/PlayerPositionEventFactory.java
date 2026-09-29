package net.shurui.shuruisutilities.util.events.player;

import java.util.HashMap;
import java.util.UUID;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.core.misc.TeleportHelper;
import net.shurui.shuruisutilities.util.events.ServerEventHandler;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent.ServerTickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

public class PlayerPositionEventFactory extends ServerEventHandler
{

    private HashMap<UUID, WarpPoint> lastPlayerPosition = new HashMap<>();

    // Last-seen dimension ResourceKey per player. ResourceKey instances are interned, so a reference compare
    // is a sound "did the dimension change" test and lets the hot idle path refresh the stored WarpPoint
    // without paying dimension().location().toString() on every tick.
    private HashMap<UUID, ResourceKey<Level>> lastDimension = new HashMap<>();

    @SubscribeEvent
    public void playerTickEvent(ServerTickEvent.PlayerTickEvent e)
    {
        if (e.side != LogicalSide.SERVER || e.phase == ServerTickEvent.Phase.START)
            return;
        Player player = (Player) e.player;
        UUID id = player.getGameProfile().getId();
        WarpPoint before = lastPlayerPosition.get(id);

        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();

        // WarpPoint.equals(WarpPoint) compares ONLY x/y/z, so a PlayerMoveEvent only ever fired on a positional
        // change. Decide that from the raw doubles up front and allocate nothing on the common idle/rotation-only
        // tick (the old code built two or three WarpPoints per player per tick, each stringifying the dimension).
        boolean moved = before == null || before.getX() != x || before.getY() != y || before.getZ() != z;

        if (before != null && moved && !player.isDeadOrDying() && player.level() != null)
        {
            WarpPoint current = new WarpPoint(e.player);
            PlayerMoveEvent event = new PlayerMoveEvent(player, before, current);
            MinecraftForge.EVENT_BUS.post(event);
            if (event.isCanceled())
            {
                // Check, if the position was not changed by one of the event handlers (compare the raw coords,
                // the exact fields WarpPoint.equals uses, instead of allocating another WarpPoint).
                if (current.getX() == player.getX() && current.getY() == player.getY()
                        && current.getZ() == player.getZ())
                    // Move the player to his last position
                    TeleportHelper.doTeleport(player, before);
            }
            lastPlayerPosition.put(id, current);
            lastDimension.put(id, player.level().dimension());
            return;
        }

        // No move event this tick. The old code still stored a fresh WarpPoint every tick, so keep the stored
        // point a faithful snapshot of the current state (position, rotation, dimension) for the next tick's
        // "before" and any cancel teleport-back. Do it by mutating in place: no allocation, and the dimension
        // string is only rebuilt when the dimension actually changed.
        if (before == null)
        {
            lastPlayerPosition.put(id, new WarpPoint(e.player));
            if (player.level() != null)
                lastDimension.put(id, player.level().dimension());
        }
        else
        {
            before.setX(x);
            before.setY(y);
            before.setZ(z);
            before.setPitch(player.getXRot());
            before.setYaw(player.getYRot());
            if (player.level() != null)
            {
                ResourceKey<Level> dim = player.level().dimension();
                if (dim != lastDimension.get(id))
                {
                    before.setDimension(dim.location().toString());
                    lastDimension.put(id, dim);
                }
            }
        }
    }

    @SubscribeEvent
    public void playerLoggedOutEvent(PlayerEvent.PlayerLoggedOutEvent e)
    {
        UUID id = e.getEntity().getGameProfile().getId();
        lastPlayerPosition.remove(id);
        lastDimension.remove(id);
    }

}
