package net.shurui.shuruisutilities.client.clone;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;

/**
 * Renders the PLAYER_COPY clone variant: a 60% scale humanoid wearing the caster's real skin and the armour copied
 * onto the clone's own equipment slots. It is a stock {@link LivingEntityRenderer} over a {@link PlayerModel} plus the
 * vanilla {@link HumanoidArmorLayer}, so the armour "just works" from the entity's slots and the vanilla glowing
 * outline pass is untouched.
 *
 * <h2>Skin resolution without a tab-list entry</h2>
 * The clone is a real tracked entity, not a player, so it has no {@link PlayerInfo} in the client tab list. Following
 * {@code GuildRaidPuppet}, this builds its OWN {@link PlayerInfo} from the owner's {@link GameProfile} (name + UUID
 * carried on the entity's synced fields), which performs the normal Mojang session-server skin fetch, and reads the
 * skin location and slim/wide model from it. Until the fetch resolves it falls back to the default skin. Results are
 * cached per owner UUID so the fetch is built once, not every frame.
 *
 * <p>The caster's DragonMineZ race appearance is painted over the vanilla skin by {@link MiniCloneRacePartsLayer}, so a
 * Namekian clone is green, a Frost Demon clone is layered, and a custom race (Shadow Dragon) wears its own textures.
 * Known cosmetic gaps versus a real player render: the owner's cape is not drawn (there is no player cape layer on a
 * non-player entity), and the snapshot is the caster's BASE form, so transformation-specific body textures, the DMZ
 * face and hair geometry, and the extra race parts (horns, tails) are not reproduced.
 */
@OnlyIn(Dist.CLIENT)
public class MiniClonePlayerRenderer extends LivingEntityRenderer<MiniCloneEntity, PlayerModel<MiniCloneEntity>>
{
    // owner UUID -> its resolved PlayerInfo. Concurrent because a skin fetch may complete off the render thread; reads
    // happen on the render thread. Bounded in practice by the number of distinct casters currently on screen.
    private static final Map<UUID, PlayerInfo> INFO_CACHE = new ConcurrentHashMap<>();

    private final PlayerModel<MiniCloneEntity> wideModel;
    private final PlayerModel<MiniCloneEntity> slimModel;

    public MiniClonePlayerRenderer(EntityRendererProvider.Context context)
    {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F * MiniCloneEntity.CLONE_SCALE);
        this.wideModel = this.model;
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        // DMZ race body pass (Namekian green, Frost Demon layers, Shadow Dragon, ...) over the vanilla skin, before the
        // armour so armour draws on top the way it does on a real DMZ player.
        addLayer(new MiniCloneRacePartsLayer(this));
        addLayer(new HumanoidArmorLayer<>(this,
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
                context.getModelManager()));
    }

    @Override
    public void render(MiniCloneEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource bufferSource, int packedLight)
    {
        // Pick the slim or wide player model to match the owner's skin before the base model draws.
        this.model = isSlim(entity) ? this.slimModel : this.wideModel;
        super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
    }

    @Override
    protected void scale(MiniCloneEntity entity, PoseStack poseStack, float partialTick)
    {
        poseStack.scale(MiniCloneEntity.CLONE_SCALE, MiniCloneEntity.CLONE_SCALE, MiniCloneEntity.CLONE_SCALE);
    }

    @Override
    public ResourceLocation getTextureLocation(MiniCloneEntity entity)
    {
        PlayerInfo info = resolveInfo(entity);
        if (info != null)
        {
            try
            {
                return info.getSkinLocation();
            }
            catch (Throwable ignored)
            {
                // fall through to the default skin
            }
        }
        return DefaultPlayerSkin.getDefaultSkin(fallbackId(entity));
    }

    private boolean isSlim(MiniCloneEntity entity)
    {
        PlayerInfo info = resolveInfo(entity);
        if (info != null)
        {
            try
            {
                return "slim".equals(info.getModelName());
            }
            catch (Throwable ignored)
            {
                // fall through to the default model
            }
        }
        return "slim".equals(DefaultPlayerSkin.getSkinModelName(fallbackId(entity)));
    }

    // build (once) and cache a PlayerInfo for the owner so getSkinLocation / getModelName can resolve the caster's real
    // appearance without the clone being in the client tab list. Null when no owner UUID is known, which routes callers
    // to a default skin.
    private PlayerInfo resolveInfo(MiniCloneEntity entity)
    {
        UUID ownerId = entity.getOwnerProfileId();
        if (ownerId == null)
        {
            return null;
        }
        PlayerInfo cached = INFO_CACHE.get(ownerId);
        if (cached != null)
        {
            return cached;
        }
        try
        {
            String name = entity.getOwnerName();
            if (name == null || name.isEmpty())
            {
                name = "clone";
            }
            PlayerInfo info = new PlayerInfo(new GameProfile(ownerId, name), false);
            INFO_CACHE.put(ownerId, info);
            return info;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    // a stable UUID for default-skin selection: the owner's if known, otherwise the clone's own, so the default skin at
    // least stays consistent for a given clone.
    private static UUID fallbackId(MiniCloneEntity entity)
    {
        UUID ownerId = entity.getOwnerProfileId();
        return ownerId != null ? ownerId : entity.getUUID();
    }
}
