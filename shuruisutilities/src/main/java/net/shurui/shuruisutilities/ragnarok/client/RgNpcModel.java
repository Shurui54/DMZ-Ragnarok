package net.shurui.shuruisutilities.ragnarok.client;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.shurui.shuruisutilities.client.saiyan.SaiyanFallbackRender;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.ragnarok.RgNpcEntity;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import org.slf4j.Logger;
import software.bernie.geckolib.model.GeoModel;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * GeoModel for {@link RgNpcEntity}. Resolves the geo, texture and animation library from the entity's synced
 * ids at runtime, pointing into the installed {@code shuruisutilities:geo/entity/ragnarok/<geoId>.geo.json},
 * {@code shuruisutilities:textures/entity/ragnarok/<texture>.png} and DMZ's animation library (loaded by
 * resource location only, never classloading DMZ code). The synced model id is an ENTRY id, not a geo name; the
 * geo is resolved through {@link RgNpcModels#geoId(String)} so a variant can reuse another entry's geo.
 *
 * <p>Resolution is defensive: an unknown synced model id falls back to {@link RgNpcModels#DEFAULT_ID}, and an
 * unknown texture id falls back to that model's default texture, so a bad sync renders a known good model rather
 * than crashing the client.
 */
public class RgNpcModel extends GeoModel<RgNpcEntity> {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String GEO_PREFIX = "geo/entity/ragnarok/";
    private static final String GEO_SUFFIX = ".geo.json";
    private static final String TEX_PREFIX = "textures/entity/ragnarok/";
    private static final String TEX_SUFFIX = ".png";

    /** A geo name that is deliberately never bundled: a removed/unknown id resolves here so the saiyan draws. */
    private static final String ABSENT_GEO = "rgnpc_absent";

    // remembers which bad values we have already warned about so a broken sync logs once, not every frame.
    // getTextureResource / getModelResource run per entity per frame, so unbounded logging would flood the log.
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    // DMZ's saga animation library. Bones (root/waist/head/body/left_arm/right_arm/left_leg/right_leg) match the
    // rig the models were retargeted onto. Verified to contain the "idle" and "walk" clips the entity plays.
    private static final ResourceLocation ANIM_SAGA_BASE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    @Override
    public ResourceLocation getModelResource(RgNpcEntity animatable) {
        // an entry id is not a geo name any more: a variant loads a shared geo. validate the entry, degrade an
        // unknown one to the default, then resolve its geo id. the geo id names a file in the streamed pack.
        ResourceLocation wanted = wantedGeo(animatable);
        if (RgNpcFallback.present(wanted)) {
            return wanted;
        }
        // the model is not installed on this client (the normal case on a server without the streamed pack): draw a
        // generated DragonMineZ saiyan instead of a plain Steve. Borrow DMZ's race geo chosen by the UUID-rolled gender;
        // the blank null.png texture and the saiyan layer stack paint every visible pixel (see getTextureResource and
        // RgNpcRenderer). Warn once so the miss is visible in the log without flooding it.
        RgNpcFallback.warnMissing(wanted);
        return animatable.isMale() ? SaiyanFallbackRender.MALE_GEO : SaiyanFallbackRender.FEMALE_GEO;
    }

    // the rgnpc geo this entity WANTS, before any presence check: the same location getModelResource tests, so isFallback
    // recomputes exactly the model's own decision. Null if the id will not form a valid location.
    private static ResourceLocation wantedGeo(RgNpcEntity animatable) {
        String id = animatable.getModelId();
        String canon = RgNpcModels.resolveId(RgNpcModels.sanitize(id));
        if (canon == null) {
            // A blank / unset id is the default model; a NON-blank id that resolves to nothing is a character that
            // was removed when the streamed set was replaced by the bundled saga models, so it draws the generated
            // saiyan (a non-present location makes RgNpcFallback report a miss) rather than a wrong character.
            if (id == null || id.isBlank()) {
                canon = RgNpcModels.DEFAULT_ID;
            } else {
                return build(GEO_PREFIX, ABSENT_GEO, GEO_SUFFIX);
            }
        }
        return build(GEO_PREFIX, RgNpcModels.geoId(canon), GEO_SUFFIX);
    }

    /**
     * True when this entity is drawing the generated-saiyan stand-in rather than its own rgnpc model, i.e. its wanted geo
     * is not installed. Recomputes the SAME check {@link #getModelResource} used, so the saiyan layer stack can ask
     * whether to draw (it must stay inert when the real model is present). Cheap: a map lookup on the common installed path.
     */
    public static boolean isFallback(RgNpcEntity animatable) {
        return !RgNpcFallback.present(wantedGeo(animatable));
    }

    @Override
    public ResourceLocation getTextureResource(RgNpcEntity animatable) {
        // falling back to the generated saiyan: the base body is drawn blank and the layer stack paints it, exactly as the
        // planet garrison saiyan is drawn, so the base texture must be DMZ's null.png here, never the rgnpc skin or Steve.
        if (isFallback(animatable)) {
            return SaiyanFallbackRender.BLANK_TEXTURE;
        }
        // resolve the valid model first so a bad texture can fall back to that model's known good default.
        String modelId = RgNpcModels.isValidId(animatable.getModelId())
                ? animatable.getModelId() : RgNpcModels.DEFAULT_ID;
        String tex = animatable.getTextureId();
        if (tex == null || tex.isBlank()) {
            tex = RgNpcModels.defaultTexture(modelId);
        }
        // sanitise the (possibly user supplied) texture id; if it still will not form a valid location, fall
        // back to the model default, and if even that fails, to the DEFAULT_ID texture.
        ResourceLocation loc = build(TEX_PREFIX, tex, TEX_SUFFIX);
        if (loc == null) {
            loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(modelId), TEX_SUFFIX);
        }
        if (loc == null) {
            loc = build(TEX_PREFIX, RgNpcModels.defaultTexture(RgNpcModels.DEFAULT_ID), TEX_SUFFIX);
        }
        return RgNpcFallback.presentOrDefault(loc, RgNpcFallback.TEXTURE);
    }

    /**
     * Build a shuruisutilities resource location from a synced id, sanitising it first and validating the
     * result with {@link ResourceLocation#tryParse}. Returns null (never throws) if the id cannot form a valid
     * location, warning once per distinct bad raw value so the render loop cannot spam the log.
     */
    private static ResourceLocation build(String prefix, String rawId, String suffix) {
        String id = RgNpcModels.sanitize(rawId);
        if (id.isEmpty()) {
            warnOnce(rawId);
            return null;
        }
        String path = prefix + id + suffix;
        ResourceLocation loc = ResourceLocation.tryParse(ShuruisUtilities.MODID + ":" + path);
        if (loc == null) {
            warnOnce(rawId);
        }
        return loc;
    }

    private static void warnOnce(String rawId) {
        String key = rawId == null ? "<null>" : rawId;
        if (WARNED.add(key)) {
            LOGGER.warn("[rgnpc] ignoring invalid model/texture id \"{}\"; falling back to a default", key);
        }
    }

    @Override
    public ResourceLocation getAnimationResource(RgNpcEntity animatable) {
        // Single supported set today; keyed off the synced ANIM_SET so per model sets can be added later without
        // changing the entity. Any unrecognized value degrades to saga_base rather than a missing resource.
        return ANIM_SAGA_BASE;
    }
}
