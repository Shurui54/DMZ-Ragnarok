package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.runes.client.RuneGlintTypes;

/**
 * Gives a graded rune its own coloured enchantment sheen instead of the stock purple one.
 *
 * <p>Vanilla picks the glint buffer inside {@code ItemRenderer.render}, from a static helper that is handed the
 * buffer source and the render type but never the stack, so there is nothing to hook on the helper itself. The two
 * calls are redirected here instead, where the stack the enclosing method is drawing is still in scope.
 *
 * <p>Anything that is not a graded rune, which is every other item in the game, falls straight back to the vanilla
 * helper, so this changes nothing else on screen. Greater runes fall back too: their sheen IS the stock one.
 *
 * <p>The buffers are fetched in vanilla's order, sheen first and item second, because that ordering is what makes
 * the buffer source flush them the right way round. See {@code MixinRenderBuffersRuneGlint}.
 */
@Mixin(ItemRenderer.class)
public class MixinItemRendererRuneGlint
{
    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBufferDirect"
                            + "(Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/renderer/RenderType;ZZ)"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer su$runeFoilDirect(MultiBufferSource source, RenderType type, boolean noEntity,
            boolean foil, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, BakedModel model)
    {
        ResourceLocation glint = foil ? RuneGlintTypes.textureFor(stack) : null;
        if (glint == null)
            return ItemRenderer.getFoilBufferDirect(source, type, noEntity, foil);
        return VertexMultiConsumer.create(source.getBuffer(RuneGlintTypes.glintDirect(glint)), source.getBuffer(type));
    }

    @Redirect(
            method = "render",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;getFoilBuffer"
                            + "(Lnet/minecraft/client/renderer/MultiBufferSource;"
                            + "Lnet/minecraft/client/renderer/RenderType;ZZ)"
                            + "Lcom/mojang/blaze3d/vertex/VertexConsumer;"))
    private VertexConsumer su$runeFoil(MultiBufferSource source, RenderType type, boolean noEntity,
            boolean foil, ItemStack stack, ItemDisplayContext context, boolean leftHand, PoseStack pose,
            MultiBufferSource buffer, int light, int overlay, BakedModel model)
    {
        ResourceLocation glint = foil ? RuneGlintTypes.textureFor(stack) : null;
        if (glint == null)
            return ItemRenderer.getFoilBuffer(source, type, noEntity, foil);
        // Mirrors vanilla's own branch: fabulous graphics routes the translucent item sheet through a sheen that
        // writes to the item-entity target, and using the ordinary one there would draw the glint into the wrong
        // framebuffer.
        RenderType sheen = Minecraft.useShaderTransparency() && type == Sheets.translucentItemSheet()
                ? RuneGlintTypes.glintTranslucent(glint)
                : RuneGlintTypes.glint(glint);
        return VertexMultiConsumer.create(source.getBuffer(sheen), source.getBuffer(type));
    }
}
