package net.shurui.shuruisutilities.client.saiyan;

import com.dragonminez.client.render.hair.HairRenderer;
import com.dragonminez.client.util.ColorUtils;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairManager;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.renderer.GeoRenderer;
import software.bernie.geckolib.renderer.layer.GeoRenderLayer;
import software.bernie.geckolib.util.RenderUtils;

import net.shurui.shuruisutilities.saiyan.SaiyanAppearance;

/**
 * SU-owned hair layer for the garrison saiyan. It reuses DragonMineZ's static procedural-hair helpers exactly as the
 * task permits: {@link HairManager#getPresetHair} builds the preset {@link CustomHair} strand set for the synced hair
 * id, and {@link HairRenderer#render} draws the strand cubes. Both are player-agnostic when the character/stats/player
 * arguments are null (the physics/animation block is skipped, giving static hair), so they work for a mob. Hair is drawn
 * with DIRECT RGB (not a texture multiply), so a pure-black hair colour renders truly black, which is why generic
 * garrison saiyans get {@code #000000} hair.
 *
 * <p>Named saiyans may instead carry a DragonMineZ HAIR CODE string on their {@link SaiyanAppearance.NamedSaiyan} row.
 * When that code is set, this layer builds their hair from it via {@link HairManager#fromCode} instead of the preset;
 * when it is empty, or when it will not parse, the layer falls back to the named saiyan's synced preset id so nothing
 * ever looks broken. Generic (non-named) saiyans always use the preset path with their rolled hair id, unchanged.
 *
 * <p>Anchored on the head bone via {@link #renderForBone}, translated to the bone pivot, mirroring DMZ's own
 * {@code DMZHairLayer}. Wrapped so a malformed hair code, or any DMZ hair API drift, degrades to the preset/no-hair
 * fallback and logs once rather than crashing the render thread every frame.
 */
public class PlanetSaiyanHairLayer<T extends Mob & GeoEntity & SaiyanAppearance> extends GeoRenderLayer<T>
{
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean WARNED = false;
    private static boolean CODE_WARNED = false;

    public PlanetSaiyanHairLayer(GeoRenderer<T> renderer)
    {
        super(renderer);
    }

    @Override
    public void renderForBone(PoseStack poseStack, T animatable, GeoBone bone,
                              RenderType renderType, MultiBufferSource bufferSource, VertexConsumer buffer,
                              float partialTick, int packedLight, int packedOverlay)
    {
        if (animatable == null || !bone.getName().contentEquals("head"))
        {
            return;
        }
        try
        {
            int hairId = animatable.getHairId();
            // hair id 0 is the bald sentinel (males only, see SaiyanAppearance.roll): draw nothing.
            if (hairId <= SaiyanAppearance.HAIR_ID_BALD)
            {
                return;
            }
            int presetCount = HairManager.getPresetCount();
            if (presetCount > 0)
            {
                hairId = Math.min(hairId, presetCount);
            }
            String hairHex = toHex(animatable.getHairColor());
            CustomHair hair = buildHair(animatable, hairId, hairHex);
            if (hair == null || hair.isEmpty())
            {
                return;
            }
            float[] rgb = ColorUtils.rgbIntToFloat(animatable.getHairColor());

            // The pop MUST be in a finally, for the same reason as the scouter layer: the catch below is here so a DMZ
            // change degrades to no hair rather than crashing, but an exception thrown after the push would leave the
            // pose stack unbalanced and Minecraft throws "Pose stack not empty" at the end of the frame, killing the
            // client somewhere unrelated. A swallowed exception must not leak render state.
            poseStack.pushPose();
            try
            {
                RenderUtils.translateToPivotPoint(poseStack, bone);
                // hairTo == hairFrom, transitionFactor 0 -> static single hair; character/stats/player null -> no physics;
                // forceColor true both sides -> our flat colour overrides any per-strand preset colour, so black stays black.
                HairRenderer.render(poseStack, bufferSource, hair, hair, 0.0F, null, null, null, rgb, rgb,
                        true, true, partialTick, packedLight, packedOverlay, 1.0F, 0.0F, 0.0F);
            }
            finally
            {
                poseStack.popPose();
            }
        }
        catch (Throwable t)
        {
            if (!WARNED)
            {
                WARNED = true;
                LOGGER.warn("[garrison-saiyan] DragonMineZ hair API drift; garrison saiyans render without hair", t);
            }
        }
    }

    // choose the hair strand set to draw: a named saiyan carrying a DMZ hair code builds its hair from that code; a generic
    // saiyan, a named saiyan with no code yet, or a named saiyan whose code will not parse, falls back to the preset for
    // the synced hair id. This is the ONLY place a code is consulted, so supplying one later is purely a data edit on the
    // named row.
    private CustomHair buildHair(T animatable, int presetId, String hairHex)
    {
        SaiyanAppearance.NamedSaiyan named = animatable.getNamed();
        String code = named != null ? named.hairCode() : null;
        if (code != null && !code.isEmpty())
        {
            CustomHair fromCode = tryFromCode(code, hairHex);
            if (fromCode != null && !fromCode.isEmpty())
            {
                return fromCode;
            }
            // a code was supplied but produced nothing usable (unknown prefix, corrupt blob, or no strands): warn once and
            // fall through to the preset so a bad code degrades to the old look rather than leaving the head bald.
            if (!CODE_WARNED)
            {
                CODE_WARNED = true;
                LOGGER.warn("[garrison-saiyan] named saiyan hair code did not parse; using the preset fallback instead. code prefix: {}",
                        codePrefix(code));
            }
        }
        return HairManager.getPresetHair(presetId, hairHex);
    }

    // parse a DMZ hair code into a strand set, applying our flat hair colour. HairManager.fromCode already swallows a bad
    // code and returns null rather than throwing, but this stays wrapped so any future DMZ drift here still degrades to the
    // preset on the render thread rather than crashing it.
    private static CustomHair tryFromCode(String code, String hairHex)
    {
        try
        {
            CustomHair hair = HairManager.fromCode(code);
            if (hair != null && hairHex != null && !hairHex.isEmpty())
            {
                // the render pass forces our flat colour anyway, but set it here too so the strand set is self-consistent.
                hair.setGlobalColor(hairHex);
            }
            return hair;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    // a short, log-safe leading slice of a hair code (they can be very long); enough to tell which code failed without
    // spamming the whole blob into the log.
    private static String codePrefix(String code)
    {
        return code.length() <= 12 ? code : code.substring(0, 12) + "...";
    }

    private static String toHex(int rgb)
    {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }
}
