package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.client.renderer.RenderCustomModel;
import com.goodbird.cnpcgeckoaddon.entity.EntityCustomModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.client.renderer.FighterSkins;
import net.shurui.dev.sdu.client.renderer.cnpc.SduHairLayer;
import noppes.npcs.entity.EntityNPCInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import software.bernie.geckolib.renderer.GeoEntityRenderer;
import software.bernie.geckolib.renderer.GeoRenderer;

// Attaches SduHairLayer to the CNPC-Gecko-Addon's RenderCustomModel so a GeckoLib-model Custom NPC can render
// a DMZ hairstyle from its stored hair code, AND fixes URL skins (skinType 2) showing as the purple missing
// texture.
//
// URL-skin fix, SECOND chance. The primary fix is NpcTextureUtilsMixin at loadSkin HEAD, which installs the
// texture at the addon's first resolution (before it registers a broken SimpleTexture). This getRenderType
// hook stays as belt-and-braces: GeckoLib calls it every frame with the exact texture RL the model is about
// to draw (EntityCustomModel.textureResLoc, customnpcs:skins/<hash>), so it re-checks and, thanks to the
// self-healing installUrlTexture, recovers any RL whose texture went missing (a resource reload) or that the
// loadSkin hook somehow never covered. installUrlTexture is idempotent per RL, so the per-frame call is cheap
// (only the first, or a post-reload heal, does real work).
//
// remap=false (addon class). require=0 to degrade not crash on a future addon reshape. Gated with the rest
// of sdu.cnpc.mixins.json by CnpcMixinPlugin, so both skip when the CNPC stack is absent.
@Mixin(value = RenderCustomModel.class, remap = false)
public abstract class RenderCustomModelMixin {

    @Inject(method = "<init>", at = @At("TAIL"))
    private void sdu$addHairLayer(EntityRendererProvider.Context context, CallbackInfo ci) {
        @SuppressWarnings("unchecked")
        GeoRenderer<EntityCustomModel> self = (GeoRenderer<EntityCustomModel>) (Object) this;
        ((GeoEntityRenderer<EntityCustomModel>) self).addRenderLayer(new SduHairLayer(self));
    }

    // texture arg is the RL GeckoLib resolved this frame (model's textureResLoc). For a URL skin, install a
    // real texture under that same RL so the model reads a valid image instead of an unresolved
    // customnpcs:skins/<hash> that FileNotFounds into purple. installUrlTexture is idempotent per-RL so the
    // per-frame call is cheap (only the first downloads). NPC data comes off EntityCustomModel.owner (set by
    // the addon right before this render): owner.display.skinType and owner.display.getSkinUrl().
    @Inject(
            method = "getRenderType(Lcom/goodbird/cnpcgeckoaddon/entity/EntityCustomModel;Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/renderer/MultiBufferSource;F)Lnet/minecraft/client/renderer/RenderType;",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private void sdu$installUrlSkin(EntityCustomModel animatable, ResourceLocation texture, MultiBufferSource bufferSource, float partialTick, CallbackInfoReturnable<RenderType> cir) {
        int skinType = -1;
        String url = null;
        try {
            EntityNPCInterface owner = animatable == null ? null : animatable.owner;
            if (owner != null && owner.display != null) {
                skinType = owner.display.skinType;
                url = owner.display.getSkinUrl();
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[sdu-skin] render-hook read failed: {}", t.toString());
        }
        if (skinType != 2 || url == null || texture == null) {
            return;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return;
        }
        // installUrlTexture is idempotent per-RL, so calling it every render is fine.
        FighterSkins.installUrlTexture(texture, trimmed);
    }
}
