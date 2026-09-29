package net.shurui.shuruisutilities.nimbus;

import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.dragonminez.common.init.MainItems;
import com.dragonminez.common.init.entities.BlackNimbusEntity;
import com.dragonminez.common.init.entities.FlyingNimbusEntity;

import net.shurui.shuruisutilities.core.config.PublicContent;

// closes the one gap between our chip-return machinery and DMZ's own nimbus: both nimbus entities react to a PLAYER
// hit in hurt() by spawning DMZ's nimbus item and then removing the entity. the flying nimbus drops NUBE_ITEM, the
// black nimbus drops NUBE_NEGRA_ITEM. that drop is the UNIQUE signal of a genuine destroy: DMZ never emits it on
// chunk-unload or dim-change, so catching it is the exact analogue of the hoverbike's remove(shouldDestroy()) branch.
// left alone it would leak a DMZ nimbus item we never want from an SU-summoned nimbus. so when either nimbus drop
// appears on top of one of OUR nimbus (one carrying the su_nimbus_owner marker), we CANCEL the DMZ drop. We do NOT
// return a chip: the owner's chip stays in their curios slot the whole time a nimbus is out, so a destroy just clears
// the record and frees them to deploy again.
//
// timing: DMZ's hurt() calls spawnAtLocation (-> addFreshEntity -> this event) BEFORE it calls remove(), so the
// nimbus is still in the level here for us to find. dragonminez is mandatory, but the whole body is Throwable-guarded
// because it reads DMZ 2.1.3 internals and a DMZ change must degrade, not crash entity spawning.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class NimbusDropInterceptor
{
    private NimbusDropInterceptor() {}

    @SubscribeEvent
    public static void onItemSpawn(EntityJoinLevelEvent event)
    {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        if (!(event.getEntity() instanceof ItemEntity drop))
            return;
        // Follows the nimbus's own public feature gate, so the chip-return machinery stays live exactly when the
        // chip itself can be summoned.
        if (!PublicContent.allows(PublicContent.FEATURE_NIMBUS))
            return;

        try
        {
            Item dropped = drop.getItem().getItem();
            Item flyingItem = MainItems.NUBE_ITEM.get();
            Item blackItem = MainItems.NUBE_NEGRA_ITEM.get();
            if (dropped != flyingItem && dropped != blackItem)
                return;

            // is this drop coming from one of OUR nimbus? scan a small box around the drop for an owned nimbus of
            // either DMZ class. a normal player-held nimbus item has no owned nimbus under it, so it is left alone.
            AABB box = new AABB(drop.blockPosition()).inflate(2.0D);
            Mob owned = findOwnedNimbus(level, box);
            if (owned == null)
                return;

            // It's ours. Swallow DMZ's nimbus-item drop so an SU-summoned nimbus never leaks a DMZ nimbus item. We
            // return NO chip: the owner's chip never left their curios slot while the nimbus was out, so it is still
            // there and a destroy simply frees them to deploy again. Handing one over would DUPLICATE it.
            event.setCanceled(true);
            UUID ownerId = owned.getPersistentData().getUUID(NimbusDeploy.OWNER_TAG);
            owned.getPersistentData().remove(NimbusDeploy.OWNER_TAG); // nimbus is removed by DMZ right after this

            // If the owner is online and this nimbus is the one their record names, clear the record so their next
            // toggle deploys straight from the slot. An offline owner keeps the record; recall's not-found path clears
            // it on their next toggle. Either way the chip is safe in the owner's slot, so nothing is dropped.
            ServerPlayer owner = ownerId != null ? level.getServer().getPlayerList().getPlayer(ownerId) : null;
            if (owner != null && owned.getUUID().equals(NimbusDeployData.deployedUUID(owner)))
                NimbusDeployData.clear(owner);
        }
        catch (Throwable t)
        {
            // DMZ internals shifted: leave DMZ's default behavior intact rather than crash the entity spawn.
        }
    }

    // first owned nimbus (flying or black) in the box carrying our owner marker, else null
    private static Mob findOwnedNimbus(ServerLevel level, AABB box)
    {
        for (FlyingNimbusEntity nimbus : level.getEntitiesOfClass(FlyingNimbusEntity.class, box))
        {
            if (nimbus.getPersistentData().hasUUID(NimbusDeploy.OWNER_TAG))
                return nimbus;
        }
        for (BlackNimbusEntity nimbus : level.getEntitiesOfClass(BlackNimbusEntity.class, box))
        {
            if (nimbus.getPersistentData().hasUUID(NimbusDeploy.OWNER_TAG))
                return nimbus;
        }
        return null;
    }
}
