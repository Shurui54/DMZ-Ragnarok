package net.shurui.dev.sdu.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.block.BarrierBlock;
import net.shurui.dev.sdu.block.BarrierBlockEntity;
import org.joml.Matrix4f;

/**
 * Client-side renderer for the {@link BarrierBlockEntity}: the barrier block itself is
 * {@link net.minecraft.world.level.block.RenderShape#INVISIBLE}, so the BER is the only in-world visual. It
 * draws a full 0..1 cube textured with an animated force-field sprite, visible to everyone.
 *
 * <p>Per-player gate: the local client player's live DMZ level/race is run through
 * {@link BarrierBlock#passes} (the same check the server collision gate uses). A player who currently clears
 * the gate sees the OPEN sprite; a player who does not sees the CLOSED sprite. This is evaluated per frame
 * against {@link Minecraft#player}, so two players looking at the same wall can see different states.
 *
 * <p>Sprites come from the BLOCK atlas so their 16-frame animation plays automatically; they are resolved
 * lazily and cached, invalidated on resource reload via {@link #invalidateSprites()}.
 */
public class BarrierBER implements BlockEntityRenderer<BarrierBlockEntity> {

    private static final ResourceLocation CLOSED_TEX = new ResourceLocation("dmz_ragnarok", "block/level_barrier");
    private static final ResourceLocation OPEN_TEX = new ResourceLocation("dmz_ragnarok", "block/level_barrier_open");

    // full-brightness so the force field glows regardless of ambient light
    private static final int FULL_LIGHT = 0xF000F0;
    // soft tint at ~72% alpha; the sprite art carries its own alpha, this only shades it
    private static final float R = 1.0F;
    private static final float G = 1.0F;
    private static final float B = 1.0F;
    private static final float A = 0.72F;

    // lazily resolved from the block atlas, cleared on resource reload
    private static TextureAtlasSprite closedSprite;
    private static TextureAtlasSprite openSprite;

    public BarrierBER(BlockEntityRendererProvider.Context ctx) {
    }

    /** Drop cached atlas sprites so they are re-resolved after a resource reload. */
    public static void invalidateSprites() {
        closedSprite = null;
        openSprite = null;
    }

    @Override
    public void render(BarrierBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }

        boolean open = BarrierBlock.passes(player, be.getRequiredLevel(), be.getRequiredRace());
        TextureAtlasSprite sprite = open ? openSprite() : closedSprite();

        // translucent so the sprite alpha blends into a force-field look; block-atlas backed so it animates
        VertexConsumer vc = buffers.getBuffer(RenderType.translucent());
        Matrix4f m = pose.last().pose();
        cube(vc, m, sprite, be, player, open);
    }

    private static TextureAtlasSprite closedSprite() {
        if (closedSprite == null) {
            closedSprite = sprite(CLOSED_TEX);
        }
        return closedSprite;
    }

    private static TextureAtlasSprite openSprite() {
        if (openSprite == null) {
            openSprite = sprite(OPEN_TEX);
        }
        return openSprite;
    }

    private static TextureAtlasSprite sprite(ResourceLocation loc) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(loc);
    }

    /**
     * Emit a full 0..1 cube as up to 6 quads (translucent render type is no-cull, so each quad shows from both
     * sides). A face shared with a face-adjacent barrier that reads the SAME open/closed state for this viewer
     * is dropped so a wall renders as one continuous field: without the drop the two coincident translucent
     * quads at the boundary double their alpha into a visible seam, turning a wall into a grid of cubes.
     */
    private static void cube(VertexConsumer vc, Matrix4f m, TextureAtlasSprite s,
                             BarrierBlockEntity be, Player player, boolean open) {
        Level level = be.getLevel();
        BlockPos pos = be.getBlockPos();
        // bottom (y=0) and top (y=1)
        if (drawFace(level, pos, player, open, Direction.DOWN)) {
            quad(vc, m, s, 0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1);
        }
        if (drawFace(level, pos, player, open, Direction.UP)) {
            quad(vc, m, s, 0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0);
        }
        // north (z=0) and south (z=1)
        if (drawFace(level, pos, player, open, Direction.NORTH)) {
            quad(vc, m, s, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, 0);
        }
        if (drawFace(level, pos, player, open, Direction.SOUTH)) {
            quad(vc, m, s, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1);
        }
        // west (x=0) and east (x=1)
        if (drawFace(level, pos, player, open, Direction.WEST)) {
            quad(vc, m, s, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0);
        }
        if (drawFace(level, pos, player, open, Direction.EAST)) {
            quad(vc, m, s, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1);
        }
    }

    // draw a face unless the neighbour toward dir is another barrier that resolves to the SAME open/closed
    // state for this viewer: that shared interior face is the seam, so cull it on both blocks. per-player and
    // per-gate on purpose: two adjacent barriers whose gates read a DIFFERENT state for this player keep their
    // boundary face so they stay visually distinct rather than merging into one surface.
    private static boolean drawFace(Level level, BlockPos pos, Player player, boolean open, Direction dir) {
        if (level == null) {
            return true;
        }
        if (level.getBlockEntity(pos.relative(dir)) instanceof BarrierBlockEntity neighbour) {
            boolean neighbourOpen = BarrierBlock.passes(
                    player, neighbour.getRequiredLevel(), neighbour.getRequiredRace());
            return neighbourOpen != open;
        }
        return true;
    }

    private static void quad(VertexConsumer vc, Matrix4f m, TextureAtlasSprite s,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4) {
        // stretch the sprite's uv rect across the whole quad (0,0)-(1,1)
        vertex(vc, m, s, x1, y1, z1, 0, 0);
        vertex(vc, m, s, x2, y2, z2, 0, 1);
        vertex(vc, m, s, x3, y3, z3, 1, 1);
        vertex(vc, m, s, x4, y4, z4, 1, 0);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, TextureAtlasSprite s,
                               float x, float y, float z, float u, float v) {
        vc.vertex(m, x, y, z)
                .color(R, G, B, A)
                .uv(s.getU(u), s.getV(v))
                .uv2(FULL_LIGHT)
                .normal(0, 1, 0)
                .endVertex();
    }
}
