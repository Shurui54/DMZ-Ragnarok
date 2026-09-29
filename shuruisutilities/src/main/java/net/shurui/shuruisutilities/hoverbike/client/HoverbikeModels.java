package net.shurui.shuruisutilities.hoverbike.client;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

// register + retrieve the four baked hoverbike models. RegisterAdditional (in HoverbikeClientEvents) takes the
// model path relative to models/ with no .json (shuruisutilities:entity/hoverbike_<n>) and bakes it into the
// block atlas; we fetch via getModelManager().getModel(location).
// emissive: v1/3/4 ship a same-geometry alpha-keyed glow model (_emissive); v2 has none (null slot).
public final class HoverbikeModels
{
    private HoverbikeModels() {}

    // 1-4 (index 0 unused)
    public static final ResourceLocation[] LOCATION = new ResourceLocation[5];

    // glow overlays; only v1/3/4 have one, v2 stays null
    public static final ResourceLocation[] EMISSIVE_LOCATION = new ResourceLocation[5];

    static
    {
        for (int v = 1; v <= 4; v++)
            LOCATION[v] = new ResourceLocation(ShuruisUtilities.MODID, "entity/hoverbike_" + v);

        for (int v : new int[] { 1, 3, 4 })
            EMISSIVE_LOCATION[v] = new ResourceLocation(ShuruisUtilities.MODID, "entity/hoverbike_" + v + "_emissive");
    }

    // baked model for a variant, or the missing-model placeholder if not yet loaded
    public static BakedModel get(int variant)
    {
        int v = Math.max(1, Math.min(4, variant));
        return Minecraft.getInstance().getModelManager().getModel(LOCATION[v]);
    }

    // baked glow model, or null if this variant has none (v2) or the index is out of range
    public static BakedModel getEmissiveModel(int variant)
    {
        if (variant < 1 || variant > 4 || EMISSIVE_LOCATION[variant] == null)
            return null;
        return Minecraft.getInstance().getModelManager().getModel(EMISSIVE_LOCATION[variant]);
    }
}
