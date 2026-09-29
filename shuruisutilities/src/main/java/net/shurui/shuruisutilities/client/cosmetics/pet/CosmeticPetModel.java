package net.shurui.shuruisutilities.client.cosmetics.pet;

import net.minecraft.resources.ResourceLocation;

import software.bernie.geckolib.model.GeoModel;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntity;
import net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetType;

/**
 * Picks the geo, texture and animation for a {@link CosmeticPetEntity} from its synched pet id. The converter writes
 * all three under {@code assets/dmz_ragnarok/.../entity/cosmetic_pet/<id>.*}, so the id is the resource base name and
 * one model serves every pet.
 *
 * <h2>Why the id is clamped to a KNOWN pet</h2>
 * GeckoLib bakes from a directory LISTING once per reload and THROWS on a cache miss, on the render thread (the
 * list-vs-read asymmetry recorded in the workspace notes). Every id this returns must therefore be one whose geo
 * the client can bake (the rigs are streamed by the Ragnarok Key, see CosmeticAssetCache). {@link CosmeticPetType#known} is the guard: an unknown id falls back to the default
 * rig rather than naming a geo that was never listed, so a stale or malformed id degrades to a visible fallback pet
 * instead of crashing the client. All four bundled rigs are listed and read, and the renderer additionally skips drawing until the rig is baked (CosmeticArt), so the fallback is only ever reached by
 * a genuinely bad id.
 */
public class CosmeticPetModel extends GeoModel<CosmeticPetEntity>
{
    private static final String NS = ShuruisUtilities.MODID;

    private static String safeId(CosmeticPetEntity pet)
    {
        String id = pet.getPetId();
        return CosmeticPetType.known(id) ? id : CosmeticPetType.defaultPetId();
    }

    @Override
    public ResourceLocation getModelResource(CosmeticPetEntity pet)
    {
        return new ResourceLocation(NS, "geo/entity/cosmetic_pet/" + safeId(pet) + ".geo.json");
    }

    @Override
    public ResourceLocation getTextureResource(CosmeticPetEntity pet)
    {
        return new ResourceLocation(NS, "textures/entity/cosmetic_pet/" + safeId(pet) + ".png");
    }

    @Override
    public ResourceLocation getAnimationResource(CosmeticPetEntity pet)
    {
        return new ResourceLocation(NS, "animations/entity/cosmetic_pet/" + safeId(pet) + ".animation.json");
    }
}
