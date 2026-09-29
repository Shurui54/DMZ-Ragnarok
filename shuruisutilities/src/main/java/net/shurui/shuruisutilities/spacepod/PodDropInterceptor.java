package net.shurui.shuruisutilities.spacepod;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.init.MainItems;
import com.dragonminez.common.init.entities.SpacePodEntity;

import net.shurui.shuruisutilities.core.config.PublicContent;

// closes the one gap between our chip-return machinery and DMZ's own pod: SpacePodEntity.hurt() reacts to a PLAYER
// hit by spawning DMZ's NAVE_SAIYAN_ITEM and then removing the pod. that drop is the UNIQUE signal of a genuine
// destroy: DMZ never emits it on chunk-unload or dim-change, so catching it is the exact analogue of the
// hoverbike's remove(reason.shouldDestroy()) branch. left alone it would leak a DMZ saiyan-ship item we never want
// from an SU-summoned pod. so when a NAVE_SAIYAN_ITEM drop appears on top of one of OUR pods (one carrying the
// su_pod_owner marker), we CANCEL the DMZ drop. We do NOT return a chip: the owner's chip stays in their curios slot
// the whole time a pod is out, so a destroy just clears the record and frees them to deploy again.
//
// timing: DMZ's hurt() calls spawnAtLocation (-> addFreshEntity -> this event) BEFORE it calls remove(), so the
// pod is still in the level here for us to find. dragonminez is mandatory, but the whole body is Throwable-guarded
// because it reads DMZ 2.1.3 internals and a DMZ change must degrade, not crash entity spawning.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class PodDropInterceptor
{
    private PodDropInterceptor() {}

    @SubscribeEvent
    public static void onItemSpawn(EntityJoinLevelEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getEntity() instanceof ItemEntity drop))
            return;
        // Follows the pod's own public feature gate, so the chip-return machinery stays live exactly when the chip
        // itself can be summoned.
        if (!PublicContent.allows(PublicContent.FEATURE_SPACE_POD))
            return;

        try
        {
            Item nave = MainItems.NAVE_SAIYAN_ITEM.get();
            if (drop.getItem().getItem() != nave)
                return;

            // is this drop coming from one of OUR pods? scan a small box around the drop for an owned pod. a normal
            // player-held saiyan ship item has no owned pod under it, so it is left completely alone.
            AABB box = new AABB(drop.blockPosition()).inflate(2.0D);
            SpacePodEntity owned = null;
            for (SpacePodEntity pod : level.getEntitiesOfClass(SpacePodEntity.class, box))
            {
                if (pod.getPersistentData().hasUUID(SpacePodDeploy.OWNER_TAG))
                {
                    owned = pod;
                    break;
                }
            }
            if (owned == null)
                return;

            // It's ours. Swallow DMZ's ship-item drop so an SU-summoned pod never leaks a NAVE_SAIYAN_ITEM. We return
            // NO chip: the owner's chip never left their curios slot while the pod was out, so it is still there and a
            // destroy simply frees them to deploy again. Handing one over would DUPLICATE it.
            event.setCanceled(true);
            UUID ownerId = owned.getPersistentData().getUUID(SpacePodDeploy.OWNER_TAG);
            owned.getPersistentData().remove(SpacePodDeploy.OWNER_TAG); // pod is removed by DMZ right after this

            // If the owner is online and this pod is the one their record names, clear the record so their next toggle
            // deploys straight from the slot. An offline owner keeps the record; recall's not-found path clears it on
            // their next toggle. Either way the chip is safe in the owner's slot, so nothing is dropped.
            ServerPlayer owner = ownerId != null ? level.getServer().getPlayerList().getPlayer(ownerId) : null;
            if (owner != null && owned.getUUID().equals(PodDeployData.deployedUUID(owner)))
                PodDeployData.clear(owner);
        }
        catch (Throwable t)
        {
            // DMZ internals shifted: leave DMZ's default behavior intact rather than crash the entity spawn.
        }
    }
}
