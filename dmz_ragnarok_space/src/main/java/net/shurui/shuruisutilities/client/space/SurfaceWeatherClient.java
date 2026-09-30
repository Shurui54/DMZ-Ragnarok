package net.shurui.shuruisutilities.client.space;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.space.OrbitClock;
import net.shurui.shuruisutilities.space.PlanetSpawnModule;
import net.shurui.shuruisutilities.space.PlanetWeather;
import net.shurui.shuruisutilities.space.SurfaceDimension;
import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * The client authority for per-planet weather ({@link PlanetWeather}) while the player stands on a planet surface. Each
 * client tick it recomputes the current weather from the synced planet key and theme ({@link SurfaceSkyState}) and the
 * shard-corrected {@link OrbitClock} clock (so it matches every other client and the server with nothing streamed), then
 * draws precipitation particles, drives the looping weather sound ({@link WeatherSoundInstance}), fires thunder on each
 * deterministic lightning flash, and exposes the darkening / fog / star-suppression the sky renderer
 * ({@link PlanetSurfaceEffects}) folds into the dome.
 *
 * <p>The particle budget scales with the client's particle graphics setting, so a low-end machine sees a lighter storm
 * and a high-end one a full downpour. A debug hook ({@link #forceState}) lets the visual-test harness pin a state so a
 * scripted shot can capture clear, rain, storm and snow without waiting for the clock to roll them around.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SurfaceWeatherClient
{
    private SurfaceWeatherClient()
    {
    }

    private static volatile PlanetWeather.State state = PlanetWeather.State.CLEAR;
    private static volatile float intensity = 0.0F;
    private static volatile float flash = 0.0F;
    private static volatile boolean onWeatheredSurface = false;

    // harness debug override: when non-null, this state is shown at full intensity regardless of the clock.
    private static volatile PlanetWeather.State forced = null;

    // harness debug override for the synthetic day/night phase (0 = deep night, 1 = full noon), or -1 to follow the
    // world clock. Lets a scripted shot pin daytime so the theme-coloured dome and the weather darkening are visible
    // (the real cycle is driven by the world game time, which is near zero in a fresh test world, i.e. night).
    private static volatile float debugDayFactor = -1.0F;

    private static WeatherSoundInstance loop;
    private static boolean lastFlashOn = false;

    // ---- accessors used by the sky renderer, the sound loop and the harness ----

    public static PlanetWeather.State state()
    {
        return state;
    }

    public static float intensity()
    {
        return intensity;
    }

    /** True while the player is on a surface planet with a known theme (so weather is being drawn at all). */
    public static boolean onWeatheredSurface()
    {
        return onWeatheredSurface;
    }

    /** Force a weather state for testing (harness only), or {@code null} to return to the deterministic clock. */
    public static void forceState(PlanetWeather.State s)
    {
        forced = s;
    }

    /** Force the synthetic day/night factor (0..1) for testing (harness only), or a negative value to follow the clock. */
    public static void forceDayFactor(float f)
    {
        debugDayFactor = f;
    }

    /** The forced day factor (0..1), or -1 when following the world clock. Read by the sky renderer. */
    public static float dayFactorOverride()
    {
        return debugDayFactor;
    }

    // ---- the per-tick update ----

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.isPaused())
        {
            return;
        }

        boolean surface = SurfaceDimension.isSurface(level) && SurfaceSkyState.theme() != null
                && PlanetSpawnModule.planetWeatherEnabled();
        onWeatheredSurface = surface;
        if (!surface)
        {
            state = PlanetWeather.State.CLEAR;
            intensity = 0.0F;
            flash = 0.0F;
            return;
        }

        String key = SurfaceSkyState.planetKey();
        SurfaceStamp.Theme theme = SurfaceSkyState.theme();
        long epoch = OrbitClock.epochMillis();

        PlanetWeather.State f = forced;
        if (f != null)
        {
            state = f;
            intensity = 1.0F;
        }
        else
        {
            PlanetWeather.Snapshot snap = PlanetWeather.at(key, theme, epoch);
            state = snap.state;
            intensity = snap.intensity;
        }
        // lightning is always from the real clock (a forced STORM still flashes on schedule so a test can see it).
        flash = state == PlanetWeather.State.STORM ? PlanetWeather.lightning(key, epoch) : 0.0F;

        // thunder on the RISING edge of each flash.
        boolean flashOn = flash > 0.15F;
        if (flashOn && !lastFlashOn)
        {
            level.playLocalSound(player.getX(), player.getY(), player.getZ(),
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.WEATHER, 0.9F, 0.8F + level.random.nextFloat() * 0.2F, false);
        }
        lastFlashOn = flashOn;

        spawnHazeParticles(mc, player, level);
        ensureLoop(mc);
    }

    // start the looping weather ambience once when a loop-bearing state begins; it self-stops when the weather clears.
    private static void ensureLoop(Minecraft mc)
    {
        if (!WeatherSoundInstance.hasLoop(state) || intensity <= 0.0F)
        {
            return;
        }
        if (loop == null || !mc.getSoundManager().isActive(loop))
        {
            loop = new WeatherSoundInstance();
            mc.getSoundManager().play(loop);
        }
    }

    // ---- haze particles (dust, ash, meteor) ----
    //
    // Rain, storm, snow and blizzard are NOT particles: they are drawn as vanilla-style falling streak/flake quads near
    // the camera in onRenderWeather below, which reads as real precipitation rather than a field of floating squares.
    // Dust and ash are a drifting haze and a meteor shower is a few streaks, all of which particles suit, so those stay
    // here on the tick.

    private static void spawnHazeParticles(Minecraft mc, LocalPlayer player, ClientLevel level)
    {
        if (intensity <= 0.02F)
        {
            return;
        }
        float budget = particleBudget(mc) * intensity;
        RandomSource rng = level.random;
        double px = player.getX();
        double py = player.getEyeY();
        double pz = player.getZ();
        switch (state)
        {
            case DUST ->
            {
                int n = Math.round(55 * budget);
                double wind = 0.4;
                for (int i = 0; i < n; ++i)
                {
                    scatterFall(level, ParticleTypes.ASH, rng, px, py, pz, 15.0, 6.0, wind, -0.02, wind * 0.2);
                }
            }
            case ASH ->
            {
                // ash falls: WHITE_ASH drifts down (negative Y). No rising embers, so nothing reads as flowing upward.
                int n = Math.round(48 * budget);
                for (int i = 0; i < n; ++i)
                {
                    scatterFall(level, ParticleTypes.WHITE_ASH, rng, px, py, pz, 14.0, 6.0,
                            (rng.nextDouble() - 0.5) * 0.05, -0.05, (rng.nextDouble() - 0.5) * 0.05);
                }
            }
            case METEOR ->
            {
                // a few bright streaks high across the sky, moving fast on a shallow diagonal, leaving the end-rod trail.
                int n = Math.max(1, Math.round(3 * budget));
                for (int i = 0; i < n; ++i)
                {
                    double sx = px + (rng.nextDouble() - 0.5) * 40.0;
                    double sy = py + 25.0 + rng.nextDouble() * 15.0;
                    double sz = pz + (rng.nextDouble() - 0.5) * 40.0;
                    double dir = rng.nextDouble() * Math.PI * 2.0;
                    level.addParticle(ParticleTypes.END_ROD, sx, sy, sz,
                            Math.cos(dir) * 0.8, -0.6, Math.sin(dir) * 0.8);
                }
            }
            default ->
            {
            }
        }
    }

    private static void scatterFall(ClientLevel level, ParticleOptions type, RandomSource rng, double px, double py,
                                    double pz, double radius, double up, double vx, double vy, double vz)
    {
        double x = px + (rng.nextDouble() - 0.5) * 2.0 * radius;
        double y = py + rng.nextDouble() * up + 2.0;
        double z = pz + (rng.nextDouble() - 0.5) * 2.0 * radius;
        level.addParticle(type, x, y, z, vx, vy, vz);
    }

    // scale the particle count by the client's particle graphics setting so a haze is lighter on a low-end machine.
    private static float particleBudget(Minecraft mc)
    {
        return switch (mc.options.particles().get())
        {
            case ALL -> 1.0F;
            case DECREASED -> 0.5F;
            case MINIMAL -> 0.12F;
        };
    }

    // ---- rain / snow, drawn as vanilla-style falling quads near the camera ----

    private static final ResourceLocation RAIN_TEX = new ResourceLocation("textures/environment/rain.png");
    private static final ResourceLocation SNOW_TEX = new ResourceLocation("textures/environment/snow.png");

    /**
     * Draw rain, storm, snow or blizzard as columns of tall thin textured quads around the camera, each billboarded to
     * face it and scrolled downward over time, so precipitation reads as streaks falling near the player (vanilla's own
     * look and textures) instead of a field of floating particle squares. Denser and darker for a storm, wind-slanted
     * for storm and blizzard, alpha faded with distance. Depth is tested but not written, and this runs at AFTER_WEATHER
     * so it lays over the terrain like vanilla weather.
     */
    @SubscribeEvent
    public static void onRenderWeather(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER)
        {
            return;
        }
        if (!onWeatheredSurface || intensity <= 0.02F)
        {
            return;
        }
        boolean rain = state == PlanetWeather.State.RAIN || state == PlanetWeather.State.STORM;
        boolean snow = state == PlanetWeather.State.SNOW || state == PlanetWeather.State.BLIZZARD;
        if (!rain && !snow)
        {
            return;
        }
        boolean heavy = state == PlanetWeather.State.STORM || state == PlanetWeather.State.BLIZZARD;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            return;
        }
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        float partial = event.getPartialTick();
        float time = (float) mc.level.getGameTime() + partial;

        int radius = heavy ? 7 : 5;
        float fallSpeed = heavy ? 1.9F : 1.2F;          // texture scroll per tick
        float vScale = 0.10F;                            // texture tiles per block of height
        float halfWidth = 0.55F;
        float yTop = 9.0F;
        float yBot = -5.0F;
        // wind slant offsets the top of each streak (blocks), giving storms and blizzards a driven, angled look.
        float windX = heavy ? (rain ? 2.6F : 1.6F) : (rain ? 0.7F : 0.9F);
        float windZ = heavy ? 1.1F : 0.4F;
        // base colour and opacity per state: rain grey-white, snow near-white; heavier is denser (more opaque).
        float cr = rain ? 0.62F : 0.90F;
        float cg = rain ? 0.66F : 0.93F;
        float cb = rain ? 0.78F : 1.00F;
        float baseAlpha = (rain ? (heavy ? 0.62F : 0.42F) : (heavy ? 0.60F : 0.40F)) * intensity;

        int camX = Mth.floor(camPos.x);
        int camZ = Mth.floor(camPos.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        // Draw the streaks two sided, exactly like vanilla's renderSnowAndRain. The quads are world vertical with a
        // horizontal (roughly camera facing) normal, so pitching the view toward straight up or straight down swings
        // them past the back face threshold: with culling left on (its state at AFTER_WEATHER) the visible set of
        // streaks flips as the camera pitches, which reads as the rain changing the direction it moves. Disabling cull
        // keeps every streak drawn at any pitch, so the field stays put in the world and only falls down.
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, rain ? RAIN_TEX : SNOW_TEX);

        Matrix4f mat = event.getPoseStack().last().pose();
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        int r2 = radius * radius;
        for (int ix = -radius; ix <= radius; ++ix)
        {
            for (int iz = -radius; iz <= radius; ++iz)
            {
                if (ix * ix + iz * iz > r2)
                {
                    continue;
                }
                // per-column deterministic phase, so streaks are not all aligned.
                int h = ix * ix * 3121 + ix * 45238971 + iz * iz * 418711 + iz * 13761;
                float phase = (h & 0xFF) / 255.0F;
                float jitterX = ((h >> 8) & 0xFF) / 255.0F - 0.5F;
                float jitterZ = ((h >> 16) & 0xFF) / 255.0F - 0.5F;

                double colX = camX + ix + 0.5 + jitterX;
                double colZ = camZ + iz + 0.5 + jitterZ;
                float dx = (float) (colX - camPos.x);
                float dz = (float) (colZ - camPos.z);
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                if (dist > radius)
                {
                    continue;
                }
                float alpha = baseAlpha * (1.0F - (dist * dist) / r2);
                if (alpha <= 0.01F)
                {
                    continue;
                }
                // Width axis in the horizontal plane, perpendicular to the camera-to-column direction: a cylindrical
                // billboard that rotates ONLY around world Y. The quad stays vertical in the world (its up edge is the
                // fixed world Y span below, plus the world wind slant), so camera PITCH and YAW never tilt a streak or
                // change the direction it falls. Only the camera position, never the look angle, feeds this axis.
                float perpX = dist < 1.0E-4F ? 1.0F : -dz / dist;
                float perpZ = dist < 1.0E-4F ? 0.0F : dx / dist;
                float wx = perpX * halfWidth;
                float wz = perpZ * halfWidth;

                // v increases WITH world y (top = larger v) and WITH time, so a fixed bright texel's world y decreases
                // over time: the streaks fall DOWN. (An earlier -y sign inverted this and made the rain flow upward.)
                // Anchor v to the ABSOLUTE world y (camPos.y + the camera-relative span) so the scroll is pinned to the
                // world, not to the camera: the fall reads the same whatever the camera height or where it looks.
                float wy0 = (float) camPos.y + yBot;
                float wy1 = (float) camPos.y + yTop;
                float v0 = wy0 * vScale + (time * fallSpeed * 0.06F) + phase;
                float v1 = wy1 * vScale + (time * fallSpeed * 0.06F) + phase;

                float bx0 = dx - wx;
                float bz0 = dz - wz;
                float bx1 = dx + wx;
                float bz1 = dz + wz;
                float tx0 = bx0 + windX;
                float tz0 = bz0 + windZ;
                float tx1 = bx1 + windX;
                float tz1 = bz1 + windZ;

                bb.vertex(mat, bx0, yBot, bz0).uv(0.0F, v0).color(cr, cg, cb, alpha).endVertex();
                bb.vertex(mat, bx1, yBot, bz1).uv(1.0F, v0).color(cr, cg, cb, alpha).endVertex();
                bb.vertex(mat, tx1, yTop, tz1).uv(1.0F, v1).color(cr, cg, cb, alpha).endVertex();
                bb.vertex(mat, tx0, yTop, tz0).uv(0.0F, v1).color(cr, cg, cb, alpha).endVertex();
            }
        }
        tess.end();

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    // ---- sky darkening inputs, read by PlanetSurfaceEffects ----

    /** The multiply applied to the atmosphere dome (and the lightmap lift), darkening the sky under bad weather and
     *  briefly brightening on a lightning flash. 1.0 in clear weather. */
    public static float domeBrightness()
    {
        float darken = lerp(1.0F, baseDarken(state), intensity);
        return Math.min(2.2F, darken * (1.0F + flash * 1.6F));
    }

    /** 0..1 amount to blend the fog colour toward {@link #fogTarget}, scaled by intensity. */
    public static float fogGrey()
    {
        return baseGreyFog(state) * intensity;
    }

    /** The colour the fog blends toward under this weather (grey overcast, tan dust, dark red ash). */
    public static Vector3f fogTarget()
    {
        return switch (state)
        {
            case DUST -> new Vector3f(0.55F, 0.46F, 0.31F);
            case ASH -> new Vector3f(0.26F, 0.13F, 0.09F);
            default -> new Vector3f(0.46F, 0.48F, 0.53F);
        };
    }

    /** 0..1 how much the star field is dimmed by overcast, scaled by intensity. A meteor shower keeps its clear sky. */
    public static float starSuppress()
    {
        return baseStarSuppress(state) * intensity;
    }

    private static float baseDarken(PlanetWeather.State s)
    {
        return switch (s)
        {
            case STORM -> 0.48F;
            case BLIZZARD -> 0.58F;
            case ASH -> 0.55F;
            case RAIN -> 0.68F;
            case DUST -> 0.66F;
            case SNOW -> 0.80F;
            case METEOR -> 0.92F;
            default -> 1.0F;
        };
    }

    private static float baseGreyFog(PlanetWeather.State s)
    {
        return switch (s)
        {
            case STORM -> 0.72F;
            case BLIZZARD -> 0.85F;
            case DUST -> 0.90F;
            case ASH -> 0.80F;
            case RAIN -> 0.50F;
            case SNOW -> 0.60F;
            default -> 0.0F;
        };
    }

    private static float baseStarSuppress(PlanetWeather.State s)
    {
        return switch (s)
        {
            case STORM, BLIZZARD -> 0.92F;
            case DUST -> 0.85F;
            case ASH -> 0.75F;
            case RAIN -> 0.70F;
            case SNOW -> 0.65F;
            default -> 0.0F;
        };
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * Math.max(0.0F, Math.min(1.0F, t));
    }
}
