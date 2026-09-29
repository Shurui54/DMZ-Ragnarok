package net.shurui.dev.shuruis_dmz_dungeons.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.shurui.dev.shuruis_dmz_dungeons.block.AdvancedSpawnerBlockEntity;

// the spawn block's own model is invisible, so this BER is the only thing that draws it: the configured
// disguise block if set, else a plain mob spawner cage. client-side only, disguise id is all that's synced.
public class AdvancedSpawnerRenderer implements BlockEntityRenderer<AdvancedSpawnerBlockEntity> {

    public AdvancedSpawnerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AdvancedSpawnerBlockEntity be, float partialTick, PoseStack pose,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        BlockState toRender = be.disguiseState();
        if (toRender == null) {
            toRender = Blocks.SPAWNER.defaultBlockState(); // no disguise, show a spawner cage
        }
        // renderSingleBlock draws the model here since our invisible block never makes it into the chunk mesh
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(toRender, pose, buffer, packedLight, packedOverlay);
    }
}
