package net.shurui.shuruisutilities.shard;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Warns when a player's local playerdata file is far larger than a character can legitimately be.
 *
 * <p>Vanilla parses {@code playerdata/<uuid>.dat} on the server thread while the player logs in, so a bloated file
 * freezes the whole shard for every login and hop that player makes, and everybody else joining in that window times
 * out. On 2026-09-14 an Iron Furnaces duplication bug had grown one file to 23 MB and froze OW2 for 8 to 9 seconds
 * per event. {@code MixinIronFurnacesList} fixes that cause, but this watch does not depend on it: it looks only at the
 * file size, so a regrowth from any mod (or from that mixin silently no longer applying after an Iron Furnaces update)
 * shows up in the log long before it can freeze anything.
 *
 * <p>A normal file is well under 100 KB. The check is one filesystem stat per login, run after the login completes.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class PlayerDataSizeWatch
{
    private PlayerDataSizeWatch() {}

    /** Above this, the file is worth a warning. */
    private static final long WARN_BYTES = 500_000L;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLoggedIn(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        try
        {
            MinecraftServer server = player.getServer();
            if (server == null)
                return;
            Path file = server.getWorldPath(LevelResource.PLAYER_DATA_DIR).resolve(player.getStringUUID() + ".dat");
            if (!Files.exists(file))
                return;
            long size = Files.size(file);
            if (size > WARN_BYTES)
            {
                LoggingHandler.sulog.warn("[playerdata] {}'s playerdata file is {} KB (normal is under 100 KB). A file "
                        + "this size freezes the server while it loads; check it for a runaway list (Iron Furnaces "
                        + "furnaces_list was the cause on 2026-09-14).",
                        player.getGameProfile().getName(), size / 1024L);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[playerdata] Size check failed for {}: {}",
                    player.getGameProfile().getName(), t.toString());
        }
    }
}
