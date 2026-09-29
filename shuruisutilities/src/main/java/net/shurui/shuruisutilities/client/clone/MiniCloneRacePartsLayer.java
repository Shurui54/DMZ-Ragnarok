package net.shurui.shuruisutilities.client.clone;

import java.util.Locale;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.clone.MiniCloneEntity;

/**
 * Repaints a PLAYER_COPY clone's vanilla skin with the caster's DragonMineZ race body appearance.
 *
 * <h2>Why this exists</h2>
 * DragonMineZ does not draw a race onto a vanilla player model, it REPLACES the whole player with a GeckoLib model
 * (DMZPlayerRenderer over DMZPlayerModel) and stacks GeckoLib layers on it. The two layers that carry the race look,
 * DMZSkinLayer and DMZRacePartsLayer, are both bound to {@code AbstractClientPlayer & GeoAnimatable} and draw per
 * GeckoLib bone, so neither can be attached to this clone (a vanilla {@link PlayerModel} over a TamableAnimal). Direct
 * reuse is therefore impossible; this layer reimplements the ONE piece that matters for a "copy of the player": the
 * body-layer pass.
 *
 * <h2>What it reproduces, and how faithfully</h2>
 * DMZ resolves the body in {@code SkinGathererProvider.gatherBodyLayers}, which emits a short list of (texture, colour)
 * pairs and paints each as a whole-model overlay multiplied by its colour (a multiply, not a mask). Its race textures
 * live under {@code assets/dragonminez/textures/entity/races/<dir>/<token>_<bodyType>_layer<K>.png} and are authored on
 * the 64x64 vanilla player-skin UV layout, which is exactly why a vanilla {@link PlayerModel} can wear them. This layer
 * follows the same path pattern and the same colour-to-layer order DMZ uses:
 * <ul>
 *   <li>Namekian and Frost Demon: {@code dir}/{@code token} are the race id and the literal {@code bodytype}, layers
 *       1..4 tinted by body colour 1, body colour 2, body colour 3 and hair colour. Frost Demon's 3 body layers plus
 *       its 4th (and its form-only 5th, which this layer omits) are covered by the same loop, since a missing layer
 *       texture is simply skipped.</li>
 *   <li>Custom races (for example {@code shadow_dragon}): {@code dir} is the race id and {@code token} is the race's
 *       configured custom model (for shadow dragon, {@code oshenron}), read from DMZ's own {@code ConfigManager} so the
 *       correct filenames resolve. Same layer loop and colours.</li>
 *   <li>Human and Saiyan use a single un-tinted {@code humansaiyan} texture whose skin tone is already baked in, and
 *       which is what a plain player skin already approximates, so this layer draws nothing for them and lets the base
 *       skin stand. Majin and bio-android never reach PLAYER_COPY (they render as Buu / Cell Jr.).</li>
 * </ul>
 * The clone is snapshotted in its base form, so transformation-specific body textures are not reproduced.
 *
 * <h2>Safety</h2>
 * A render-thread throw takes the client down, so every DMZ read and every texture lookup is wrapped: an unknown race,
 * a null character (default appearance), a missing texture or a config miss all degrade to the plain vanilla skin and
 * never throw. The layer only runs for {@link MiniCloneEntity.Variant#PLAYER_COPY}.
 */
@OnlyIn(Dist.CLIENT)
public class MiniCloneRacePartsLayer extends RenderLayer<MiniCloneEntity, PlayerModel<MiniCloneEntity>>
{
    // Race ids whose body is a single un-tinted humansaiyan texture; the plain skin already approximates them, so this
    // layer leaves them alone rather than guessing at the humansaiyan pass.
    private static final String RACE_HUMAN = "human";
    private static final String RACE_SAIYAN = "saiyan";
    // The two builtin races whose layered body textures sit under races/<race>/bodytype_<N>_layerK.png.
    private static final String RACE_NAMEKIAN = "namekian";
    private static final String RACE_FROSTDEMON = "frostdemon";

    private static final String TEXTURE_ROOT = "textures/entity/races/";
    private static final String BUILTIN_LAYER_TOKEN = "bodytype";
    private static final int MAX_BODY_LAYERS = 4;

