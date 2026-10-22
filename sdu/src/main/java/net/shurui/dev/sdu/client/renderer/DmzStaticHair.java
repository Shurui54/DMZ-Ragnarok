package net.shurui.dev.sdu.client.renderer;

import com.dragonminez.client.render.hair.HairEntityState;
import com.dragonminez.client.render.hair.HairMeshBuilder;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairStyleSlot;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws a DragonMineZ hair code with no physics, for entities that are not DMZ players (our NPCs, CNPC fighters and the
 * planet saiyans).
 *
 * <p>DragonMineZ 2.2 removed {@code HairRenderer.render(...)}, the static entry point all three layers used. Its hair
 * is now drawn by {@link HairMeshBuilder#emit} from a {@link HairEntityState} (the strand poses), which is exactly
 * what DMZ's own {@code DMZHairLayer} does for a player when hair physics is off. The pose stack must already be
 * translated to the head bone's pivot, as it was for the old call. Render thread only: the state and mesh builder are
 * shared scratch objects.
 */
public final class DmzStaticHair {
    private static final ResourceLocation HAIR_TEXTURE =
            new ResourceLocation("dragonminez", "textures/entity/races/hair.png");
    private static final HairMeshBuilder MESH = new HairMeshBuilder();
    private static final HairEntityState STATE = new HairEntityState();

    private DmzStaticHair() {}

    /**
     * @param rgb   the hair colour (0..1 per channel), used for every strand that has no colour of its own, or for all
     *              of them when {@code force} is set (the old HairRenderer arguments rgbFrom/rgbTo and forceFrom/forceTo)
     */
    public static void render(PoseStack poseStack, MultiBufferSource bufferSource, CustomHair hair, float[] rgb,
                              boolean force, int packedLight, int packedOverlay) {
        if (hair == null || hair.isEmpty()) return;
        STATE.updatePoses(hair, hair, 0f, HairStyleSlot.BASE, HairStyleSlot.BASE);
        int color = toRgb(rgb);
        MESH.emit(bufferSource.getBuffer(RenderType.entityCutoutNoCull(HAIR_TEXTURE)),
                poseStack.last().pose(), poseStack.last().normal(), STATE, false,
                color, color, force, force, packedLight, packedOverlay, 1.0f, true, null, null, null);
    }

    private static int toRgb(float[] rgb) {
        if (rgb == null || rgb.length < 3) return 0;
        int r = Math.round(Math.max(0f, Math.min(1f, rgb[0])) * 255f);
        int g = Math.round(Math.max(0f, Math.min(1f, rgb[1])) * 255f);
        int b = Math.round(Math.max(0f, Math.min(1f, rgb[2])) * 255f);
        return (r << 16) | (g << 8) | b;
    }
}
