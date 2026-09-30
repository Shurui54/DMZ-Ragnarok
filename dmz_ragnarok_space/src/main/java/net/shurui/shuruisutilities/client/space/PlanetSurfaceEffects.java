package net.shurui.shuruisutilities.client.space;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * The per-planet SURFACE sky, split off from the deep-space backdrop by batch B1. Standing on a generated planet you now
 * see a sky that belongs to THAT planet: a gradient atmosphere dome coloured from the planet's {@link SurfaceStamp.Theme}
 * (through {@link PlanetSkyPalette}), a matching fog and daytime lightmap lift, and the deep star field faded in and out
 * by a synthetic day/night cycle and by how thin the theme's atmosphere is. The sun and the sibling planets are drawn
 * separately, over this sky, by {@link SpaceBodyRenderer} at AFTER_SKY.
 *
 * <p>It EXTENDS {@link SpaceDimensionEffects} so it reuses the identical prebuilt star buffer (one field, built once)
 * rather than a second copy, and only overrides the pieces that differ on a planet surface: the dome, the fog colour,
 * the lightmap lift, and the star-field fade. Open space keeps the plain {@link SpaceDimensionEffects}.
 *
 * <h3>Day / night with a fixed-time dimension</h3>
 * The surface dimension is {@code fixed_time} and {@code has_skylight: false} (baked into level.dat, never changed here),
 * so there is no world day/night clock to read. The cycle is therefore a CLIENT VISUAL driven by the shared world game
 * time ({@link ClientLevel#getGameTime}), synchronised across clients and touching no world data. B2 (the sun-centred
 * layout) will make the sun's position track this same phase; for B1 the sun sits at a fixed per-planet direction and
 * only the dome brightness and the star fade move with the cycle.
 *
 * <h3>Fallback</h3>
 * With no synced theme ({@link SurfaceSkyState#theme()} null: not on a resolved planet, or before the first sync, or on
 * Beerus which is footprint-based and never records a theme) the dome is skipped and this defers to the inherited
 * biome-driven behaviour, so Beerus stays violet, Vegeta green, and everything else the plain black-void look, exactly
 * as before B1.
 */
public class PlanetSurfaceEffects extends SpaceDimensionEffects
{
    // Length of the synthetic day/night cycle in world game ticks. 24000 mirrors a vanilla day so the pace reads
    // naturally, even though the surface dimension itself has no clock.
    private static final long DAY_LENGTH_TICKS = 24000L;

    // The atmosphere dome sits just inside the star shell (SHELL_RADIUS = 100), so it is drawn first and the additively
    // blended stars lay over it. Depth is off for the whole sky, so radius only orders the layers visually.
    private static final double DOME_RADIUS = 90.0D;

    // Dome tessellation: latitude rings from the NADIR (straight down) up to the zenith, and longitude wedges. The dome
    // is a FULL sphere now, not just the upper cap: the lower hemisphere is filled with the horizon colour so pitching
    // the camera down never reveals a hard seam between the dome rim and the backdrop (the old cut-at-the-bottom bug).
    // DOME_LAT is raised so the upper hemisphere keeps its old gradient resolution now that the rings span the whole
    // sphere rather than a thin band above the horizon. A few thousand triangles, rebuilt only when the theme or the
    // quantised day phase changes, so the cost is paid once per change, never per frame.
    private static final int DOME_LAT = 24;
    private static final int DOME_LON = 32;

    // The bottom of the dome: the nadir. Filling all the way down means the lower hemisphere is a solid horizon-colour
    // cap under the player, so there is no lower edge to show through no matter how far the camera pitches down.
    private static final double DOME_BOTTOM_ELEVATION = -Math.PI / 2.0D;

    // How many buckets the day phase is quantised into for the dome rebuild key. 64 over a 24000-tick day rebuilds the
    // static dome buffer at most every ~6 s of real time, so the colour still eases through the cycle while the geometry
    // is genuinely built once per change, not per frame (the performance rule for this sky).
    private static final int DAY_BUCKETS = 64;

    // The prebuilt gradient dome, rebuilt only when domeKey changes. Null until first drawn (or after a theme change).
    private VertexBuffer domeBuffer;
    // Encodes (theme, day bucket) so a change of either triggers exactly one rebuild. Integer.MIN_VALUE = never built.
    private int domeKey = Integer.MIN_VALUE;

    public PlanetSurfaceEffects()
    {
        super(true);
    }

    // The synthetic day factor in 0..1: 0 = deep night, 1 = full noon, eased with a cosine so dawn and dusk are smooth.
    // Pure function of the shared world game time, so every client on the same planet sees the same phase.
    private static float dayFactor(ClientLevel level)
    {
        // harness/debug override: pin the phase so a scripted shot can force daytime (the real cycle is night in a fresh
        // test world). A negative value means follow the world clock.
        float override = SurfaceWeatherClient.dayFactorOverride();
        if (override >= 0.0F)
        {
            return Math.min(1.0F, override);
        }
        if (level == null)
        {
            return 1.0F;
        }
        double phase = (double) Math.floorMod(level.getGameTime(), DAY_LENGTH_TICKS) / (double) DAY_LENGTH_TICKS;
        return (float) (0.5D - 0.5D * Math.cos(phase * Math.PI * 2.0D));
    }

    // The synced theme's sky, or null when there is no synced theme (fall back to the inherited biome sky).
    private static PlanetSkyPalette.Sky themeSky()
    {
        SurfaceStamp.Theme theme = SurfaceSkyState.theme();
        return theme == null ? null : PlanetSkyPalette.forTheme(theme);
    }

    // Fog: on a themed planet the void and the dense horizon fog take the theme's HORIZON colour interpolated by the day
    // factor, which is the exact colour the dome's lower hemisphere is filled with, so distant terrain fades into the sky
    // with no seam and the void below the horizon matches the dome bottom. (This used to read the separate fogDay/fogNight
    // pair, which sat a little darker than the horizon and left a visible line where the sky met the fog.) With no synced
    // theme, defer to the base class (Beerus violet, Vegeta green, else black).
    @Override
    public Vec3 getBrightnessDependentFogColor(Vec3 fogColor, float brightness)
    {
        PlanetSkyPalette.Sky sky = themeSky();
        if (sky != null)
        {
            float d = dayFactor(Minecraft.getInstance().level);
            Vector3f fog = lerp(sky.nightHorizon, sky.dayHorizon, d);
            // WEATHER: blend the fog toward the weather's own haze colour (grey overcast, tan dust, dark red ash) by the
            // weather amount, then darken it with the sky so a storm's void is dim and murky, never the clear-sky colour.
            float grey = SurfaceWeatherClient.fogGrey();
            if (grey > 0.0F)
            {
                fog = lerp(fog, SurfaceWeatherClient.fogTarget(), grey);
            }
            float dark = Math.min(1.0F, SurfaceWeatherClient.domeBrightness());
            return new Vec3(fog.x() * dark, fog.y() * dark, fog.z() * dark);
        }
        return super.getBrightnessDependentFogColor(fogColor, brightness);
    }

    // Lightmap: lift the surface toward daytime white on a themed planet, scaled by the day factor so the ground darkens
    // at night. With no synced theme, defer to the base class (which lifts only on Beerus / Vegeta).
    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker,
                                     float skyLight, int pixelX, int pixelY, Vector3f colors)
    {
        PlanetSkyPalette.Sky sky = themeSky();
        if (sky != null)
        {
            float d = dayFactor(level);
            // never go fully dark: keep a floor so a night surface is still navigable, matching the dim ambient the
            // dimension type imposes rather than pitch black.
            float mix = sky.daylightMix * (0.30F + 0.70F * d);
            // WEATHER darkens the ground with the sky (bad weather dims the daylight lift), so a storm surface is dim.
            mix *= Math.min(1.0F, SurfaceWeatherClient.domeBrightness());
            colors.lerp(new Vector3f(1.0F, 0.99F, 0.98F), mix);
            return;
        }
        super.adjustLightmapColors(level, partialTicks, skyDarken, blockLightRedFlicker, skyLight, pixelX, pixelY, colors);
    }

    @Override
    public boolean renderSky(ClientLevel level, int ticks, float partialTick, PoseStack poseStack, Camera camera,
                             Matrix4f projectionMatrix, boolean isFoggy, Runnable setupFog)
    {
        PlanetSkyPalette.Sky sky = themeSky();
        if (sky == null)
        {
            // no synced theme: fall back to the inherited deep-space sky (which the biome path colours for Beerus /
            // Vegeta), exactly the pre-B1 behaviour.
            return super.renderSky(level, ticks, partialTick, poseStack, camera, projectionMatrix, isFoggy, setupFog);
        }

        ensureBuffers();

        float d = dayFactor(level);
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        FogRenderer.setupNoFog();

        // 1) THE ATMOSPHERE DOME, alpha-blended over the (theme-fog) cleared framebuffer. Built once per theme / day
        // bucket into a static VertexBuffer, never per frame. Drawn under a plain camera-view pose (no parallax drift)
        // so its rim never slides into view.
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        ensureDome(sky, d);
        Matrix4f domePose = new Matrix4f(poseStack.last().pose());
        // WEATHER: darken the whole dome under bad weather and briefly brighten it on a lightning flash, by multiplying
        // the prebuilt gradient rather than rebuilding it (the buffer is cached per theme / day bucket, so the weather
        // response has to be a draw-time colour multiply). 1.0 in clear weather leaves the dome exactly as built.
        float wb = SurfaceWeatherClient.domeBrightness();
        RenderSystem.setShaderColor(wb, wb, wb, 1.0F);
        this.domeBuffer.bind();
        this.domeBuffer.drawWithShader(domePose, projectionMatrix, GameRenderer.getPositionColorShader());
        VertexBuffer.unbind();
        // reset the shader colour so the weather multiply above never bleeds into the star pass (which sets its own).
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        // 2) THE STAR FIELD, additive and FADED. A thin-atmosphere theme (STONY, END) keeps a base star alpha in
        // daylight; every theme reaches full stars at night. buildSkyPose advances the shared parallax accumulator, so
        // it is called exactly once here.
        RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE,
                GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        Matrix4f skyPose = buildSkyPose(poseStack, camera, ticks, partialTick);
        float starAlpha = Math.min(1.0F, sky.baseStarAlpha + (1.0F - d) * (1.0F - sky.baseStarAlpha));
        // WEATHER: overcast dims the stars (a storm or blizzard hides them; a meteor shower keeps its clear night).
        starAlpha *= (1.0F - SurfaceWeatherClient.starSuppress());
        drawStars(skyPose, projectionMatrix, starAlpha);

        RenderSystem.disableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        // true suppresses vanilla's own sun/moon/clouds/horizon; the sun and sibling planets are drawn at AFTER_SKY.
        return true;
    }

    // Rebuild the dome buffer if the theme or the quantised day phase changed since it was last built.
    private void ensureDome(PlanetSkyPalette.Sky sky, float dayFactor)
    {
        SurfaceStamp.Theme theme = SurfaceSkyState.theme();
        int bucket = Math.round(dayFactor * (DAY_BUCKETS - 1));
        int key = ((theme == null ? 0 : theme.ordinal() + 1) * (DAY_BUCKETS + 1)) + bucket;
        if (this.domeBuffer != null && key == this.domeKey)
        {
            return;
        }
        Vector3f zenith = lerp(sky.nightZenith, sky.dayZenith, dayFactor);
        Vector3f horizon = lerp(sky.nightHorizon, sky.dayHorizon, dayFactor);
        VertexBuffer rebuilt = buildDome(zenith, horizon);
        if (this.domeBuffer != null)
        {
            this.domeBuffer.close();
        }
        this.domeBuffer = rebuilt;
        this.domeKey = key;
    }

    // Build the gradient dome: latitude rings from just below the horizon to the zenith, coloured by elevation from the
    // horizon colour to the zenith colour. Emitted as loose triangles in one POSITION_COLOR buffer, one draw call.
    private static VertexBuffer buildDome(Vector3f zenith, Vector3f horizon)
    {
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);

        double elBottom = DOME_BOTTOM_ELEVATION;
        double elTop = Math.PI / 2.0D;
        for (int lat = 0; lat < DOME_LAT; ++lat)
        {
            double e0 = elBottom + (elTop - elBottom) * ((double) lat / DOME_LAT);
            double e1 = elBottom + (elTop - elBottom) * ((double) (lat + 1) / DOME_LAT);
            for (int lon = 0; lon < DOME_LON; ++lon)
            {
                double a0 = (double) lon / DOME_LON * Math.PI * 2.0D;
                double a1 = (double) (lon + 1) / DOME_LON * Math.PI * 2.0D;
                float[] c00 = colourAt(e0, zenith, horizon);
                float[] c10 = colourAt(e1, zenith, horizon);
                float[] p00 = point(e0, a0);
                float[] p01 = point(e0, a1);
                float[] p10 = point(e1, a0);
                float[] p11 = point(e1, a1);
                // two triangles per quad wedge.
                vert(builder, p00, c00);
                vert(builder, p01, c00);
                vert(builder, p11, c10);
                vert(builder, p00, c00);
                vert(builder, p11, c10);
                vert(builder, p10, c10);
            }
        }
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(builder.end());
        VertexBuffer.unbind();
        return buffer;
    }

    // Colour at an elevation: below/at the horizon it is the horizon colour, rising to the zenith colour by sin(elev).
    private static float[] colourAt(double elevation, Vector3f zenith, Vector3f horizon)
    {
        float f = (float) Math.max(0.0D, Math.sin(Math.max(0.0D, elevation)));
        return new float[] {
                horizon.x() + (zenith.x() - horizon.x()) * f,
                horizon.y() + (zenith.y() - horizon.y()) * f,
                horizon.z() + (zenith.z() - horizon.z()) * f };
    }

    // A point on the dome shell at the given elevation (from the horizon plane) and azimuth.
    private static float[] point(double elevation, double azimuth)
    {
        double ce = Math.cos(elevation);
        double se = Math.sin(elevation);
        return new float[] {
                (float) (Math.cos(azimuth) * ce * DOME_RADIUS),
                (float) (se * DOME_RADIUS),
                (float) (Math.sin(azimuth) * ce * DOME_RADIUS) };
    }

    private static void vert(BufferBuilder builder, float[] p, float[] c)
    {
        builder.vertex(p[0], p[1], p[2]).color(c[0], c[1], c[2], 1.0F).endVertex();
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t)
    {
        return new Vector3f(
                a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t);
    }
}
