package net.shurui.shuruisutilities.client.space;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;
import net.shurui.shuruisutilities.client.saiyan.PlanetSaiyanBodyLayer;
import net.shurui.shuruisutilities.client.saiyan.PlanetSaiyanHairLayer;
import net.shurui.shuruisutilities.client.saiyan.PlanetSaiyanVanillaArmorLayer;
import net.shurui.shuruisutilities.client.saiyan.PlanetSaiyanArmorLayer;
import net.shurui.shuruisutilities.client.saiyan.PlanetSaiyanScouterLayer;

/**
 * Renderer for any SU planet saiyan drawn as a DMZ custom character, generic over the {@link SaiyanAppearance} contract
 * so ONE renderer serves the hostile garrison saiyan, the passive town citizen and the town trader. It layers, in DMZ's
 * order: the body/face/tail ({@link PlanetSaiyanBodyLayer}), the procedural hair ({@link PlanetSaiyanHairLayer}), then
 * DMZ's TWO armor layers reproduced here: the vanilla-{@code HumanoidModel} {@link PlanetSaiyanVanillaArmorLayer}
 * (head/chest/arms/leggings/boots, the whole set for a male and everything but the chest torso for a female) and the
 * inflated-race-geo {@link PlanetSaiyanArmorLayer} (the female chest torso only). Together they paint each armor piece
 * exactly once, matching the DMZPlayerArmorLayer + DMZCustomArmorLayer split DMZ registers on a player. The base model
 * itself is drawn with DMZ's blank {@code null.png}; every visible pixel comes from a layer, exactly as DMZ paints a
 * player.
 *
 * <p>The armor stack is read via {@code getItemBySlot}, which works for a mob, NOT {@code getInventory()} which is
 * player-only. A chassis that wears no armor simply draws no armor.
 */
public class PlanetSaiyanGarrisonRenderer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoEntityRenderer<T>
{
    public PlanetSaiyanGarrisonRenderer(EntityRendererProvider.Context context)
    {
        super(context, new PlanetSaiyanGarrisonModel<>());
        this.shadowRadius = 0.5F;
        addRenderLayer(new PlanetSaiyanBodyLayer<>(this));
        addRenderLayer(new PlanetSaiyanHairLayer<>(this));
        // Two armor layers, in DMZ's own order: the vanilla-HumanoidModel layer draws head/chest/arms/leggings/boots
        // (the whole set for a male, and everything but the chest torso for a female), then the inflated-body layer draws
        // the female chest torso. Neither draws the chest for the same body, so the chest is painted exactly once.
        addRenderLayer(new PlanetSaiyanVanillaArmorLayer<>(this));
        addRenderLayer(new PlanetSaiyanArmorLayer<>(this));
        // Scouter, drawn last so it sits over the face. It draws DMZ's scouter geo on the head bone for the synced scouter
        // colour; a saiyan with SCOUTER_NONE (the generated-planet garrison's default) draws none, so this shared layer
        // leaves the garrison saiyan's look unchanged while giving the town citizen and trader their random-colour scouter.
        addRenderLayer(new PlanetSaiyanScouterLayer<>(this));
    }

    /**
     * Establish a full, known visibility baseline on the shared baked model at the very start of every render, via the
     * shared {@link SaiyanFallbackRender#applyVisibilityBaseline}. See that method for why this is load bearing: the human
     * / majin_slim baked geo is SHARED AND MUTABLE, so without a per-frame baseline a bone could carry a hidden state left
     * over from whatever was drawn immediately before, making output depend on draw order.
     *
     * <p>Guarded on {@code !isReRender}: GeckoLib calls {@code preRender} again with {@code isReRender == true} for every
     * {@code reRender} a layer performs, and the layers hide bones per pass right before calling {@code reRender} (for
     * example the face pass hides every bone but the head). Re-applying the baseline then would clobber those per-pass
     * hides, so the baseline runs only on the initial pass. This garrison chassis ALWAYS draws the saiyan (it has no rgnpc
     * model of its own), so unlike the rgnpc renderers there is no fallback check to gate on here.
     */
    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model, MultiBufferSource bufferSource,
                          VertexConsumer buffer, boolean isReRender, float partialTick, int packedLight,
                          int packedOverlay, float red, float green, float blue, float alpha)
    {
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick, packedLight,
                packedOverlay, red, green, blue, alpha);
        if (!isReRender && animatable != null)
        {
            SaiyanFallbackRender.applyVisibilityBaseline(model, animatable);
        }
    }
}
