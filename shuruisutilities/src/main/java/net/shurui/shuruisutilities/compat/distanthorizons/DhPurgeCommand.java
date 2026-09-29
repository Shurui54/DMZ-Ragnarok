package net.shurui.shuruisutilities.compat.distanthorizons;

import java.io.IOException;
import java.nio.file.Path;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-side self-service for the Distant Horizons data that has twice broken clients on this server (a
 * {@code decodeDataSource} NPE storm, and a "client level loading failed" chat error). A server owner cannot reach a
 * player's PC to clear it, so the player runs this on their own machine: {@code /dhpurge}.
 *
 * <p>The command does not delete anything itself, because while the player is connected DH holds the current server's
 * LOD SQLite files open. It instead resolves the folder, measures it, and QUEUES it for {@link DhPurgePending}, which
 * removes it at the next startup, before DH opens it. The player is told to restart and rejoin.</p>
 *
 * <p>It is a CLIENT command (registered on {@link RegisterClientCommandsEvent}), so there is no permission model to
 * honour: a client command is already scoped to the one player who typed it. It is safe to run when DH is absent, and
 * it never classloads a Distant Horizons type: all it does is decide which folder under the game directory to remove,
 * which is pure filesystem work in {@link DhClientData}.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DhPurgeCommand
{
    private DhPurgeCommand() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event)
    {
        // Two steps on purpose. A bare /dhpurge only REPORTS what would be removed; only /dhpurge confirm
        // queues it. This mod deleting another mod's files is exactly the shape of thing a store review
        // flags, and "one command silently arms a deletion that happens at next launch" is a fair thing to
        // flag. Making the destructive step a separate word the player has to type means the deletion is
        // never a surprise, and it costs a player nothing but one extra command.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("dhpurge")
                .executes(ctx -> execute(ctx, false))
                .then(Commands.literal("confirm").executes(ctx -> execute(ctx, true)));
        event.getDispatcher().register(root);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx, boolean confirmed)
    {
        CommandSourceStack src = ctx.getSource();

        if (!ModList.get().isLoaded("distanthorizons"))
        {
            ChatOutputHandler.chatWarning(src, "Distant Horizons is not installed, so there is no Distant Horizons data to purge.");
            return 0;
        }

        Minecraft mc = Minecraft.getInstance();
        ServerData server = mc.getCurrentServer();
        if (server == null || server.name == null || server.name.isEmpty())
        {
            ChatOutputHandler.chatError(src, "This only works while connected to a multiplayer server. Distant Horizons stores its data per server, and singleplayer LODs live inside the world save, not here.");
            return 0;
        }

        String label = ChatOutputHandler.stripFormatting(server.name);
        Path dir = DhClientData.resolveServerDir(server.name);
        if (dir == null)
        {
            ChatOutputHandler.chatWarning(src, "No Distant Horizons data folder was found for this server, so there is nothing to purge.");
            return 0;
        }

        if (!DhClientData.isSafeTarget(dir))
        {
            // Should not happen for a folder we just resolved as a direct child of the DH root, but a wrong path here
            // would delete a player's world, so we refuse rather than trust the resolution.
            ChatOutputHandler.chatError(src, "Refusing to queue the purge: the resolved folder is not safely inside the Distant Horizons data directory.");
            LoggingHandler.sulog.warn("dhpurge refused unsafe target: " + dir);
            return 0;
        }

        DhClientData.Stats stats = DhClientData.measure(dir);

        // Step one: say exactly what would go, name the folder, and stop. Nothing is written and nothing is
        // queued until the player types the second command.
        if (!confirmed)
        {
            ChatOutputHandler.chatNotification(src, "Distant Horizons data for \"" + label + "\": "
                    + stats.files + " files, " + DhClientData.humanBytes(stats.bytes) + ".");
            ChatOutputHandler.chatNotification(src, "Folder: " + dir);
            ChatOutputHandler.chatNotification(src, "Nothing has been deleted. Run /dhpurge confirm to remove it at the next startup.");
            return Command.SINGLE_SUCCESS;
        }

        try
        {
            DhPurgePending.markPending(dir, label, stats.files, stats.bytes);
        }
        catch (IOException e)
        {
            ChatOutputHandler.chatError(src, "Could not write the purge marker: " + e.getMessage());
            LoggingHandler.sulog.warn("dhpurge failed to write marker for " + dir + ": " + e.getMessage());
            return 0;
        }

        ChatOutputHandler.chatConfirmation(src, "Queued Distant Horizons data for this server: "
                + stats.files + " files, " + DhClientData.humanBytes(stats.bytes) + ".");
        ChatOutputHandler.chatConfirmation(src, "It cannot be deleted while you are connected, because Distant Horizons keeps those files open.");
        ChatOutputHandler.chatNotification(src, "Fully close Minecraft and start it again, then rejoin. The data is wiped during startup, before Distant Horizons loads it.");
        return Command.SINGLE_SUCCESS;
    }
}
