package net.shurui.shuruisutilities.client.corrupted;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.corrupted.client.DefiledBallsClient;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Swaps DragonMineZ's EARTH radar item sprite for SU's red/black shadow sprite while the dragon balls are defiled, in the
 * inventory and in hand. This is the item-sprite half of the defiled-radar swap; the GUI dial half lives in
 * {@link net.shurui.shuruisutilities.client.hud.RadarBackgrounds} and {@code MixinDmzRadarDraw}, and both halves read the
 * same client flag {@link DefiledBallsClient#isEarthDefiled()} so the dial and the held item agree at all times.
 *
 * <p>HOW. At {@link ModelEvent.ModifyBakingResult} (client, mod bus) we replace the baked model registered for
 * {@code dragonminez:dball_radar#inventory} with a thin {@link DefiledRadarModel} delegate that holds BOTH baked models,
 * DMZ's stock one and SU's {@code shuruisutilities:item/shadow_dball_radar}, and forwards every call to whichever the
 * defiled flag selects at that instant. The item renderer re-resolves the model each frame, so the flag is consulted per
 * render and the sprite flips live when the balls are defiled or cleansed. No mixin and no shipped model override are
 * involved, so there is no {@code mixins.shuruisutilities.json} or {@code addonMixinConfigs} change.
 *
 * <p>SU's shadow model is consumed by nothing else (no item points at it), so a plain {@code item/generated} model would
 * not be baked and would be absent from the baking result. {@link #onRegisterAdditional} registers it for baking so it is
 * present here.
 *
 * <p>FAIL SOFT AND LOUD-ONCE. If DMZ's radar model is absent (DMZ missing or the item renamed) or SU's shadow model
 * failed to bake, we leave the baking result untouched and log one line. The handler never throws and never installs a
 * delegate holding a null, because a fault here would take the client down at startup.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShadowRadarModelBind
{
    // DMZ's earth radar item model. Item models are keyed in the baking result by their "inventory" ModelResourceLocation
    // (ModelBakery loads each item as new ModelResourceLocation(itemId, "inventory")), so we must look it up with a
    // ModelResourceLocation, never a plain ResourceLocation (their equals/hashCode differ by variant).
    private static final ModelResourceLocation DMZ_RADAR =
            new ModelResourceLocation("dragonminez", "dball_radar", "inventory");

    // SU's shadow radar model, registered for baking via RegisterAdditional below and therefore keyed in the baking
    // result by this exact plain ResourceLocation (RegisterAdditional models are stored under the location as given).
    private static final ResourceLocation SHADOW_MODEL =
            new ResourceLocation(ShuruisUtilities.MODID, "item/shadow_dball_radar");

    // one-shot latches so each outcome logs exactly once for the client's lifetime, never once per resource reload.
    private static final AtomicBoolean BOUND_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean DMZ_MISSING_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SHADOW_MISSING_LOGGED = new AtomicBoolean(false);

    private ShadowRadarModelBind()
    {
    }

    /**
     * Register SU's shadow radar model for baking. Nothing else references it, so without this it would not be baked and
     * would be missing from the baking result, leaving the swap with nothing to delegate to.
     */
    @SubscribeEvent
    public static void onRegisterAdditional(ModelEvent.RegisterAdditional event)
    {
        event.register(SHADOW_MODEL);
    }

    /**
     * Wrap DMZ's earth radar inventory model in the defiled-aware delegate. Fail-soft: on any missing model or fault the
     * baking result is left untouched and DMZ keeps its stock sprite.
     */
    @SubscribeEvent
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event)
    {
        try
        {
            Map<ResourceLocation, BakedModel> models = event.getModels();

            BakedModel dmz = models.get(DMZ_RADAR);
            if (dmz == null)
            {
                if (DMZ_MISSING_LOGGED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.info(
                            "[Radar] earth radar model dragonminez:dball_radar#inventory absent; shadow item swap skipped "
                                    + "(DragonMineZ missing or the radar renamed)");
                }
                return;
            }

            BakedModel shadow = models.get(SHADOW_MODEL);
            if (shadow == null)
            {
                if (SHADOW_MISSING_LOGGED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.info(
                            "[Radar] shadow radar model shuruisutilities:item/shadow_dball_radar failed to bake; shadow "
                                    + "item swap skipped, DMZ radar left untouched");
                }
                return;
            }

            if (dmz instanceof DefiledRadarModel)
            {
                // already wrapped on a prior reload; nothing to do (defensive, the map is normally rebuilt each reload).
                return;
            }

            models.put(DMZ_RADAR, new DefiledRadarModel(dmz, shadow));
            if (BOUND_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info(
                        "[Radar] shadow radar item model bound (dragonminez:dball_radar#inventory delegate installed)");
            }
        }
        catch (Throwable t)
        {
            // never crash the client during model baking; on any fault keep DMZ's stock radar model.
        }
    }

    /**
     * Thin {@link BakedModel} that forwards every call to DMZ's stock model or SU's shadow model, chosen live by
     * {@link DefiledBallsClient#isEarthDefiled()}. Both delegates are guaranteed non-null by the installer above. Every vanilla
     * and Forge method is delegated so item transforms in hand, particle icon, overrides and render passes all match the
     * selected model rather than falling through to a default that would break the held-item look.
     */
    private static final class DefiledRadarModel implements BakedModel
    {
        private final BakedModel original;
        private final BakedModel shadow;

        private DefiledRadarModel(BakedModel original, BakedModel shadow)
        {
            this.original = original;
            this.shadow = shadow;
        }

        private BakedModel pick()
        {
            return DefiledBallsClient.isEarthDefiled() ? shadow : original;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand)
        {
            return pick().getQuads(state, side, rand);
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data,
                RenderType renderType)
        {
            return pick().getQuads(state, side, rand, data, renderType);
        }

        @Override
        public boolean useAmbientOcclusion()
        {
            return pick().useAmbientOcclusion();
        }

        @Override
        public boolean useAmbientOcclusion(BlockState state)
        {
            return pick().useAmbientOcclusion(state);
        }

        @Override
        public boolean useAmbientOcclusion(BlockState state, RenderType renderType)
        {
            return pick().useAmbientOcclusion(state, renderType);
        }

        @Override
        public boolean isGui3d()
        {
            return pick().isGui3d();
        }

        @Override
        public boolean usesBlockLight()
        {
            return pick().usesBlockLight();
        }

        @Override
        public boolean isCustomRenderer()
        {
            return pick().isCustomRenderer();
        }

        @Override
        public TextureAtlasSprite getParticleIcon()
        {
            return pick().getParticleIcon();
        }

        @Override
        public TextureAtlasSprite getParticleIcon(ModelData data)
        {
            return pick().getParticleIcon(data);
        }

        @Override
        public ItemTransforms getTransforms()
        {
            return pick().getTransforms();
        }

        @Override
        public ItemOverrides getOverrides()
        {
            return pick().getOverrides();
        }

        @Override
        public BakedModel applyTransform(ItemDisplayContext transformType, PoseStack poseStack,
                boolean applyLeftHandTransform)
        {
            // apply the selected model's own transform and hand rendering back to the real selected model, so held and
            // GUI transforms are exactly DMZ's or SU's, never a broken default.
            return pick().applyTransform(transformType, poseStack, applyLeftHandTransform);
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData)
        {
            return pick().getModelData(level, pos, state, modelData);
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data)
        {
            return pick().getRenderTypes(state, rand, data);
        }

        @Override
        public List<RenderType> getRenderTypes(ItemStack itemStack, boolean fabulous)
        {
            return pick().getRenderTypes(itemStack, fabulous);
        }

        @Override
        public List<BakedModel> getRenderPasses(ItemStack itemStack, boolean fabulous)
        {
            return pick().getRenderPasses(itemStack, fabulous);
        }
    }
}
