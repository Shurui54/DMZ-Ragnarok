package net.shurui.shuruisutilities.model.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.dev.sdu.compat.dmz.DmzRaceGeoGuard;
import net.shurui.shuruisutilities.model.PacketModelSync;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.ragnarok.client.RgNpcFallback;

import software.bernie.geckolib.cache.GeckoLibCache;

/**
 * Client mirror of {@code ModelState}: which player is drawn as which model. Read by the DragonMineZ player-model
 * render redirect. A missing entry means "draw normally", so the common case is one null map lookup.
 *
 * <p>{@link #resolve} is the ONE place an override becomes render resources, and it is where the crash guard lives:
 * an override applies only when its geo really backs a file ({@link RgNpcFallback#present}, which also knows the
 * streamed rgnpc pack) AND GeckoLib baked it ({@link DmzRaceGeoGuard#bakedOrDefault} hands the same location back,
 * and the baked map holds it). Anything else (a model pack not streamed yet, a reload in progress) resolves to null
 * and the player is drawn exactly as without {@code /model}: DMZ's own geo, texture, animation and every layer. So a
 * /model can never hand GeckoLib a location it would throw on.
 */
@OnlyIn(Dist.CLIENT)
public final class ModelClientCache
{
    private static final Map<UUID, String> OVERRIDES = new ConcurrentHashMap<>();

    private ModelClientCache() {}

    public static void accept(PacketModelSync p)
    {
        switch (p.mode)
        {
            case PacketModelSync.MODE_SNAPSHOT:
                OVERRIDES.clear();
                OVERRIDES.putAll(p.overrides);
                break;
            case PacketModelSync.MODE_SINGLE:
                if (p.single != null && p.singleModel != null && !p.singleModel.isEmpty())
                    OVERRIDES.put(p.single, p.singleModel);
                break;
            case PacketModelSync.MODE_CLEAR:
                OVERRIDES.remove(p.single);
                break;
            default:
                break;
        }
    }

    /** The model override id for a player, or null. */
    public static String get(UUID id)
    {
        return id == null ? null : OVERRIDES.get(id);
    }

    public static boolean has(UUID id)
    {
        return id != null && OVERRIDES.containsKey(id);
    }

    public static void reset()
    {
        OVERRIDES.clear();
    }

    /** The render resources for one override: geo, texture and animation (null = keep DMZ's own animation). */
    public record Resolved(ResourceLocation geo, ResourceLocation texture, ResourceLocation animation) {}

    private static final String DMZ_PREFIX = "dmz:";
    // The rgnpc geos are retargeted onto DragonMineZ's saga rig; this is the library the rgnpc renderers use.
    private static final ResourceLocation SAGA_BASE_ANIM =
            new ResourceLocation("dragonminez", "animations/entity/sagas/saga_base.animation.json");

    /** This player's override as safe render resources, or null when there is none or it cannot be drawn yet. */
    public static Resolved resolve(UUID id)
    {
        String modelId = get(id);
        if (modelId == null)
            return null;
        try
        {
            ResourceLocation geo;
            ResourceLocation tex;
            ResourceLocation anim;
            if (modelId.startsWith(DMZ_PREFIX))
            {
                String path = modelId.substring(DMZ_PREFIX.length());
                geo = ResourceLocation.tryParse("dragonminez:geo/entity/" + path + ".geo.json");
                tex = ResourceLocation.tryParse("dragonminez:textures/entity/" + path + ".png");
                anim = null; // arbitrary DMZ geos are not on the saga rig: keep DMZ's own animation
            }
            else
            {
                geo = ResourceLocation.tryParse("dmz_ragnarok:"
                        + "geo/entity/ragnarok/" + RgNpcModels.sanitize(RgNpcModels.geoId(modelId)) + ".geo.json");
                tex = ResourceLocation.tryParse("dmz_ragnarok:"
                        + "textures/entity/ragnarok/" + RgNpcModels.sanitize(RgNpcModels.defaultTexture(modelId)) + ".png");
                anim = SAGA_BASE_ANIM;
            }
            if (geo == null || tex == null || !RgNpcFallback.present(geo))
                return null;
            if (!geo.equals(DmzRaceGeoGuard.bakedOrDefault(geo)) || !GeckoLibCache.getBakedModels().containsKey(geo))
                return null;
            return new Resolved(geo, RgNpcFallback.presentOrDefault(tex, RgNpcFallback.TEXTURE), anim);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
