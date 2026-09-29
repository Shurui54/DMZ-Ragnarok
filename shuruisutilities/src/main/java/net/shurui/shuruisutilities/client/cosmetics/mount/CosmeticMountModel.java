package net.shurui.shuruisutilities.client.cosmetics.mount;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntity;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountType;

/**
 * Picks the geo, texture and animation for a {@link CosmeticMountEntity} from its synched mount id. The converter
 * writes all three under {@code assets/dmz_ragnarok/.../entity/cosmetic_mount/<id>.*}, so the id is the resource
 * base name and one model serves every mount.
 *
 * <h2>Why the id is clamped to a KNOWN mount</h2>
 * GeckoLib bakes from a directory LISTING once per reload and THROWS on a cache miss, on the render thread (the
 * list-vs-read asymmetry recorded in the workspace notes). Every id this returns must therefore be one whose geo
 * the client can bake (the rigs are streamed by the Ragnarok Key, see CosmeticAssetCache). {@link CosmeticMountType#known} is the guard: an unknown id falls back to the
 * default rig rather than naming a geo that was never listed, so a stale or malformed id degrades to a visible
 * fallback mount instead of crashing the client. All seven bundled rigs are listed and read, and the renderer additionally skips drawing until the rig is baked (CosmeticArt), so the fallback is
 * only ever reached by a genuinely bad id.
 */
public class CosmeticMountModel extends GeoModel<CosmeticMountEntity>
{
    private static final String NS = ShuruisUtilities.MODID;

    private static String safeId(CosmeticMountEntity mount)
    {
        String id = mount.getMountId();
        return CosmeticMountType.known(id) ? id : CosmeticMountType.defaultMountId();
    }

    @Override
    public ResourceLocation getModelResource(CosmeticMountEntity mount)
    {
        return new ResourceLocation(NS, "geo/entity/cosmetic_mount/" + safeId(mount) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CosmeticMountEntity mount)
    {
        return new ResourceLocation(NS, "textures/entity/cosmetic_mount/" + safeId(mount) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CosmeticMountEntity mount)
    {
        return new ResourceLocation(NS, "animations/entity/cosmetic_mount/" + safeId(mount) + ".animation.json");
    }
}
