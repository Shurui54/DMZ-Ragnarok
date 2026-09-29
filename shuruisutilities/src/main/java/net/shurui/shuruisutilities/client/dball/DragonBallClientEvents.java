package net.shurui.shuruisutilities.client.dball;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLLoadCompleteEvent;

import com.dragonminez.common.init.MainBlockEntities;
import com.dragonminez.common.init.block.custom.DragonBallType;
import com.dragonminez.common.init.block.entity.DragonBallBlockEntity;


/**
 * Re-binds DMZ's dragon ball block entity type to {@link SuDragonBallBlockRenderer} on the client, so Shurui's sets
 * draw as translucent glass spheres while DMZ's earth and namek keep their own look.
 *
 * <p>WHY re-binding at {@code RegisterRenderers} was not enough (proven root cause). DMZ does NOT register its dragon
 * ball renderer in {@code EntityRenderersEvent.RegisterRenderers}. It registers it via
 * {@code BlockEntityRenderers.register} inside a {@code FMLClientSetupEvent.enqueueWork} task (decompiled:
 * {@code com.dragonminez.client.events.ModClientEvents.lambda$onClientSetup$2}). Client-setup deferred work drains
 * AFTER {@code RegisterRenderers} fires, so DMZ's provider is put into the provider map LAST, and the block entity
 * render dispatcher, which snapshots that provider map at the first resource reload apply (later still), builds DMZ's
 * renderer. So a plain {@code RegisterRenderers} put loses even though Shurui's Utilities depends on and loads after
 * dragonminez: the deciding factor is the EVENT ordering (deferred client-setup work vs RegisterRenderers), not the
 * mod load order.
 *
 * <p>THE FIX has two layers. {@link #reassertAtLoadComplete} re-registers this renderer at {@code FMLLoadCompleteEvent},
 * which runs after ALL setup and its deferred work but before the first dispatcher snapshot, so ours is the last
 * provider and every reload-driven rebuild (initial and future F3+T) builds ours. {@link SelfHeal} is a guaranteed
 * belt-and-suspenders: on the first client tick, which is unambiguously after all registration and after the dispatcher
 * has been built, it asks the live dispatcher which renderer it actually holds for {@code dragonminez:dragon_ball}; if
 * that is still not ours (a machine where the snapshot beat load-complete), it forces the dispatcher to rebuild from the
 * provider map (where we are now last) so this session shows ours without waiting for a manual reload.
 *
 * <p>dragonminez is mandatory, so referencing {@code MainBlockEntities} / the ball classes directly is allowed (no
 * optional-dependency guard needed); they are only ever touched here, on the client.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class DragonBallClientEvents
{
    private DragonBallClientEvents()
    {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event)
    {
        event.registerBlockEntityRenderer(MainBlockEntities.DRAGON_BALL_BLOCK_ENTITY.get(),
                SuDragonBallBlockRenderer::new);
    }

    /**
     * Re-register as the LAST provider for the dragon ball type, after DMZ's deferred client-setup registration. This is
     * a plain map put ({@code BlockEntityRenderers.register}, verified as {@code PROVIDERS.put} with no duplicate guard),
     * so last wins; run here at load complete it is guaranteed to be that last put before the dispatcher snapshots.
     */
    @SubscribeEvent
    public static void reassertAtLoadComplete(FMLLoadCompleteEvent event)
    {
        BlockEntityRenderers.register(MainBlockEntities.DRAGON_BALL_BLOCK_ENTITY.get(), SuDragonBallBlockRenderer::new);
    }

    /**
     * First-client-tick self-heal and proof. Guaranteed-after point: by the first tick everything is registered and the
     * block entity render dispatcher has been built. We probe the live dispatcher for the renderer it holds for the
     * dragon ball type; if it is not ours, we force a rebuild from the provider map (where we are now the last provider).
     */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class SelfHeal
    {
        private static boolean done = false;

        private SelfHeal()
        {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event)
        {
            if (done || event.phase != TickEvent.Phase.END)
            {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            BlockEntityRenderDispatcher dispatcher = mc.getBlockEntityRenderDispatcher();
            if (dispatcher == null)
            {
                return;
            }
            done = true;
            try
            {
                // a throwaway block entity purely to look up the renderer: getRenderer keys on getType(), so the state
                // and set id are irrelevant. No level, no world side effects.
                DragonBallBlockEntity probe = new DragonBallBlockEntity(BlockPos.ZERO,
                        Blocks.AIR.defaultBlockState(), DragonBallType.ONE_STAR, "earth");
                BlockEntityRenderer<DragonBallBlockEntity> held = dispatcher.getRenderer(probe);
                if (!(held instanceof SuDragonBallBlockRenderer))
                {
                    // DMZ won the snapshot on this machine: re-register as the last provider and force the dispatcher to
                    // rebuild from the provider map, so this session shows ours without waiting for a manual reload.
                    BlockEntityRenderers.register(MainBlockEntities.DRAGON_BALL_BLOCK_ENTITY.get(),
                            SuDragonBallBlockRenderer::new);
                    dispatcher.onResourceManagerReload(mc.getResourceManager());
                }
            }
            catch (Throwable t)
            {
                // best-effort self-heal: a probe failure must never crash the client tick.
            }
        }
    }
}
