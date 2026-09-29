package net.shurui.shuruisutilities.client.gravitychamber;

import com.dragonminez.client.init.entities.renderer.sagas.DBSagasRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.gravitychamber.ChamberSparEntity;
import net.shurui.shuruisutilities.guilds.raid.clone.client.GuildRaidPuppetManager;

/**
 * Client renderer for the guild-chamber sparring dummy. It is DragonMineZ's generic saga renderer with the same one
 * change the guild-raid clone renderer makes: while a client-side appearance PUPPET exists for this dummy (the source
 * member's true look, drawn by {@link net.shurui.shuruisutilities.guilds.raid.clone.client.GuildRaidPuppetRenderHook}),
 * the dummy's own plain saga body is not drawn, so exactly one body appears. If no puppet exists (never built or
 * dropped after a failure) the dummy renders as a plain saga humanoid, the graceful-degradation path. The puppet
 * manager is keyed by the driver's network id, so it serves this dummy without any dummy-specific code.
 */
@OnlyIn(Dist.CLIENT)
public class ChamberSparRenderer extends DBSagasRenderer<ChamberSparEntity>
{
    public ChamberSparRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public void render(ChamberSparEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
            MultiBufferSource buffer, int packedLight)
    {
        if (GuildRaidPuppetManager.hasPuppetFor(entity.getId()))
        {
            return;
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }
}
