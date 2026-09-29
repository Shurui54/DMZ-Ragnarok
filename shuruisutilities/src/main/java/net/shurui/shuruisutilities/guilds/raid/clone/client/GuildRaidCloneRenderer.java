package net.shurui.shuruisutilities.guilds.raid.clone.client;

import com.dragonminez.client.init.entities.renderer.sagas.DBSagasRenderer;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.guilds.raid.clone.GuildRaidCloneEntity;

/**
 * Client renderer for the guild-raid clone DRIVER. It is DragonMineZ's own generic saga renderer with one change:
 * while a client-side appearance PUPPET exists for this driver (see {@link GuildRaidPuppetManager}), the driver's
 * own plain saga body is NOT drawn, because the puppet is drawing the true appearance in its place. If no puppet
 * exists (never built, or dropped after a failure), the driver renders normally as a plain DragonMineZ saga
 * humanoid, which is the graceful-degradation path: the clone stays visible and keeps fighting, just without the
 * member's exact look.
 *
 * <p>This is the single arbiter of "puppet or plain body", so the two can never both draw and never both vanish.
 * DragonMineZ is a mandatory dependency, so extending its renderer directly is fine; this only loads on the client.
 */
@OnlyIn(Dist.CLIENT)
public class GuildRaidCloneRenderer extends DBSagasRenderer<GuildRaidCloneEntity>
{
    public GuildRaidCloneRenderer(EntityRendererProvider.Context context)
    {
        super(context);
    }

    @Override
    public void render(GuildRaidCloneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight)
    {
        // puppet present: it draws the true appearance, so skip the plain driver body entirely.
        if (GuildRaidPuppetManager.hasPuppetFor(entity.getId()))
        {
            return;
        }
        super.render(entity, entityYaw, partialTick, poseStack, buffer, packedLight);
    }
}
