package net.shurui.shuruisutilities.core.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.dragonminez.common.stats.StatsData;

import net.minecraft.world.entity.player.Player;

import net.shurui.shuruisutilities.client.DmzStatsFallback;

/** ThirdPartyLayerForwarder: reuse one cached fallback StatsData per player instead of a new one per call; see DmzStatsFallback. */
@Mixin(targets = "com.dragonminez.client.render.layer.DMZThirdPartyLayerForwarder", remap = false)
public abstract class MixinDmzStatsFallbackThirdPartyLayerForwarder
{
    @Redirect(method = {
            "renderForBone(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/player/AbstractClientPlayer;Lsoftware/bernie/geckolib/cache/object/GeoBone;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/MultiBufferSource;Lcom/mojang/blaze3d/vertex/VertexConsumer;FII)V"
    }, at = @At(value = "NEW", target = "com/dragonminez/common/stats/StatsData"), remap = false, require = 0)
    private StatsData dmzr$cachedStatsFallback(Player player)
    {
        return DmzStatsFallback.forPlayer(player);
    }
}