    public MiniCloneRacePartsLayer(RenderLayerParent<MiniCloneEntity, PlayerModel<MiniCloneEntity>> parent)
    {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource bufferSource, int packedLight, MiniCloneEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw,
                       float headPitch)
    {
        try
        {
            if (entity == null || entity.getVariant() != MiniCloneEntity.Variant.PLAYER_COPY)
            {
                return;
            }
            String race = entity.getRaceName();
            if (race == null || race.isEmpty())
            {
                return;
            }
            race = race.toLowerCase(Locale.ROOT);
            if (RACE_HUMAN.equals(race) || RACE_SAIYAN.equals(race))
            {
                return;
            }

            String dir = race;
            String token = resolveLayerToken(race);
            if (token == null || token.isEmpty())
            {
                return;
            }

            int bodyType = entity.getBodyType();
            int[] colors =
            {
                entity.getBodyColor1(),
                entity.getBodyColor2(),
                entity.getBodyColor3(),
                entity.getHairColor()
            };

            PlayerModel<MiniCloneEntity> model = this.getParentModel();
            for (int k = 0; k < MAX_BODY_LAYERS; k++)
            {
                ResourceLocation texture = resolveLayerTexture(dir, token, bodyType, k + 1);
                if (texture == null)
                {
                    continue;
                }
                float[] rgb = unpack(colors[k]);
                RenderType renderType = RenderType.entityTranslucentCull(texture);
                VertexConsumer buffer = bufferSource.getBuffer(renderType);
                model.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY,
                        rgb[0], rgb[1], rgb[2], 1.0F);
            }
        }
        catch (Throwable ignored)
        {
            // Never take the render thread down for a cosmetic overlay: fall back to the plain skin.
        }
    }

    /**
     * The filename token between the race directory and the body type. For the two builtin layered races it is the
     * literal {@code bodytype}; for a custom race it is the race's configured custom model (read from DMZ's own config,
     * matching how {@code SkinGathererProvider} resolves the path), falling back to the race id.
     */
    private static String resolveLayerToken(String race)
    {
        if (RACE_NAMEKIAN.equals(race) || RACE_FROSTDEMON.equals(race))
        {
            return BUILTIN_LAYER_TOKEN;
        }
        try
        {
            com.dragonminez.common.config.RaceCharacterConfig config =
                    com.dragonminez.common.config.ConfigManager.getRaceCharacter(race);
            if (config != null)
            {
                String custom = config.getCustomModel();
                if (custom != null && !custom.isEmpty())
                {
                    return custom.toLowerCase(Locale.ROOT);
                }
            }
        }
        catch (Throwable ignored)
        {
            // fall through to the race id
        }
        return race;
    }

    /**
     * Resolve {@code races/<dir>/<token>_<bodyType>_layer<k>.png}, falling back to body type 0 the way DMZ's
     * {@code getSafeTexture} does, and returning null when neither file is present so the caller skips the layer.
     */
    private static ResourceLocation resolveLayerTexture(String dir, String token, int bodyType, int k)
    {
        ResourceLocation specific = new ResourceLocation("dragonminez",
                TEXTURE_ROOT + dir + "/" + token + "_" + bodyType + "_layer" + k + ".png");
        if (exists(specific))
        {
            return specific;
        }
        if (bodyType != 0)
        {
            ResourceLocation base = new ResourceLocation("dragonminez",
                    TEXTURE_ROOT + dir + "/" + token + "_0_layer" + k + ".png");
            if (exists(base))
            {
                return base;
            }
        }
        return null;
    }

    private static boolean exists(ResourceLocation location)
    {
        try
        {
            return Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /** Packed 0xRRGGBB to a float triplet in 0..1, for the multiply passed to {@code renderToBuffer}. */
    private static float[] unpack(int rgb)
    {
        return new float[]
        {
            ((rgb >> 16) & 0xFF) / 255.0F,
            ((rgb >> 8) & 0xFF) / 255.0F,
            (rgb & 0xFF) / 255.0F
        };
    }
}
