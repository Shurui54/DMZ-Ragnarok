package net.shurui.shuruisutilities.guilds.raid.clone.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntities;

/**
 * Client-only, mod-event-bus registration of the {@link GuildRaidCloneRenderer} for the {@link
 * net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntity} driver. That renderer draws the driver as a
 * plain DragonMineZ saga humanoid ONLY while no appearance puppet exists for it (the graceful-degradation path);
 * when a puppet exists it suppresses the plain body and the puppet ({@link GuildRaidPuppetRenderHook}) draws the
 * true appearance instead.
 *
 * <p>This class only loads on the client (a dedicated server never registers renderers).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class GuildRaidCloneClientEvents
{
    private GuildRaidCloneClientEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerEntityRenderer(GuildRaidCloneEntities.GUILD_RAID_CLONE.get(), GuildRaidCloneRenderer::new);
    }
}
