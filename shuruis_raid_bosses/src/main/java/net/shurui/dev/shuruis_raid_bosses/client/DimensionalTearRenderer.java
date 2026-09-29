package net.shurui.dev.shuruis_raid_bosses.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import net.shurui.dev.shuruis_raid_bosses.entity.DimensionalTear;

/**
 * Draws an open dimensional tear: one camera-facing quad, three blocks square, floating half a block off the
 * ground, playing the tear's 24-frame swirl in a colour of its own.
 *
 * <h2>Why the animation is done here rather than by a texture .mcmeta</h2>
 * A {@code .mcmeta} animation only ticks for sprites in an ATLAS (blocks, items, particles). An entity texture
 * is a standalone {@code SimpleTexture} that ignores the animation block and would draw frame 0 forever. So the
 * frames ship as one vertical strip and the frame is chosen here by offsetting V, keeping the source gif's pace:
 * {@link #FRAME_TICKS} is 2.4 ticks, the 120ms per frame it was authored at.
 *
 * <h2>Colour</h2>
 * The sheet is greyscale and each tear multiplies it by one colour from its own uuid, so no two open tears look
 * alike and a tear keeps its colour for as long as it is open, identically on every client.
 *
 * <h2>Billboarding</h2>
 * The quad faces the camera on both axes so the swirl reads as a hole from every angle. Drawn at full brightness
 * and does not write depth for the transparent parts, because a soft-edged disc in a dark field must still be
 * seen.
 */
public class DimensionalTearRenderer extends EntityRenderer<DimensionalTear> {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation("dmz_ragnarok", "textures/entity/rift/dimensional_tear.png");

    /**
     * The carved jack-o'-lantern face drawn glowing in the centre of a {@code "halloween"} tear. A standalone
     * square (not part of the swirl strip), on transparent ground, drawn full-bright so the face reads as lit
     * from within whatever the local light.
     */
    private static final ResourceLocation FACE_TEXTURE =
            new ResourceLocation("dmz_ragnarok", "textures/entity/rift/haunted_tear_face.png");

    /** The two-tone swirl colours of the {@code "halloween"} look: pumpkin orange and deep witch purple. */
    private static final int HALLOWEEN_ORANGE = 0xFF7518;
    private static final int HALLOWEEN_PURPLE = 0x8B2FB0;

    /** The face is drawn this fraction of the swirl's side, centred, so the swirl frames it. */
    private static final float FACE_SCALE = 0.55F;

    /** Frames in the strip, and the ticks each is held for (120ms at 20 ticks per second). */
    private static final int FRAMES = 24;
    private static final float FRAME_TICKS = 2.4F;

    /** Side length in blocks, and how far the BOTTOM edge floats above the entity's feet. */
    private static final float SIZE = 3.0F;
    private static final float GROUND_OFFSET = 0.5F;

    /**
     * How far the quad is pushed toward the viewer, in blocks. Must exceed the waypoint beam's glow radius
     * (0.25), how far the beam column reaches out of the shared centre toward the camera.
     */
    private static final float FRONT_OFFSET = 0.35F;

    /**
     * The colours a tear can be, one picked per tear. The art is greyscale and the quad's colour multiplies
     * through it. Saturated on purpose: a multiply loses saturation, so a dull tint comes out dirty grey.
     */
    private static final int[] TINTS = {
            0xB65CFF, // violet
            0xFF5CE1, // magenta
            0x5CE1FF, // cyan
            0x38F2B0, // teal
            0x5C8CFF, // deep blue
            0x8CFF5C, // acid green
            0xFFC24D, // amber
            0xFF6B4D, // ember
            0xFF4D6B, // crimson
            0xE0E0FF  // pale spirit
    };

    public DimensionalTearRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(DimensionalTear entity) {
        return TEXTURE;
    }

