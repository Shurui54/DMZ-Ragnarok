package net.shurui.shuruisutilities.patreon.client;

import net.shurui.shuruisutilities.patreon.PatreonCrowns;

import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws {@code <crown> | } before a supporter's name above their head, from the codepoint synced into
 * {@link CrownClientCache}. Client dist only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CrownClientEvents
{
    private CrownClientEvents() {}

    // LOW, deliberately one step ahead of the rank badge's LOWEST: whatever another mod put in the name tag, the
    // crown goes in front of it, and then the rank badge goes in front of the crown. Final order above a head is
    // [rank] <crown> | Name.
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onRenderNameTag(RenderNameTagEvent event)
    {
        if (event.getResult() == Event.Result.DENY)
            return; // another mod suppressed the name tag entirely; nothing to decorate
        if (!(event.getEntity() instanceof Player player))
            return;
        // Disguise-aware: a disguised player's crown is the target's (the real crown for everyone else).
        int cp = net.shurui.shuruisutilities.disguise.client.DisguiseIdentity.crownOf(player.getUUID());
        if (cp <= 0)
            return;
        event.setContent(PatreonCrowns.decorate(cp, event.getContent()));
    }
}
