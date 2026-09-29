package net.shurui.shuruisutilities.client.space;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.mojang.logging.LogUtils;

import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import software.bernie.geckolib.constant.DataTickets;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.model.data.EntityModelData;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.ragnarok.client.RgNpcFallback;
import net.shurui.shuruisutilities.space.PlanetGarrisonDefenderEntity;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;

/**
 * GeoModel for the {@link PlanetGarrisonDefenderEntity} wild-planet garrison fighter. This is the piece that lets a
 * DragonMineZ saga entity render with an arbitrary rgnpc GeckoLib model: it resolves the geo and texture from the
 * entity's synced rgnpc entry id through {@link RgNpcModels} (exactly like {@link
 * net.shurui.shuruisutilities.ragnarok.client.RgNpcModel}), while serving DragonMineZ's own {@code
 * saga_base.animation.json}. The rgnpc geos were retargeted onto that saga rig, so the saga combat animation
 * controllers the entity inherits from {@code DBSagasEntity} drive the rgnpc bones with no extra work.
 *
 * <p>Resolution is defensive on two separate counts, and it needs both. An unknown synced model id falls back to
 * {@link RgNpcModels#DEFAULT_ID} and a bad texture to that model's default, so a stale sync draws a known good
 * character. Then every resolved location goes through {@link RgNpcFallback}, because a VALID id is not a promise
 * that the file exists: the rgnpc set is streamed from the server's folder rather than bundled, and a client whose
 * server never installed it holds none of these models. GeckoLib does not degrade on a missing geo, it throws out of
 * {@code getBakedModel} on the render thread. A distinct bad raw value is warned about ONCE, never per frame.
 */
public class PlanetGarrisonDefenderModel extends GeoModel<PlanetGarrisonDefenderEntity>
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String GEO_PREFIX = "geo/entity/ragnarok/";
    private static final String GEO_SUFFIX = ".geo.json";
    private static final String TEX_PREFIX = "textures/entity/ragnarok/";
    private static final String TEX_SUFFIX = ".png";

    // getModelResource / getTextureResource run per entity per frame; remember bad raw values so a broken sync logs
    // once rather than flooding the log every frame.
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    // DragonMineZ's saga animation library, the same file its own saga entities use. Bones match the rig the rgnpc
    // models were retargeted onto, so the inherited saga controllers find their clips.
    private static final ResourceLocation ANIM_SAGA_BASE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    @Override
    public ResourceLocation getModelResource(PlanetGarrisonDefenderEntity animatable)
    {
        // Validating the id is NOT enough: the rgnpc models are streamed from the server folder, not bundled, so a
        // perfectly valid id can name a file this client does not hold. GeckoLib throws out of getBakedModel on the
        // render thread rather than degrading, which takes the client down, so the location is checked before it is
        // handed over.
        ResourceLocation wanted = wantedGeo(animatable);
        if (RgNpcFallback.present(wanted))
        {
            return wanted;
        }
        // Missing means a generated DragonMineZ saiyan, not a crash and not a plain Steve: borrow DMZ's race geo chosen by
        // the UUID-rolled gender, drawn blank and painted by the saiyan layer stack (see getTextureResource and the
        // fallback layer on PlanetGarrisonDefenderRenderer). Warn once so the miss shows in the log without flooding it.
        RgNpcFallback.warnMissing(wanted);
        return animatable.isMale() ? SaiyanFallbackRender.MALE_GEO : SaiyanFallbackRender.FEMALE_GEO;
    }

    // the rgnpc geo this defender WANTS, before any presence check: the same location getModelResource tests, so isFallback
    // recomputes exactly the model's own decision. Null if the id will not form a valid location.
    private static ResourceLocation wantedGeo(PlanetGarrisonDefenderEntity animatable)
    {
        String id = animatable.getModelId();
        if (!RgNpcModels.isValidId(id))
        {
            id = RgNpcModels.DEFAULT_ID;
        }
        return build(GEO_PREFIX, RgNpcModels.geoId(id), GEO_SUFFIX);
    }

    /**
     * True when this defender is drawing the generated-saiyan stand-in rather than its own rgnpc model, i.e. its wanted
     * geo is not installed. Recomputes the SAME check {@link #getModelResource} used, so the saiyan layer stack can ask
     * whether to draw (it must stay inert when the real model is present).
     */
    public static boolean isFallback(PlanetGarrisonDefenderEntity animatable)
    {
        return !RgNpcFallback.present(wantedGeo(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(PlanetGarrisonDefenderEntity animatable)
    {
        // falling back to the generated saiyan: the base body is drawn blank and the layer stack paints it, so the base
        // texture must be DMZ's null.png here, never the rgnpc skin or Steve.
        if (isFallback(animatable))
        {
            return SaiyanFallbackRender.BLANK_TEXTURE;
        }
        String modelId = RgNpcModels.isValidId(animatable.getModelId())
                ? animatable.getModelId() : RgNpcModels.DEFAULT_ID;
        ResourceLocation loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(modelId), TEX_SUFFIX);
        if (loc == null)
        {
            loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(RgNpcModels.DEFAULT_ID), TEX_SUFFIX);
        }
        // Guarded for the same reason, though a missing skin only mispaints where a missing geo crashes. Geo and
        // skin are checked independently, which is safe here because a character's two files travel in the same
        // streamed zip: in practice they are present together or absent together.
        return RgNpcFallback.presentOrDefault(loc, RgNpcFallback.TEXTURE);
    }

    @Override
    public ResourceLocation getAnimationResource(PlanetGarrisonDefenderEntity animatable)
    {
        return ANIM_SAGA_BASE;
    }

    /**
     * Drive the head bone from the entity's look, matching DragonMineZ's own {@code DBSagaModel} so the fighter tracks
     * its target the way a real saga entity does. Null-guarded: a geo without a head bone simply skips this.
     */
    @Override
    public void setCustomAnimations(PlanetGarrisonDefenderEntity animatable, long instanceId,
                                    AnimationState<PlanetGarrisonDefenderEntity> animationState)
    {
        CoreGeoBone head = getAnimationProcessor().getBone("head");
        if (head != null && animationState != null)
        {
            EntityModelData data = animationState.getData(DataTickets.ENTITY_MODEL_DATA);
            if (data != null)
            {
                head.setRotX(data.headPitch() * ((float) Math.PI / 180F));
                head.setRotY(data.netHeadYaw() * ((float) Math.PI / 180F));
            }
        }
    }

    // build a shuruisutilities resource location from a synced id, sanitising and validating it. Returns null (never
    // throws) if the id cannot form a valid location, warning once per distinct bad raw value.
    private static ResourceLocation build(String prefix, String rawId, String suffix)
    {
        String id = RgNpcModels.sanitize(rawId);
        if (id.isEmpty())
        {
            warnOnce(rawId);
            return null;
        }
        ResourceLocation loc = ResourceLocation.tryParse(ShuruisUtilities.MODID + ":" + prefix + id + suffix);
        if (loc == null)
        {
            warnOnce(rawId);
        }
        return loc;
    }

    private static void warnOnce(String rawId)
    {
        String key = rawId == null ? "<null>" : rawId;
        if (WARNED.add(key))
        {
            LOGGER.warn("[garrison-defender] ignoring invalid model/texture id \"{}\"; falling back to a default", key);
        }
    }
}
