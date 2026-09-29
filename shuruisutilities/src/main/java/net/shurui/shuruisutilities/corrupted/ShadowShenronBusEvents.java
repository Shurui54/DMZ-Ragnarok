package net.shurui.shuruisutilities.corrupted;

import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;


// common-side, mod-event-bus subscription: registers the shadow shenron prop's attributes. it extends Mob, so it
// needs an attribute set or the game asserts on spawn.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ShadowShenronBusEvents
{
    private ShadowShenronBusEvents() {}

    @SubscribeEvent
    public static void createAttributes(EntityAttributeCreationEvent event)
    {
        event.put(ShadowShenronEntities.SHADOW_SHENRON.get(), ShadowShenronEntity.createAttributes().build());
    }
}
