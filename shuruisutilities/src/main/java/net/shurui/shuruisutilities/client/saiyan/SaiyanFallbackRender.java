package net.shurui.shuruisutilities.client.saiyan;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * Shared client render support for drawing a saiyan on a DragonMineZ race geo. It holds the ONE copy of the mutable-baked-
 * model visibility baseline (so the hostile garrison saiyan renderer and the three rgnpc renderers cannot fork it and
 * drift apart), plus the DMZ race geo / blank texture locations those renderers pick a fallback body from.
 *
 * <p>The baseline exists because DragonMineZ's {@code human.geo.json} / {@code majin_slim.geo.json} baked models are
 * SHARED AND MUTABLE: GeckoLib caches ONE {@link BakedGeoModel} instance per geo {@link ResourceLocation} in {@code
 * GeckoLibCache.getBakedModels()}, so DragonMineZ's own player renderer, our garrison saiyan renderer and any rgnpc that
 * has fallen back to a saiyan all mutate the SAME {@link GeoBone} objects. Without re-establishing a full baseline at the
 * start of every render, a bone could carry a hidden state left over from whatever was drawn immediately before, making
 * output depend on draw order. Setting the whole baseline unconditionally each frame removes that dependency: no layer
 * needs its restore to be perfect, because the next frame re-establishes the baseline before anything draws, and
 * DragonMineZ likewise re-establishes its own before drawing a player.
 */
public final class SaiyanFallbackRender
{
    private SaiyanFallbackRender() {}

