package net.shurui.dev.shuruis_dmz_dungeons.client;

import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import javax.annotation.Nullable;

// ONE configurable DimensionSpecialEffects used for all four themed-dimension effect groups (surface, otherworld,
// nether, end). The specific sky/fog COLOURS come from each dimension's biome JSON; this class only decides the
// sky style (sun/moon/clouds/starfield), whether fog dims with light or stays flat, whether a sunrise gradient is
// drawn, and whether the camera sits in thick fog. Four instances are registered in ClientModBusEvents.
public class DungeonDimensionEffects extends DimensionSpecialEffects {

    // when true the fog dims with the ambient light like the overworld; when false it stays flat and biome-driven
    // (so a warm/red/purple haze reads at full strength regardless of the time of day).
    private final boolean brightnessScaledFog;
    // when true a sunrise/sunset gradient is drawn (only meaningful with SkyType.NORMAL).
    private final boolean showSunrise;
    // when true the camera is always considered to be in fog, thickening the near-camera haze.
    private final boolean thickFog;

    private final float[] sunriseCol = new float[4];

    public DungeonDimensionEffects(float cloudLevel, boolean hasGround, SkyType skyType,
                                   boolean forceBrightLightmap, boolean constantAmbientLight,
                                   boolean brightnessScaledFog, boolean showSunrise, boolean thickFog) {
        super(cloudLevel, hasGround, skyType, forceBrightLightmap, constantAmbientLight);
        this.brightnessScaledFog = brightnessScaledFog;
        this.showSunrise = showSunrise;
        this.thickFog = thickFog;
    }

    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness) {
        if (brightnessScaledFog) {
            // overworld-style: fog darkens toward night. Keeps the daylit surface themes readable.
            return fogColor.multiply(brightness * 0.94F + 0.06F, brightness * 0.94F + 0.06F,
                    brightness * 0.91F + 0.09F);
        }
        // flat, biome-driven colour: the warm otherworld / dark-red nether / dim-purple end haze reads at full
        // strength no matter the light level.
        return fogColor;
    }

    @Nullable
    @Override
    public float[] getSunriseColor(float timeOfDay, float partialTicks) {
        if (!showSunrise) {
            return null; // no sun/moon on these themes, so no sunrise gradient.
        }
        // vanilla overworld sunrise maths.
        float horizon = 0.4F;
        float cos = Mth.cos(timeOfDay * ((float) Math.PI * 2F));
        if (cos >= -horizon && cos <= horizon) {
            float f = (cos + horizon) / (2F * horizon);
            float alpha = 1.0F - (1.0F - Mth.sin(f * (float) Math.PI)) * 0.99F;
            alpha *= alpha;
            sunriseCol[0] = f * 0.3F + 0.7F;
            sunriseCol[1] = f * f * 0.7F + 0.2F;
            sunriseCol[2] = f * f * 0.0F + 0.2F;
            sunriseCol[3] = alpha;
            return sunriseCol;
        }
        return null;
    }

    @Override
    public boolean isFoggyAt(int x, int y) {
        return thickFog;
    }
}
