package net.shurui.shuruisutilities.ranks.client;

import net.shurui.shuruisutilities.ranks.RankManager;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the rank badge before a player's name above their head, using the codepoint synced into
 * {@link RankClientCache}. Client dist only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RankClientEvents
{
    private RankClientEvents() {}

    // LOWEST so we run after any other mod that rewrites the player's name tag (e.g. Simple Voice Chat's speaker
    // icon, Tinkers): we prepend the rank badge to whatever content they ended up with, so it isn't clobbered.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderNameTag(RenderNameTagEvent event)
    {
        if (event.getResult() == net.minecraftforge.eventbus.api.Event.Result.DENY)
            return; // another mod suppressed the name tag entirely; nothing to badge
        if (!(event.getEntity() instanceof Player player))
            return;
        // Disguise-aware: a disguised player's badge is the target's (the real rank for everyone else).
        RankManager.Rank rank = net.shurui.shuruisutilities.disguise.client.DisguiseIdentity.rankOf(player.getUUID());
        if (rank == null)
            return;
        // Drawn every render frame, so an animated rank cycles frames above the player's head.
        event.setContent(RankManager.withAnimatedBadge(rank, System.currentTimeMillis(), event.getContent()));
    }
}
