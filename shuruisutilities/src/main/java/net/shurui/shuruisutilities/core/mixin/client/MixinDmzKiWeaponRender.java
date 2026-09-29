package net.shurui.shuruisutilities.core.mixin.client;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.god.RagnarokKiWeapon;

/**
 * Draws our summoned weapons as the painted objects they are, instead of as energy.
 *
 * <h2>Why the artwork never showed</h2>
 * DragonMineZ's three ki weapons are 64x64 files of 99 bytes: blank. They are meant to be glowing constructs, so
 * {@code KiWeaponRenderer} draws every ki weapon through {@code ModRenderTypes.energy2} and multiplies it by a ki
 * colour resolved per weapon type. On a blank white texture that is exactly right, and it is why theirs look the way
 * they do.
 *
 * <p>Ours are drawn weapons with real textures, and an additive glow multiplied by a form colour flattens that into
 * a coloured shape no matter what the texture or the UVs say. That is the whole reason the keyblades kept coming out
 * looking like energy however many times their coordinates were corrected: none of that work was ever visible
 * through this. Not a UV problem, and never was.
 *
 * <p>So for OUR types only, and left completely alone for DMZ's, the render type becomes an ordinary cutout so the
 * texture is drawn as painted rather than added as light. The tint is handled separately, in
 * {@code MixinDmzKiWeaponColour}.
 *
 * <p>Decided from the TEXTURE path rather than from a weapon type argument, because that is what this call site
 * actually has: the path always ends in {@code kiweapon_<type>.png}, which names the weapon.
 */
@Mixin(targets = "com.dragonminez.client.render.effects.KiWeaponRenderer", remap = false)
public class MixinDmzKiWeaponRender
{
    @Redirect(
            method = "lambda$processWeapons$1",
            at = @At(value = "INVOKE",
                     target = "Lcom/dragonminez/client/render/util/ModRenderTypes;energy2(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/client/renderer/RenderType;"),
            require = 0, remap = false)
    private static RenderType su$paintedRatherThanGlowing(ResourceLocation texture)
    {
        try
        {
            if (su$isOurs(texture))
                return RenderType.entityCutoutNoCull(texture);
        }
        catch (Throwable ignored)
        {
            // Fall through to DMZ's own render type; a wrong-looking weapon beats a crash in the render loop.
        }
        return com.dragonminez.client.render.util.ModRenderTypes.energy2(texture);
    }

    /**
     * True when this texture path belongs to one of ours.
     *
     * <p>PRIVATE, and it has to be: a mixin may not carry a non-private static method, and this one was
     * package-private, which failed the whole mixin at APPLY and took the client down with it. {@code require = 0}
     * does not help, since that governs injection and this is the applicator refusing the class outright.
     */
    private static boolean su$isOurs(ResourceLocation texture)
    {
        if (texture == null)
            return false;
        String path = texture.getPath().toLowerCase(Locale.ROOT);
        for (RagnarokKiWeapon weapon : RagnarokKiWeapon.values())
        {
            if (path.endsWith("kiweapon_" + weapon.type.toLowerCase(Locale.ROOT) + ".png"))
                return true;
        }
        return false;
    }
}
