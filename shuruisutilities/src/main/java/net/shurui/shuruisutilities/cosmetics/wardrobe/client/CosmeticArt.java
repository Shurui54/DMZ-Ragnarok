package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import software.bernie.geckolib.cache.GeckoLibCache;

/**
 * "Is this cosmetic's art on this client yet?", asked before drawing, because the art of every non-Patreon cosmetic is
 * streamed by the Ragnarok Key ({@link CosmeticAssetCache}) and a cosmetic can be SEEN before its pack has arrived
 * (a first join, a keyless server, or an offer the server held back under its cap).
 *
 * <p>Two failure modes, two answers. An item whose model is absent draws the magenta missing-model cube, so the worn
 * layer draws nothing and a wardrobe tile shows the cosmetic's name instead. A GeckoLib rig whose geo or animation was
 * never baked makes GeckoLib THROW on the render thread, so pets and mounts skip rendering until the pack reloads.
 */
public final class CosmeticArt
{
    private CosmeticArt() {}

    /** Whether this item has a real baked model (not the missing model). */
    public static boolean hasItemModel(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return false;
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getItemRenderer().getItemModelShaper().getItemModel(stack);
        return model != null && model != mc.getModelManager().getMissingModel();
    }

    /** Whether both the geo and the animation file of a GeckoLib rig are baked, so rendering it cannot throw. */
    public static boolean rigBaked(ResourceLocation geo, ResourceLocation animation)
    {
        return GeckoLibCache.getBakedModels().containsKey(geo)
                && (animation == null || GeckoLibCache.getBakedAnimations().containsKey(animation));
    }
}