    /**
     * DragonMineZ's own race geos, the fallback body a rgnpc borrows when its own model is not installed. A MALE saiyan
     * uses {@code human.geo.json}; a FEMALE uses {@code majin_slim.geo.json}, which (unlike bare {@code majin.geo.json})
     * carries the tail bones the tail layer needs. These are DMZ's most stable geos; a DMZ move would surface as a
     * missing-model log rather than a crash.
     */
    public static final ResourceLocation MALE_GEO =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "geo/entity/races/human.geo.json");
    public static final ResourceLocation FEMALE_GEO =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "geo/entity/races/majin_slim.geo.json");

    /**
     * DMZ's blank {@code null.png}. When an rgnpc falls back to a saiyan, the BASE model is drawn with this blank texture
     * and every visible pixel comes from the saiyan layer stack (body, face, hair, tail, armour), exactly as DMZ paints a
     * player. A missing null.png degrades to an invisible body rather than a crash.
     */
    public static final ResourceLocation BLANK_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/entity/races/null.png");

    /**
     * Establish a full, known visibility baseline on the shared baked race model at the very start of a render, exactly as
     * DragonMineZ does in {@code DMZPlayerRenderer.preRender} -> {@code BoneVisibilityHandler.updateVisibility}. Set every
     * bone this stack cares about to a definite state, so no bone's visibility can depend on the previous render. Order
     * matters: reset everything visible first, then apply the fixed hides.
     *
     * <p>Callers MUST guard this on {@code !isReRender}: GeckoLib calls {@code preRender} again with {@code isReRender ==
     * true} for every {@code reRender} a layer performs, and the layers hide bones per pass right before calling {@code
     * reRender}. Re-applying the baseline then would clobber those per-pass hides. Callers must ALSO only call this when
     * the entity is actually drawing the race geo (its own rgnpc model is absent): a real ragnarok geo has none of these
     * bones, so running the baseline on it is pointless, and, more importantly, the saiyan layers must stay inert on a
     * real model so a saiyan body is never painted over a ragnarok character.
     */
    public static <T extends Mob & SaiyanAppearance> void applyVisibilityBaseline(BakedGeoModel model, T animatable)
    {
        boolean male = animatable.isMale();
        // 1) Everything visible. This resets, symmetrically, body / right_arm / left_arm / right_leg / left_leg / head
        // and every second-layer bone (body_layer, *_leg_layer, ...), so nothing a prior render left hidden survives.
        // Notably right_leg and left_leg always end this step in the SAME state, which is where the bodysuit "shoes" are
        // painted, so one cannot render without the other because of leaked state.
        for (GeoBone bone : model.topLevelBones())
        {
            setHiddenRecursive(bone, false);
        }
        // 2) The MAJIN straight-tail chain (tail1m..tail6m, present only on the female majin_slim geo) is never drawn on a
        // saiyan. Hide the whole chain unconditionally; this is the majin-tail fix, now guaranteed every frame rather than
        // depending on each layer's per-pass hide-and-restore. A no-op on the male human geo, which has no such bone.
        model.getBone("tail1m").ifPresent(bone -> setHiddenRecursive(bone, true));
        // 3) The saiyan tail (tail1..tail5) is always shown for a saiyan.
        model.getBone("tail1").ifPresent(bone -> setHiddenRecursive(bone, false));
        // 4) boobas (the female chest bone) exists only on the female geo; show it for a female body, hide it otherwise.
        // DragonMineZ hides it for non-female bodies the same way. A no-op on the male human geo.
        model.getBone("boobas").ifPresent(bone -> bone.setHidden(male));
        // 5) Hide the second-layer (skin overlay) bones the way DMZ's BoneVisibilityHandler does for a no-jacket standard
        // body. DMZ hides body_layer / *_arm_layer when a chestplate is worn OR the body is a standard saiyan/human body
        // with no jacket/sleeve model part, and the *_leg_layer when leggings are worn OR no pants part; hat_layer is hidden
        // for a standard body with no hat part. A mob has no PlayerModelPart to show, so those "no part" terms are always
        // true, collapsing each condition to (piece worn) OR (standard body). Without this, a resource pack with opaque
        // pixels in those overlay regions would render white shells over the armor; DMZ's stock textures are transparent
        // there, so this is a no-op on the stock pack. isStandardBody mirrors DMZ: a saiyan body of type 0 or 1 (type 2 is
        // the non-standard build, on which DMZ leaves these overlays alone).
        boolean hasChest = !animatable.getItemBySlot(EquipmentSlot.CHEST).isEmpty();
        boolean hasLegs = !animatable.getItemBySlot(EquipmentSlot.LEGS).isEmpty();
        boolean standardBody = animatable.getBodyType() == 0 || animatable.getBodyType() == 1;
        hideBone(model, "body_layer", hasChest || standardBody);
        hideBone(model, "right_arm_layer", hasChest || standardBody);
        hideBone(model, "left_arm_layer", hasChest || standardBody);
        hideBone(model, "right_leg_layer", hasLegs || standardBody);
        hideBone(model, "left_leg_layer", hasLegs || standardBody);
        // hat_layer is hidden unconditionally rather than only on a standard body. DMZ leaves it to the wearer because a
        // PLAYER can put something meaningful there; these NPCs never can, so on the roughly half of them rolled as body
        // type 2 it was the only head bone still showing, which matches a white box on some heads and not others.
        hideBone(model, "hat_layer", true);
        // 6) This stack never draws through the armor bones: PlanetSaiyanArmorLayer paints the chestplate onto the inflated
        // body bone instead. Hide EVERY armor-prefixed bone so none can leak into a pass (for example armorBody2 being
        // painted with the chest texture during the armor pass). Mirrors DragonMineZ's hideAllArmorPrefixBones.
        for (GeoBone bone : model.topLevelBones())
        {
            hideArmorPrefixRecursive(bone);
        }
    }

    // hide/show a single named bone if present, mirroring DMZ's BoneVisibilityHandler.hideBone.
    private static void hideBone(BakedGeoModel model, String name, boolean hidden)
    {
        model.getBone(name).ifPresent(bone -> bone.setHidden(hidden));
    }

    private static void setHiddenRecursive(GeoBone bone, boolean hidden)
    {
        bone.setHidden(hidden);
        for (GeoBone child : bone.getChildBones())
        {
            setHiddenRecursive(child, hidden);
        }
    }

    private static void hideArmorPrefixRecursive(GeoBone bone)
    {
        if (bone.getName() != null && bone.getName().startsWith("armor"))
        {
            bone.setHidden(true);
        }
        for (GeoBone child : bone.getChildBones())
        {
            hideArmorPrefixRecursive(child);
        }
    }
}
