package net.shurui.shuruisutilities.racing.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * R11: an OPTIONAL, guarded use of DragonMineZ's own screen post-effects for two race globals, with the R9 HUD washes
 * as the always-present fallback. When this client is being Gravity Crushed it runs DMZ's
 * {@code shaders/post/gravity_red.json}; when Solar-Flared it runs {@code shaders/post/taiyoken_flash.json}. Both ship
 * inside the mandatory DMZ jar and are plain, uniform-less vanilla post chains.
 *
 * <p>It builds its OWN {@link PostChain} on the main render target (exactly as DMZ's own shader managers do), so it
 * never touches the global {@code GameRenderer} post-effect and cannot fight a shader a player or another mod set up.
 * Everything is defensive: if a chain fails to build or process (an incompatible pipeline, a future DMZ without the
 * file, anything), that kind is marked DEAD for the session and never tried again, and the HUD wash carries it. The
 * chains are lazy, resized with the window, and freed on disconnect. Client-only.
 *
 * <p>Explicit {@code modid = "dmz_ragnarok"} as the split requires; Forge bus, client dist.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RacePostShaders
{
    private RacePostShaders() {}

    private static final ResourceLocation GRAVITY_RED =
            new ResourceLocation("dragonminez", "shaders/post/gravity_red.json");
    private static final ResourceLocation TAIYOKEN_FLASH =
            new ResourceLocation("dragonminez", "shaders/post/taiyoken_flash.json");

    private static PostChain gravityChain;
    private static PostChain blindChain;
    private static boolean gravityDead;
    private static boolean blindDead;
    private static int lastWidth = -1;
    private static int lastHeight = -1;

    /** True while our gravity chain will process this frame (the HUD red wash then stands down; the shake still runs). */
    public static boolean gravityShaderActive()
    {
        return !gravityDead && gravityChain != null && RaceClientState.gravityAlpha() > 0.001F;
    }

    /** True while our solar-flare chain will process this frame (the HUD white wash then stands down). */
    public static boolean blindShaderActive()
    {
        return !blindDead && blindChain != null && RaceClientState.blindAlpha() > 0.001F;
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event)
    {
        // Process at the very end of the level pass, so the world + entities are in the main target before the effect
        // runs and the GUI (HUD wash / results) draws on top afterwards.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
            return;
        if (!ClientGate.feature("racing") || !RaceClientState.isSessionActive())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.getMainRenderTarget() == null)
            return;

        float partial = event.getPartialTick();
        if (!gravityDead && RaceClientState.gravityAlpha() > 0.001F)
            processChain(mc, Mode.GRAVITY, partial);
        else if (!blindDead && RaceClientState.blindAlpha() > 0.001F)
            processChain(mc, Mode.BLIND, partial);
    }

    private enum Mode { GRAVITY, BLIND }

    private static void processChain(Minecraft mc, Mode mode, float partial)
    {
        try
        {
            PostChain chain = ensure(mc, mode);
            if (chain == null)
                return;
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            if (w != lastWidth || h != lastHeight)
            {
                if (gravityChain != null)
                    gravityChain.resize(w, h);
                if (blindChain != null)
                    blindChain.resize(w, h);
                lastWidth = w;
                lastHeight = h;
            }
            chain.process(partial);
            // The post chain leaves its own render target bound; restore the main one so the GUI draws correctly.
            mc.getMainRenderTarget().bindWrite(false);
        }
        catch (Throwable t)
        {
            markDead(mode);
        }
    }

    private static PostChain ensure(Minecraft mc, Mode mode) throws Exception
    {
        if (mode == Mode.GRAVITY)
        {
            if (gravityChain == null)
            {
                gravityChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                        mc.getMainRenderTarget(), GRAVITY_RED);
                gravityChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
            }
            return gravityChain;
        }
        if (blindChain == null)
        {
            blindChain = new PostChain(mc.getTextureManager(), mc.getResourceManager(),
                    mc.getMainRenderTarget(), TAIYOKEN_FLASH);
            blindChain.resize(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        }
        return blindChain;
    }

    private static void markDead(Mode mode)
    {
        if (mode == Mode.GRAVITY)
        {
            gravityDead = true;
            close(gravityChain);
            gravityChain = null;
        }
        else
        {
            blindDead = true;
            close(blindChain);
            blindChain = null;
        }
    }

    // Free both chains (disconnect / reset). Not dead-marked: a fresh server may race again fine.
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        if (Minecraft.getInstance().level == null && (gravityChain != null || blindChain != null))
            reset();
    }

    public static void reset()
    {
        close(gravityChain);
        close(blindChain);
        gravityChain = null;
        blindChain = null;
        lastWidth = -1;
        lastHeight = -1;
    }

    private static void close(PostChain chain)
    {
        if (chain == null)
            return;
        try
        {
            chain.close();
        }
        catch (Throwable ignored)
        {
        }
    }
}