    @Override
    public void render(DimensionalTear entity, float entityYaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        pose.pushPose();
        // Entity sits ON the ground: lift by half a block plus half the quad so the art's BOTTOM edge floats
        // half a block clear.
        pose.translate(0.0D, GROUND_OFFSET + SIZE / 2.0F, 0.0D);
        // Step TOWARD THE VIEWER to put the tear in front of its own beacon. The waypoint beam is a column on
        // the same point, so its near half occupies the space between viewer and a flat billboard through that
        // centre; depth sorting cannot fix that because the beam really is in front. A third of a block forward
        // puts the whole column (glow radius 0.25) behind the plane from every angle. Invisible at this size.
        Vec3 camera = this.entityRenderDispatcher.camera.getPosition();
        Vec3 centre = new Vec3(entity.getX(), entity.getY() + GROUND_OFFSET + SIZE / 2.0F, entity.getZ());
        Vec3 toCamera = camera.subtract(centre);
        if (toCamera.lengthSqr() > 1.0E-4D) {
            Vec3 step = toCamera.normalize().scale(FRONT_OFFSET);
            pose.translate(step.x, step.y, step.z);
        }
        // Face the camera on both axes, using the render pass's shared camera orientation so the tear turns
        // with the view, not the entity's rotation.
        pose.mulPose(this.entityRenderDispatcher.cameraOrientation());
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));

        float half = SIZE / 2.0F;
        int frame = currentFrame(entity, partialTick);
        float v0 = frame / (float) FRAMES;
        float v1 = (frame + 1) / (float) FRAMES;

        var matrix = pose.last().pose();
        var normal = pose.last().normal();
        // Full bright, ignoring the light at the tear's feet: a hole into somewhere else reads wrong dimmed by
        // the local sky or a cave.
        int light = net.minecraft.client.renderer.LightTexture.FULL_BRIGHT;

        if ("halloween".equalsIgnoreCase(entity.look())) {
            // Two swirl passes at opposite phases give a live orange/purple swirl instead of one flat tint, then
            // the carved face glows on top.
            swirl(buffers, matrix, normal, half, v0, v1, light, HALLOWEEN_ORANGE);
            int frame2 = (frame + FRAMES / 2) % FRAMES;
            swirl(buffers, matrix, normal, half, frame2 / (float) FRAMES, (frame2 + 1) / (float) FRAMES,
                    light, HALLOWEEN_PURPLE);
            face(buffers, matrix, normal, half * FACE_SCALE, light);
        } else {
            swirl(buffers, matrix, normal, half, v0, v1, light, tintFor(entity));
        }

        pose.popPose();
        // Default name-tag pass still runs: the label is the whole point of the tear.
        super.render(entity, entityYaw, partialTick, pose, buffers, packedLight);
    }

    /**
     * One swirl quad, the whole strip frame [v0,v1] tinted by {@code rgb}. entityTranslucent, NOT the emissive
     * variant (that is about the BEACON): the beam draws later, so front-vs-behind is depth. This type writes
     * depth and discards fragments under 0.1 alpha, so the swirl occludes the beam while the transparent corners
     * let it through, and the {@code light} passed in is already full-bright so no brightness is lost.
     */
    private static void swirl(MultiBufferSource buffers, org.joml.Matrix4f matrix, org.joml.Matrix3f normal,
                              float half, float v0, float v1, int light, int rgb) {
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucent(TEXTURE));
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        quad(buffer, matrix, normal, -half, -half, 0.0F, v1, light, r, g, b);
        quad(buffer, matrix, normal, half, -half, 1.0F, v1, light, r, g, b);
        quad(buffer, matrix, normal, half, half, 1.0F, v0, light, r, g, b);
        quad(buffer, matrix, normal, -half, half, 0.0F, v0, light, r, g, b);
    }

    /**
     * The carved jack-o'-lantern face, a smaller centred quad on its own texture, drawn full-bright so it glows
     * from within the swirl. Untinted (the texture is already orange), and slightly forward is unnecessary because
     * it shares the swirl's plane and its transparent ground lets the swirl show around it.
     */
    private static void face(MultiBufferSource buffers, org.joml.Matrix4f matrix, org.joml.Matrix3f normal,
                             float half, int light) {
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityTranslucent(FACE_TEXTURE));
        quad(buffer, matrix, normal, -half, -half, 0.0F, 1.0F, light, 255, 255, 255);
        quad(buffer, matrix, normal, half, -half, 1.0F, 1.0F, light, 255, 255, 255);
        quad(buffer, matrix, normal, half, half, 1.0F, 0.0F, light, 255, 255, 255);
        quad(buffer, matrix, normal, -half, half, 0.0F, 0.0F, light, 255, 255, 255);
    }

    private static void quad(VertexConsumer buffer, org.joml.Matrix4f matrix, org.joml.Matrix3f normal,
                             float x, float y, float u, float v, int packedLight, int r, int g, int b) {
        buffer.vertex(matrix, x, y, 0.0F)
                .color(r, g, b, 255)
                .uv(u, v)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(packedLight)
                .normal(normal, 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    /**
     * The colour this tear wears, fixed for life and identical on every client. Taken from the server-synced
     * UUID, not rolled locally (a local roll would differ per viewer and re-rolling per frame would strobe).
     */
    private static int tintFor(DimensionalTear entity) {
        int hash = entity.getUUID().hashCode();
        return TINTS[Mth.positiveModulo(hash, TINTS.length)];
    }

    /**
     * Which strip frame is showing. Driven by the world clock plus the entity id (so two tears do not pulse in
     * lockstep) and by {@code partialTick} so the strip advances smoothly, not in client-tick steps.
     */
    private static int currentFrame(DimensionalTear entity, float partialTick) {
        float time = entity.tickCount + partialTick + (entity.getId() * 7 % FRAMES) * FRAME_TICKS;
        return Mth.positiveModulo((int) (time / FRAME_TICKS), FRAMES);
    }

    @Override
    protected boolean shouldShowName(DimensionalTear entity) {
        // Always, not just when looked at: the label names the fight on the other side and a player needs it from afar.
        return entity.hasCustomName();
    }

    @Override
    public Vec3 getRenderOffset(DimensionalTear entity, float partialTick) {
        return Vec3.ZERO;
    }
}
