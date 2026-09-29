package net.shurui.shuruisutilities.compat.customnpcs;

import net.shurui.shuruisutilities.api.key.AuctionHooks;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import noppes.npcs.api.event.NpcEvent;
import noppes.npcs.api.wrapper.WrapperNpcAPI;

// Right-clicking a CustomNPCs NPC that carries the auctioneer tag opens the SU auction house, routed through the
// same server-authoritative open the command, hub button and block use, through AuctionHooks (the key's AuctionServer
// re-checks eligibility,
// so there is no client trust and the permission gate is shared). On CustomNPCs' own event bus
// (WrapperNpcAPI.EVENT_BUS, NOT the Forge bus), where the cancelable InteractEvent fires just before the NPC's
// default interaction. An NPC without the tag is ignored entirely, so every other NPC behaves normally. Only
// loaded when CustomNPCs is present and its API resolved (caller guards with a Class.forName probe); the caller is the
// key's AuctionModule, so this is never initialised keyless.
public class AuctionNpcHandler
{
    // scoreboard/CustomNPCs tag that marks an NPC as an auctioneer; overridden from the module config on init
    private static String tag = "su_auctioneer";

    public static void init(String auctioneerTag)
    {
        if (auctioneerTag != null && !auctioneerTag.trim().isEmpty())
            tag = auctioneerTag.trim();
        WrapperNpcAPI.EVENT_BUS.register(new AuctionNpcHandler());
        LoggingHandler.sulog.info("[CustomNPCs] Auctioneer NPC integration enabled (tag '{}').", tag);
    }

    @SubscribeEvent
    public void onInteract(NpcEvent.InteractEvent event)
    {
        if (event.npc == null || !event.npc.hasTag(tag))
            return; // not an auctioneer: leave CustomNPCs' normal interaction alone

        Object mcEntity = event.player.getMCEntity();
        if (!(mcEntity instanceof ServerPlayer player))
            return;

        AuctionHooks.get().open(player);
        // suppress the NPC's default right-click (dialog/role) so a tagged auctioneer only ever opens the house
        event.setCanceled(true);
    }
}
