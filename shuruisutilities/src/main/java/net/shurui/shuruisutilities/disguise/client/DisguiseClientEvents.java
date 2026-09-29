package net.shurui.shuruisutilities.disguise.client;

import net.minecraft.world.entity.player.Player;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.model.client.ModelClientCache;

/**
 * Client Forge-bus glue for the disguise / model overrides: swap a disguised player's name-tag NAME (the rank badge
 * and crown are swapped where those are drawn, via {@link DisguiseIdentity}), and clear every override cache on
 * disconnect so a disguise from one server can never bleed into the next.
 *
 * <p>Explicit {@code modid} as the multi-mod jar requires; core trees use {@code dmz_ragnarok}. Client dist only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DisguiseClientEvents
{
    private DisguiseClientEvents() {}

    // HIGH so the target name is in place before the rank badge / crown are prepended by the ranks and crowns
    // handlers (which read the target identity through DisguiseIdentity).
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRenderNameTag(RenderNameTagEvent event)
    {
        if (!(event.getEntity() instanceof Player player))
            return;
        if (!DisguiseClientCache.isDisguised(player.getUUID()))
            return;
        event.setContent(DisguiseIdentity.nameFor(player.getUUID(), event.getContent()));
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        DisguiseClientCache.reset();
        DisguiseSkins.reset();
        ModelClientCache.reset();
    }
}
