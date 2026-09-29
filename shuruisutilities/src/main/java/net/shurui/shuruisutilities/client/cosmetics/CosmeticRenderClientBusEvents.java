package net.shurui.shuruisutilities.client.cosmetics;

import java.util.Map;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Wires the worn-cosmetic renderer into the client at mod-bus time: it registers the worn-variant models for
 * baking and adds {@link WardrobeCosmeticLayer} to the vanilla player renderers.
 *
 * <h2>Why the layer goes on the VANILLA player renderer</h2>
 * DragonMineZ draws players with its own geo renderer, but its {@code DMZThirdPartyLayerForwarder} reads the layer
 * list off the vanilla {@code PlayerRenderer} in the skin map and forwards each non-vanilla layer onto the geo
 * body. So the supported way to draw on a DMZ player is to add the layer HERE, to the vanilla renderer, exactly as
 * if DMZ were not installed. See {@link WardrobeCosmeticLayer} for the forwarder's two constraints (the class-name
 * filter and the Oozaru gap), both already satisfied.
 *
 * <h2>Why the worn models must be registered</h2>
 * A model is only baked if an item points at it or {@code ModelEvent.RegisterAdditional} names it. The worn
 * variants under {@code models/item/cosmetic_worn/} back no item (the item keeps its inventory model), so without
 * this they would never bake and the renderer's worn lookup would always miss. They are keyed in the baking result
 * by the plain {@link ResourceLocation} given here, which is the same location the layer looks them up by. The set
 * is discovered from the resource pack rather than hardcoded, so importing another worn variant later is a file
 * drop with no code change. Fail soft: a discovery error registers nothing and logs one line.
 *
 * <p>The annotation is BARE of a modid on purpose. The five original addons are one jar now, so naming a modid on
 * an {@code EventBusSubscriber} is a chance to name the wrong one, and a wrong modid fails silently.
 *
 * <p>Client only, mod bus.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CosmeticRenderClientBusEvents
{
    private static final String WORN_DIR = "models/item/cosmetic_worn";

    private CosmeticRenderClientBusEvents()
    {
    }

    /** Register every {@code cosmetic_worn/*.json} present in the jar/pack for baking. */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event)
    {
        try
        {
            ResourceManager rm = net.minecraft.client.Minecraft.getInstance().getResourceManager();
            Map<ResourceLocation, ?> found = rm.listResources(WORN_DIR,
                    rl -> ShuruisUtilities.MODID.equals(rl.getNamespace()) && rl.getPath().endsWith(".json"));
            int n = 0;
            for (ResourceLocation res : found.keySet())
            {
                // res is dmz_ragnarok:models/item/cosmetic_worn/<id>.json; the baked-model key drops the
                // models/ prefix and the .json suffix, i.e. dmz_ragnarok:item/cosmetic_worn/<id>.
                String path = res.getPath();
                String modelPath = path.substring("models/".length(), path.length() - ".json".length());
                event.register(new ResourceLocation(res.getNamespace(), modelPath));
                n++;
            }
            LoggingHandler.sulog.info("[Cosmetics] registered {} worn cosmetic model(s) for baking", n);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Cosmetics] worn cosmetic model discovery failed; worn variants disabled: {}",
                    t.toString());
        }
    }

    /** Add the worn-cosmetic layer to each vanilla player renderer (default and slim skins). */
    @SubscribeEvent
    public static void onAddLayers(EntityRenderersEvent.AddLayers event)
    {
        for (String skin : event.getSkins())
        {
            EntityRenderer<?> renderer = event.getSkin(skin);
            if (renderer instanceof PlayerRenderer player)
                player.addLayer(new WardrobeCosmeticLayer(player));
        }
    }
}
