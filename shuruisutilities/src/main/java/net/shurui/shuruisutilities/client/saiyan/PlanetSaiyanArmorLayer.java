package net.shurui.shuruisutilities.client.saiyan;

import java.util.ArrayList;
import java.util.List;

import com.dragonminez.client.util.ArmorTextureResolver;
import com.dragonminez.common.init.armor.DbzArmorItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * SU-owned reimplementation of DragonMineZ's custom-character armor rendering ({@code DMZCustomArmorLayer}'s DBZ-armor
 * path) for any planet saiyan. DMZ's own layer is hard-typed to {@code AbstractClientPlayer} and reads the player-only
 * {@code StatsCapability}, so it cannot be reused for a mob; this reproduces exactly the branch that matters for a
 * saiyan wearing DragonMineZ armor.
 *
 * <h2>Why this exists instead of GeckoLib's stock {@code ItemArmorGeoLayer}</h2>
 * DragonMineZ armor is NOT a vanilla-shaped {@code HumanoidModel} armor. DMZ draws it by re-rendering the RACE geo's own
 * body bone INFLATED and textured with the armor's layer texture, so the armor takes the exact shape of the body it sits
 * on. That is the whole reason a female saiyan's armor fits: the female body geo ({@code majin_slim}) carries a chest
 * ({@code boobas}) bone, and DMZ scales it up during the armor pass so the armor bulges over the chest instead of the
 * chest clipping through a flat vanilla armor mesh. The stock {@code ItemArmorGeoLayer} maps the armor to a fixed
 * vanilla {@code HumanoidModel}, which has neither the saiyan shape nor any chest, which is why females clipped through
 * it. This layer restores DMZ's real mechanism.
 *
 * <h2>What it draws, and when</h2>
 * The chest TORSO only, and only for a FEMALE saiyan, exactly as DMZ splits the work: DMZCustomArmorLayer draws the
 * inflated chest only for the bodies its {@code resolveArmorContext} keeps (for a stock saiyan that is the female case),
 * and DMZPlayerArmorLayer draws the vanilla-model chest for everyone else. So a male saiyan's chest comes from
 * {@link PlanetSaiyanVanillaArmorLayer}, not here, and this layer returns early for a male. For a female it paints the
 * equipped {@code CHEST} item's layer1 texture onto the inflated {@code body} bone (with
 * {@code armorBody}/{@code armorLeggingsBody}/{@code body_layer} and the tail hidden for the pass, and the {@code boobas}
 * chest bone scaled up so the armor bulges over the female chest). The female's arms, leggings and boots are drawn by
 * {@link PlanetSaiyanVanillaArmorLayer}; only her chest torso is drawn here. Wrapped so any DMZ API drift degrades to
 * "no armor" and logs once rather than crashing the render thread. This class is under {@code client/} and must never be
 * referenced from common code.
 */
public class PlanetSaiyanArmorLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoRenderLayer<T>
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean WARNED = false;

    // DMZ's DBZ-armor inflation for the body pass, and the chest-bone scale it uses so the armor covers a female chest.
    private static final float BODY_INFLATION = 1.035F;
    private static final float BOOBAS_SCALE_X = 1.03F;
    private static final float BOOBAS_SCALE_Y = 1.06F;
    private static final float BOOBAS_SCALE_Z = 1.15F;

    // bones hidden during the armor pass so the body texture / the armor's own uv bones do not double-paint over it, and
    // the tail (a child of body) does not render tinted with the armor texture. Mirrors DMZ's DBZ-armor branch.
    private static final String[] HIDE_DURING_ARMOR = {"armorBody", "armorLeggingsBody", "body_layer"};
    // The armor pass re-renders the whole body subtree, so it must exclude BOTH tail chains. setHidden only hides a bone's
    // own cubes (children still render), so every segment of the majin straight-tail chain (tail1m..tail6m, present only on
    // the female majin_slim geo) is listed explicitly; otherwise the armor texture would paint that chain and the straight
    // frost-demon-looking tail would still show. tail1 covers the saiyan tail root as before.
    private static final String[] EXCLUDE_TAIL =
            {"tail1", "tail1m", "tail2m", "tail3m", "tail4m", "tail5m", "tail6m"};

    public PlanetSaiyanArmorLayer(GeoRenderer<T> renderer)
    {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay)
    {
        if (animatable == null || !bone.getName().contentEquals("body"))
        {
            return;
        }
        // Draw the inflated chest ONLY for a female saiyan, mirroring DMZCustomArmorLayer.resolveArmorContext: for a stock
        // saiyan its only shouldRender branch is SLIM_SUPPORTED_MODELS.contains("saiyan") && gender.equals("female"), so a
        // normal male returns shouldRender=false and never draws the inflated chest. A male's chest torso is drawn by the
        // vanilla-model PlanetSaiyanVanillaArmorLayer instead. This gate, together with that layer returning null on the
        // armorBody bone for a female, is what guarantees the chest is painted exactly once for either gender.
        if (animatable.isMale())
        {
            return;
        }
        ItemStack chest = animatable.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.isEmpty() || !(chest.getItem() instanceof ArmorItem))
        {
            return;
        }
        try
        {
            ResourceLocation texture = resolveTexture(chest);
            if (texture == null)
            {
                return;
            }
            renderChestInflated(poseStack, animatable, bone, bufferSource, texture, partialTick, packedLight);
        }
        catch (Throwable t)
        {
            if (!WARNED)
            {
                WARNED = true;
                LOGGER.warn("[garrison-saiyan] DragonMineZ armor API drift; planet saiyans render without armor", t);
            }
        }
    }

    // resolve the equipped chest item's layer1 texture through DMZ's own resolver, so every set's real armor art is used.
    private static ResourceLocation resolveTexture(ItemStack chest)
    {
        if (chest.getItem() instanceof DbzArmorItem dbz)
        {
            return ArmorTextureResolver.resolve(dbz.getModId(), dbz.getItemId(), EquipmentSlot.CHEST, chest);
        }
        return null;
    }

    // paint the chestplate: re-render the body bone inflated and textured with the armor layer1, with the female chest
    // bone scaled up so the armor bulges over it. Every hidden/scaled bone is restored in a finally so a thrown render
    // never leaves the shared baked model in a hidden state on the next entity.
    private void renderChestInflated(PoseStack poseStack, T animatable, GeoBone body, MultiBufferSource bufferSource,
                                     ResourceLocation texture, float partialTick, int packedLight)
    {
        List<GeoBone> hidden = new ArrayList<>();
        GeoBone boobas = findDeep(body, "boobas");
        float savedBoobasX = 1.0F;
        float savedBoobasY = 1.0F;
        float savedBoobasZ = 1.0F;
        for (String name : HIDE_DURING_ARMOR)
        {
            GeoBone child = getChild(body, name);
            if (child != null && !child.isHidden())
            {
                child.setHidden(true);
                hidden.add(child);
            }
        }
        List<GeoBone> excludedTails = new ArrayList<>();
        for (String name : EXCLUDE_TAIL)
        {
            GeoBone tail = getChild(body, name);
            if (tail != null && !tail.isHidden())
            {
                tail.setHidden(true);
                excludedTails.add(tail);
            }
        }
        if (boobas != null)
        {
            savedBoobasX = boobas.getScaleX();
            savedBoobasY = boobas.getScaleY();
            savedBoobasZ = boobas.getScaleZ();
            boobas.setScaleX(savedBoobasX * BOOBAS_SCALE_X);
            boobas.setScaleY(savedBoobasY * BOOBAS_SCALE_Y);
            boobas.setScaleZ(savedBoobasZ * BOOBAS_SCALE_Z);
        }

        float rotX = body.getRotX();
        float rotY = body.getRotY();
        float rotZ = body.getRotZ();
        float posX = body.getPosX();
        float posY = body.getPosY();
        float posZ = body.getPosZ();
        float scaleX = body.getScaleX();
        float scaleY = body.getScaleY();
        float scaleZ = body.getScaleZ();
        try
        {
            // the pose stack is already at the body bone's animated position (renderForBone gives it transformed), so
            // zero the bone's own transform and drive only the inflation scale, exactly as DMZ's renderRootBoneInflated.
            body.setRotX(0.0F);
            body.setRotY(0.0F);
            body.setRotZ(0.0F);
            body.setPosX(0.0F);
            body.setPosY(0.0F);
            body.setPosZ(0.0F);
            body.setScaleX(BODY_INFLATION);
            body.setScaleY(BODY_INFLATION);
            body.setScaleZ(BODY_INFLATION);

            RenderType armorType = RenderType.armorCutoutNoCull(texture);
            this.getRenderer().renderRecursively(poseStack, animatable, body, armorType, bufferSource,
                    bufferSource.getBuffer(armorType), true, partialTick, packedLight, OverlayTexture.NO_OVERLAY,
                    1.0F, 1.0F, 1.0F, 1.0F);
        }
        finally
        {
            body.setRotX(rotX);
            body.setRotY(rotY);
            body.setRotZ(rotZ);
            body.setPosX(posX);
            body.setPosY(posY);
            body.setPosZ(posZ);
            body.setScaleX(scaleX);
            body.setScaleY(scaleY);
            body.setScaleZ(scaleZ);
            if (boobas != null)
            {
                boobas.setScaleX(savedBoobasX);
                boobas.setScaleY(savedBoobasY);
                boobas.setScaleZ(savedBoobasZ);
            }
            for (GeoBone tail : excludedTails)
            {
                tail.setHidden(false);
            }
            for (GeoBone child : hidden)
            {
                child.setHidden(false);
            }
        }
    }

    private static GeoBone getChild(GeoBone parent, String name)
    {
        for (GeoBone child : parent.getChildBones())
        {
            if (child.getName().equals(name))
            {
                return child;
            }
        }
        return null;
    }

    private static GeoBone findDeep(GeoBone parent, String name)
    {
        for (GeoBone child : parent.getChildBones())
        {
            if (child.getName().equals(name))
            {
                return child;
            }
            GeoBone found = findDeep(child, name);
            if (found != null)
            {
                return found;
            }
        }
        return null;
    }
}
