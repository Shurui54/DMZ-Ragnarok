package net.shurui.dev.shuruis_raid_bosses.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_raid_bosses.entity.RaidNpc;

/** Renders the default raid host entity as a humanoid. Any other entity type uses its own renderer. */
public class RaidNpcRenderer extends MobRenderer<RaidNpc, PlayerModel<RaidNpc>> {
    private static final ResourceLocation SKIN =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public RaidNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(RaidNpc entity) {
        return SKIN;
    }
}
