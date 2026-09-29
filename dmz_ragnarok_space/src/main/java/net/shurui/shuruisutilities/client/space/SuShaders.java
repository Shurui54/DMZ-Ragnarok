package net.shurui.shuruisutilities.client.space;

import java.io.IOException;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * SU's custom core shaders. Currently just {@code shuruisutilities:lensing}: a fullscreen screen-space pass that warps the
 * grabbed sky/starfield around a black hole, the only real gravitational-lensing effect in the suite. Registered on the
 * mod bus via {@link SpaceClientBusEvents#registerShaders(RegisterShadersEvent)}. Mirrors the sdu SduShaders idiom.
 *
 * <p>The lensing draw itself lives in {@link BlackHoleLensing}; this class only holds the compiled instance and hands it
 * out. If registration ever fails, {@link #lensing()} returns null and the lensing pass self-skips, so the black hole
 * still renders its sphere, spherical rim, discs and wrap arcs (the non-shader path).
 */
public final class SuShaders
{
    private static ShaderInstance lensing;

    private SuShaders()
    {
    }

    public static ShaderInstance lensing()
    {
        return lensing;
    }

    public static void register(RegisterShadersEvent event) throws IOException
    {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        new ResourceLocation(ShuruisUtilities.MODID, "lensing"),
                        DefaultVertexFormat.POSITION_TEX),
                shader -> lensing = shader);
    }
}
