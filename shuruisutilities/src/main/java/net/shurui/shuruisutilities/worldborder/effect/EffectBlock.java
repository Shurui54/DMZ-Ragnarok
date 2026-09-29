package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.events.player.PlayerMoveEvent;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.api.key.WorldBorderHooks;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

public class EffectBlock extends WorldBorderEffect
{
    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
    }

    @Override
    public String getSyntax()
    {
        return "";
    }

    @Override
    public void playerMove(WorldBorder border, PlayerMoveEvent event)
    {
        // Do NOT cancel the move. A canceled move is teleported back to the pre-move position by
        // PlayerPositionEventFactory, and when a player holds a key against the border that repeats every tick, which
        // freezes them solid at the edge: the exact "stuck in the border" bug. Instead, only when the player is
        // genuinely on or past the edge do we pull them cleanly inside (a no-op while they are still within the
        // trigger band but inside the border), so they can always keep moving and are never trapped.
        if (event.getEntity() instanceof ServerPlayer player)
        {
            ChatOutputHandler.chatWarning(player, "You're not allowed to move past the world border!");
            WorldBorderHooks.get().enforceInsideNow(player);
        }
    }
}
