package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.commons.selections.WarpPoint;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.util.WorldUtil;
import net.shurui.shuruisutilities.util.events.player.PlayerMoveEvent;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public class EffectKnockback extends WorldBorderEffect
{

    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
    }

    @Override
    public void playerMove(WorldBorder border, PlayerMoveEvent event)
    {
        ServerPlayer player = (ServerPlayer) event.getEntity();
        if (!event.before.getDimension().equals(event.after.getDimension()))
        {
            // Cancel event if player was teleported
            event.setCanceled(true);
            return;
        }

        double dx = event.after.getX() - border.getCenter().getX();
        double dz = event.after.getZ() - border.getCenter().getZ();
        double len = Math.sqrt(dx * dx + dz * dz);
        // A player sitting exactly on the border centre has no outward direction: dx/len and dz/len would be
        // 0/0 = NaN, and teleporting to a NaN position corrupts their location (they drop through the world or the
        // client wedges). There is nothing to push back from at the centre, so do nothing.
        if (len < 1.0e-4)
            return;

        // THROTTLE: a ServerPlayer correction goes out as connection.teleport(), which arms Minecraft's
        // teleport-confirm handshake and makes the server IGNORE all client movement until the client acks. A
        // player holding a key against the border generates a PlayerMoveEvent every tick, so an unthrottled push
        // here fired connection.teleport every tick, the ack never caught up, and the player was frozen in place
        // with the camera snapped, the "locks you and your camera until you back away" report. Mirror the hard-clamp
        // throttle in ModuleWorldBorder: push at most a few times a second, leaving the client responsive between.
        PlayerInfo pi = PlayerInfo.get(player);
        if (!pi.checkTimeout("worldborder_knockback"))
            return;
        pi.startTimeout("worldborder_knockback", 300);

        WarpPoint p = new WarpPoint(event.after);
        p.setX(p.getX() - dx / len);
        p.setZ(p.getZ() - dz / len);
        if (!WorldUtil.isFree(p.getWorld(), p.getBlockX(), p.getBlockY(), p.getBlockZ(), 2))
            p.setY(WorldUtil.placeInWorld(p.getWorld(), p.getBlockX(), p.getBlockY(), p.getBlockZ()));

        // absMoveTo and connection.teleport both take (x, y, z, yaw, pitch): the old calls passed pitch then yaw,
        // which snapped the camera and the vehicle heading on every push. Keep the player's own view intact.
        if (player.getVehicle() != null)
            player.getVehicle().absMoveTo(p.getX(), p.getY(), p.getZ(), player.getVehicle().getYRot(),
                    player.getVehicle().getXRot());
        player.connection.teleport(p.getX(), p.getY(), p.getZ(), player.getYRot(), player.getXRot());
    }

    public String toString()
    {
        return "knockback trigger: " + triggerDistance;
    }

    public String getSyntax()
    {
        return "";
    }

}
