package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.world.space.SurfaceTravelData;

/**
 * The LIGHT, server-side gameplay effects of {@link PlanetWeather}, applied to players standing on a generated planet's
 * surface. Deliberately cheap and safe: it only extinguishes a burning player while it rains or storms, and lays a mild
 * movement slowness during a blizzard. Everything else about weather is a pure client visual.
 *
 * <p>The weather itself is computed the same deterministic way the client computes it (from the planet's stamped theme
 * and the shard-corrected {@link OrbitClock} clock), so the server and every client always agree on what is falling. It
 * ticks a player at most a few times a second and short-circuits the instant it is off ({@code planetWeather} or
 * {@code planetWeatherEffects} false, or the player is not on a surface planet), so it costs nothing on a normal tick.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PlanetWeatherEffects
{
    private PlanetWeatherEffects()
    {
    }

    // apply at most about three times a second: fire and movement effects do not need per-tick precision, and this keeps
    // the theme resolve and clock read off the hot path.
    private static final int TICK_INTERVAL = 7;

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || event.side.isClient())
        {
            return;
        }
        if (!(event.player instanceof ServerPlayer player))
        {
            return;
        }
        if (!PlanetSpawnModule.planetWeatherEnabled() || !PlanetSpawnModule.planetWeatherEffectsEnabled())
        {
            return;
        }
        if ((player.tickCount % TICK_INTERVAL) != 0)
        {
            return;
        }
        Level level = player.level();
        if (!SurfaceDimension.isSurface(level))
        {
            return;
        }
        String planetId = SurfaceTravelData.planetId(player);
        if (planetId == null || planetId.isEmpty())
        {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return;
        }
        SurfaceStamp.Theme theme = GeneratedPlanetClaims.stampedThemeForId(server, planetId);
        PlanetWeather.Snapshot snap = PlanetWeather.at(planetId, theme, OrbitClock.epochMillis());
        if (snap.intensity < 0.25F)
        {
            return;   // too faint to bite (still easing in or nearly cleared)
        }
        switch (snap.state)
        {
            case RAIN, STORM ->
            {
                // rain puts out fires: a player alight under open rain stops burning. Cheap and matches the visual.
                if (player.isOnFire())
                {
                    player.clearFire();
                }
            }
            case BLIZZARD ->
            {
                // a blizzard drags on movement: a mild, refreshed slowness with no particles or icon spam. Kept to
                // Slowness I so it reads as harsh weather, not a debuff attack.
                player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN,
                        TICK_INTERVAL + 20, 0, true, false, false));
            }
            default ->
            {
                // clear / snow / dust / ash / meteor: no gameplay effect, purely cosmetic on the client.
            }
        }
    }
}
