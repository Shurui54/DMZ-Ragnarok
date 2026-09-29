package net.shurui.shuruisutilities.ragnarok.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.shuruisutilities.ragnarok.RgNpcEntities;
import net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntities;

/**
 * Client-only, mod-event-bus registration of the renderers for both ragnarok NPC entities: {@link RgNpcRenderer}
 * for the display NPC, and {@link RgNpcFighterRenderer} for the combat one.
 *
 * <p>The fighter's registration is load-bearing rather than cosmetic. It is a DragonMineZ saga entity, and
 * leaving it unregistered would let DragonMineZ's own saga renderer draw it, which resolves a model from the
 * entity TYPE and would replace the chosen character with a saga one.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class RgNpcClientEvents {

    private RgNpcClientEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(RgNpcEntities.RGNPC.get(), RgNpcRenderer::new);
        event.registerEntityRenderer(RgNpcFighterEntities.RGNPC_FIGHTER.get(), RgNpcFighterRenderer::new);
    }
}
