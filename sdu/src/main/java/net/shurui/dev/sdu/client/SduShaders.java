package net.shurui.dev.sdu.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * Custom core shaders registered by the addon. Currently just {@code sdu:outline}: an edge-detection
 * shader used by the form-editor preview to draw a silhouette outline around the model (it samples an
 * offscreen mask of the model and colours only the pixels just outside the silhouette).
 */
public final class SduShaders {

    private static ShaderInstance outline;

    private SduShaders() {
    }

    public static ShaderInstance outline() {
        return outline;
    }

    public static void register(RegisterShadersEvent event) throws IOException {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(), new ResourceLocation("dmz_ragnarok", "outline"),
                        DefaultVertexFormat.POSITION_TEX),
                shader -> outline = shader);
    }
}
