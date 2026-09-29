package net.shurui.shuruisutilities.hoverbike;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


// cleans up a summoned vehicle on death. the item itself is never at risk: it stays in the "hoverbike" curios slot
// the whole time a vehicle is out, so on death Curios either keeps it (keepInventory) or drops it as an ordinary
// death drop the player recovers. what respawn has to fix is the ENTITY: SU's RespawnHandler can teleport the player
// to another dim and strand the deployed vehicle there. so on respawn we find it across all loaded levels, discard
// it, and clear the DeployData record so the next toggle deploys fresh. we deliberately restore NO item here, since
// doing so would duplicate the chip the player still holds.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class HoverbikeDeathHandler
{
    private HoverbikeDeathHandler() {}

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event)
    {
        if (event.isEndConquered())
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        PacketHoverbikeToggle.despawnDeployedOnRespawn(player);
        // the space pod shares this slot/keybind, so its deployed-on-death despawn rides the same respawn hook.
        // no-op unless a pod is actually deployed; the two records are mutually exclusive (shared slot).
        net.shurui.shuruisutilities.spacepod.SpacePodDeploy.despawnDeployedOnRespawn(player);
        // the nimbus also shares this slot/keybind, so its deployed-on-death despawn rides the same respawn hook.
        // no-op unless a nimbus is actually deployed; the three vehicle records are mutually exclusive (shared slot).
        net.shurui.shuruisutilities.nimbus.NimbusDeploy.despawnDeployedOnRespawn(player);
        // the time machine shares this slot/keybind too, so its deployed-on-death despawn rides the same respawn hook.
        // no-op unless a machine is actually deployed; all four vehicle records are mutually exclusive (shared slot).
        net.shurui.shuruisutilities.timemachine.TimeMachineDeploy.despawnDeployedOnRespawn(player);
    }
}
