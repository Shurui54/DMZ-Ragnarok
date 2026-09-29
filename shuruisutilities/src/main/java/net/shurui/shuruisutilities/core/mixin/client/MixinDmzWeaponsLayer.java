package net.shurui.shuruisutilities.core.mixin.client;

import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.HumanoidArm;

import net.shurui.shuruisutilities.client.cosmetics.WardrobeCosmeticLayer;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import software.bernie.geckolib.cache.object.GeoBone;

/**
 * Replaces DragonMineZ's own ki weapon model with a HAND-style accessory cosmetic on a player wearing one: it draws
 * the cosmetic skin in the weapon's exact pose and then cancels DMZ's weapon, so the skin stands in for the weapon
 * instead of drawing a second object beside it or leaving the player empty handed.
 *
 * <h2>Where it hooks, and why it also DRAWS here</h2>
 * DMZ draws its ki weapon in two steps, read from the 2.1.3 bytecode: {@code DMZWeaponsLayer.renderForBone}, a
 * GeckoLib per-bone layer, decides a weapon is out (main hand empty, {@code kimanipulation} active, a real weapon
 * type) and QUEUES it with {@code PlayerEffectQueue.addWeapon(player, model, poseStack, ...)}, which captures
 * {@code new Matrix4f(poseStack.last().pose())}; {@code KiWeaponRenderer.processWeapons} later replays that captured
 * matrix and, for the standard weapon, applies no held-item tilt. The GeckoLib callback runs AFTER
 * {@code RenderUtils.prepMatrixForBone}, so at the HEAD of {@code renderForBone} this poseStack already carries the
 * arm bone's full animated transform. So this is both the point DMZ captures its pose AND the point to draw our
 * skin: drawing here with the same poseStack puts the cosmetic in the identical position, rotation, scale and live
 * animation as the weapon, frame for frame, with no capture-and-replay and no one-frame lag. (The cosmetic layer
 * runs off DMZ's {@code DMZThirdPartyLayerForwarder} on the ROOT bone, which is visited BEFORE this arm bone, so a
 * matrix stored here for the layer to read would always be a frame stale; that is why the skin is drawn here.)
 * {@link WardrobeCosmeticLayer#renderHandSkin} does the resolution and the item-model-into-weapon-space mapping.
 *
 * <h2>The single gate, drawn ONCE on the main-hand arm</h2>
 * It acts only when {@link WardrobeCosmeticLayer#willDrawHandAccessory} says the skin is genuinely being drawn this
 * frame: the same visibility rule the cosmetic layer uses, a HAND-style accessory that resolves to real art, and a
 * ki weapon actually out. It runs only on the two arm bones (the only bones DMZ's weapon touches) and draws exactly
 * once, on the MAIN-hand arm, mirroring DMZ's own {@code getMainArm}/bone-name check, so the skin is not drawn per
 * bone. The cancel then suppresses DMZ's weapon on the arm bones. That one shared authority means DMZ's weapon is
 * never hidden under nothing, and it is weapon-type agnostic, covering DMZ's blade, scythe and clawlance alike.
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but a future DMZ build that reshapes this
 * method degrades to "DMZ draws its weapon as before, no skin" rather than crashing the render thread, which is the
 * safe direction. A miss is then silent, so the one-shot bind log below is the only proof it wove. Client only
 * (listed in the {@code client} block of {@code mixins.shuruisutilities.json}); it never loads server side. The
 * erased descriptor of the generic {@code renderForBone(PoseStack, T, ...)} has {@code T} bounded by
 * AbstractClientPlayer, so an AbstractClientPlayer-typed capture binds the real method, never the synthetic bridge.
 */
@Mixin(targets = "com.dragonminez.client.render.layer.DMZWeaponsLayer", remap = false)
public abstract class MixinDmzWeaponsLayer
{
    private static final AtomicBoolean SU_WEAPON_SKIN_BIND_LOGGED = new AtomicBoolean(false);

    @Inject(method = "renderForBone", at = @At("HEAD"), remap = false, require = 0, cancellable = true)
    private void su$hideKiWeaponUnderHandSkin(PoseStack poseStack, AbstractClientPlayer player, GeoBone bone,
            RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer, float partialTick,
            int packedLight, int packedOverlay, CallbackInfo ci)
    {
        if (SU_WEAPON_SKIN_BIND_LOGGED.compareAndSet(false, true))
        {
            try
            {
                LoggingHandler.sulog.info(
                        "[Cosmetics] MixinDmzWeaponsLayer bound (DMZWeaponsLayer.renderForBone HEAD, ki weapon skin)");
            }
            catch (Throwable ignored)
            {
            }
        }
        try
        {
            if (player == null || bone == null)
                return;
            // Only the two arm bones matter: they are the only bones DMZ's weapon renders on, so there is nothing to
            // suppress or draw elsewhere and the per-frame wardrobe lookup is skipped for every other bone.
            String boneName = bone.getName();
            boolean rightArm = "right_arm".equals(boneName);
            boolean leftArm = "left_arm".equals(boneName);
            if (!rightArm && !leftArm)
                return;
            if (!WardrobeCosmeticLayer.willDrawHandAccessory(player))
                return;
            // Draw the skin once, on the MAIN-hand arm bone, in DMZ's exact captured pose. This mirrors DMZ's own
            // main-arm selection so the cosmetic is not drawn on both arms.
            boolean mainRight = player.getMainArm() == HumanoidArm.RIGHT;
            if (mainRight == rightArm)
                WardrobeCosmeticLayer.renderHandSkin(poseStack, player, bufferSource, packedLight, partialTick);
            // Suppress DMZ's own weapon on the arm bones (DMZ only queues it on the main-hand arm anyway; cancelling
            // on the off-hand arm, where it would early-return, is harmless).
            ci.cancel();
        }
        catch (Throwable ignored)
        {
            // Any failure resolving or drawing the wardrobe leaves DMZ's own ki weapon render untouched.
        }
    }
}
