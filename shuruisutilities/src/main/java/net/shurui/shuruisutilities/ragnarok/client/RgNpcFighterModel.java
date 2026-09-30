package net.shurui.shuruisutilities.ragnarok.client;

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

import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.ragnarok.RgNpcFighterEntity;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;

/**
 * GeoModel for {@link RgNpcFighterEntity}: the piece that lets a DragonMineZ saga entity wear an arbitrary
 * ragnarok model.
 *
 * <p>It resolves the geo and texture from the entity's synced entry id through {@link RgNpcModels}, exactly as
 * {@link RgNpcModel} does for the display NPC, but serves DragonMineZ's own {@code saga_base.animation.json}
 * instead of the display animations. THAT is where the animations come from: the rgnpc geos were retargeted onto
 * the saga rig, so the combat controllers the entity inherits from {@code DBSagasEntity} find their clips and
 * drive these bones with no per-model work.
 *
 * <p>Resolution is defensive. An unknown synced id falls back to {@link RgNpcModels#DEFAULT_ID} and a bad texture
 * to that model's default, so a stale sync draws a known-good character instead of killing the render thread. A
 * distinct bad value is warned about ONCE, never per frame.
 */
public class RgNpcFighterModel extends GeoModel<RgNpcFighterEntity>
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String GEO_PREFIX = "geo/entity/ragnarok/";
    private static final String GEO_SUFFIX = ".geo.json";
    private static final String TEX_PREFIX = "textures/entity/ragnarok/";
    private static final String TEX_SUFFIX = ".png";

    /** A geo name that is deliberately never bundled: a removed/unknown id resolves here so the saiyan draws. */
    private static final String ABSENT_GEO = "rgnpc_absent";

    /** Bad raw values already reported; these methods run per entity per frame. */
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    /** DragonMineZ's saga animation library, the same file its own saga fighters use. */
    private static final ResourceLocation ANIM_SAGA_BASE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    @Override
    public ResourceLocation getModelResource(RgNpcFighterEntity animatable)
    {
        ResourceLocation wanted = wantedGeo(animatable);
        if (RgNpcFallback.present(wanted))
            return wanted;
        // the model is not installed on this client (the normal case on a server without the streamed pack): draw a
        // generated DragonMineZ saiyan instead of a plain Steve. Borrow DMZ's race geo chosen by the UUID-rolled gender;
        // the blank null.png texture and the saiyan layer stack paint every visible pixel (see getTextureResource and the
        // fallback layer on RgNpcFighterRenderer). Warn once so the miss is visible in the log without flooding it.
        RgNpcFallback.warnMissing(wanted);
        return animatable.isMale() ? SaiyanFallbackRender.MALE_GEO : SaiyanFallbackRender.FEMALE_GEO;
    }

    // the rgnpc geo this fighter WANTS, before any presence check: the same location getModelResource tests, so isFallback
    // recomputes exactly the model's own decision. Null if the id will not form a valid location.
    private static ResourceLocation wantedGeo(RgNpcFighterEntity animatable)
    {
        String id = animatable.getModelId();
        String canon = RgNpcModels.resolveId(RgNpcModels.sanitize(id));
        if (canon == null)
        {
            // Blank / unset -> default model; a non-blank id that resolves to nothing was a character removed when
            // the streamed set was replaced by the bundled saga models, so draw the generated saiyan.
            if (id == null || id.isBlank())
                canon = RgNpcModels.DEFAULT_ID;
            else
                return build(GEO_PREFIX, ABSENT_GEO, GEO_SUFFIX);
        }
        return build(GEO_PREFIX, RgNpcModels.geoId(canon), GEO_SUFFIX);
    }

    /**
     * True when this fighter is drawing the generated-saiyan stand-in rather than its own rgnpc model, i.e. its wanted geo
     * is not installed. Recomputes the SAME check {@link #getModelResource} used, so the saiyan layer stack can ask
     * whether to draw (it must stay inert when the real model is present).
     */
    public static boolean isFallback(RgNpcFighterEntity animatable)
    {
        return !RgNpcFallback.present(wantedGeo(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(RgNpcFighterEntity animatable)
    {
        // falling back to the generated saiyan: the base body is drawn blank and the layer stack paints it, so the base
        // texture must be DMZ's null.png here, never the rgnpc skin or Steve.
        if (isFallback(animatable))
            return SaiyanFallbackRender.BLANK_TEXTURE;
        String modelId = RgNpcModels.isValidId(animatable.getModelId())
                ? animatable.getModelId() : RgNpcModels.DEFAULT_ID;
        ResourceLocation loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(modelId), TEX_SUFFIX);
        if (loc == null)
            loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(RgNpcModels.DEFAULT_ID), TEX_SUFFIX);
        return RgNpcFallback.presentOrDefault(loc, RgNpcFallback.TEXTURE);
    }

    @Override
    public ResourceLocation getAnimationResource(RgNpcFighterEntity animatable)
    {
        return ANIM_SAGA_BASE;
    }

    /**
     * Drive the head bone from the entity's look, matching DragonMineZ's own saga model so the fighter tracks its
     * target the way a real saga entity does. Null-guarded: a geo with no head bone simply skips it.
     */
    @Override
    public void setCustomAnimations(RgNpcFighterEntity animatable, long instanceId,
                                    AnimationState<RgNpcFighterEntity> animationState)
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

    /** Build an SU resource location from a synced id, sanitising and validating; null (never throws) on failure. */
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
            warnOnce(rawId);
        return loc;
    }

    private static void warnOnce(String rawId)
    {
        String key = rawId == null ? "<null>" : rawId;
        if (WARNED.add(key))
            LOGGER.warn("[rgnpc-fighter] ignoring invalid model/texture id \"{}\"; falling back to a default", key);
    }
}
