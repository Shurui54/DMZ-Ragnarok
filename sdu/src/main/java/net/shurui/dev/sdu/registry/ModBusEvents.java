package net.shurui.dev.sdu.registry;

import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

/** Common-side, mod-event-bus subscriptions (attribute creation, etc.). */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ModBusEvents {

    private ModBusEvents() {
    }

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event) {
        // True saga fighter: reuse DMZ's own saga attribute set so the inherited saga AI behaves normally.
        event.put(ModEntities.DMZ_FIGHTER.get(), DBSagasEntity.createAttributes().build());
        event.put(ModEntities.SHENRON.get(),
                net.shurui.dev.sdu.entity.ShenronDisplayEntity.createAttributes().build());
        event.put(ModEntities.DUKE_SNIPPERJACK.get(),
                net.shurui.dev.sdu.entity.DukeSnipperjackEntity.createAttributes().build());
        event.put(ModEntities.PUMPKIN_PUPPET.get(),
                net.shurui.dev.sdu.entity.PumpkinPuppetEntity.createAttributes().build());
        event.put(ModEntities.PILAF_MECH.get(),
                net.shurui.dev.sdu.entity.PilafMechEntity.createAttributes().build());
    }
}
