package net.shurui.dev.shuruis_dmz_tournaments.client;

import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.shuruis_dmz_tournaments.entity.TournamentNpc;

/** Renders the default tournament host entity as a humanoid. Any other entity type uses its own renderer. */
public class TournamentNpcRenderer extends MobRenderer<TournamentNpc, PlayerModel<TournamentNpc>> {
    private static final ResourceLocation SKIN =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    public TournamentNpcRenderer(EntityRendererProvider.Context ctx) {
        super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false), 0.5f);
    }

    @Override
    public ResourceLocation getTextureLocation(TournamentNpc entity) {
        return SKIN;
    }
}
