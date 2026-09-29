package net.shurui.shuruisutilities.block.client;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.shurui.shuruisutilities.block.EndPortalBlockEntity;
import net.shurui.shuruisutilities.block.EndPortalPlaneBlock;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;

/**
 * Renders the vertical colored end portal with the real end-portal starfield ({@link RenderType#endPortal()}),
 * mirroring vanilla {@code TheEndPortalRenderer} but drawing the two big <b>vertical</b> faces of the plane
 * (which side depends on the block's axis) instead of the top of a flat block. Each face is drawn with both
 * windings so it shows from either side.
 */
public class EndPortalPlaneRenderer implements BlockEntityRenderer<EndPortalBlockEntity>
{
    public EndPortalPlaneRenderer(BlockEntityRendererProvider.Context context) {}

    @Override
    public void render(EndPortalBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffer, int light,
            int overlay)
    {
        Matrix4f m = pose.last().pose();
        VertexConsumer vc = buffer.getBuffer(RenderType.endPortal());
        Direction.Axis axis = be.getBlockState().hasProperty(EndPortalPlaneBlock.AXIS)
                ? be.getBlockState().getValue(EndPortalPlaneBlock.AXIS)
                : Direction.Axis.X;

        if (axis == Direction.Axis.Z)
        {
            // plane thin along X, big faces at x=0 (west) and x=1 (east)
            face(vc, m, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0);
            face(vc, m, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1);
        }
        else
        {
            // plane thin along Z, big faces at z=0 (north) and z=1 (south)
            face(vc, m, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0);
            face(vc, m, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1);
        }
    }

    /** Draws a quad and its reverse (so it renders regardless of face-culling / view side). */
    private static void face(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
            float cx, float cy, float cz, float dx, float dy, float dz)
    {
        vc.vertex(m, ax, ay, az).endVertex();
        vc.vertex(m, bx, by, bz).endVertex();
        vc.vertex(m, cx, cy, cz).endVertex();
        vc.vertex(m, dx, dy, dz).endVertex();
        vc.vertex(m, dx, dy, dz).endVertex();
        vc.vertex(m, cx, cy, cz).endVertex();
        vc.vertex(m, bx, by, bz).endVertex();
        vc.vertex(m, ax, ay, az).endVertex();
    }
}
