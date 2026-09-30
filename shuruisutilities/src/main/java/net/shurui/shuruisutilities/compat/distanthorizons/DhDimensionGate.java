package net.shurui.shuruisutilities.compat.distanthorizons;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Turns Distant Horizons off while the local player is inside one of our huge procedural space dimensions, and turns it
 * back on the instant they leave. DH generating and rendering LODs for the generated-planet surface and the space void
 * froze singleplayer with an "insufficient memory" stall: those dimensions are effectively unbounded and produce far
 * more LOD data than an ordinary overworld, and there is no reason to draw a level of detail horizon in a starfield or
 * on a single floating planet cell anyway.
 *
 * <p>Distant Horizons is an OPTIONAL mod. This class never imports a single DH type: every DH call is reflective and is
 * reached only after both {@code ModList.get().isLoaded("distanthorizons")} and a {@code Class.forName} probe of the DH
 * config entry point succeed (the compat pattern: presence is not a version guard, so we prove the exact API shape we
 * use is present before we use it). If DH is absent, or its API differs, this class does nothing at all and no user
 * without DH sees any change.</p>
 *
 * <p>Mechanism (DH API 3.x, verified against DistantHorizons-3.2.0-b): DH exposes reversible config overrides through
 * {@code DhApiConfig.INSTANCE}. We set {@code graphics().renderingEnabled()} and
 * {@code worldGenerator().enableDistantWorldGeneration()} to {@code false} on entering a target dimension
 * ({@code IDhApiConfigValue.setValue}, which is an API OVERRIDE layered over the user's own value, not a write to their
 * settings), and we {@code clearValue()} on leaving, which drops our override and restores exactly whatever the user had
 * configured. So a player's DH settings are never mutated, only suppressed while they are somewhere DH must not run.</p>
 *
 * <p>Scope and limits, stated plainly:</p>
 * <ul>
 *   <li>It is keyed on the CLIENT's current level, so it is naturally per dimension: the client only ever renders and
 *       (in singleplayer) generates for the one level it is in, so suppressing the global toggle while in a target
 *       dimension is equivalent to a per dimension suppression, and restoring on exit leaves every other dimension
 *       untouched.</li>
 *   <li>Singleplayer is fully covered: the integrated server's DH generation reads this same client-side config, so
 *       disabling generation here stops the integrated generation for the target dimension too.</li>
 *   <li>On a dedicated server the client still stops RENDERING the LODs (the memory-heavy part on the client) and stops
 *       any client-side generation. A dedicated server that itself runs DH with server-side generation for these
 *       dimensions is NOT gated by this client-side change; that would need DH's own per-dimension server config or a
 *       separate server-side hook, and is left out on purpose rather than toggling a server global that would also stop
 *       the overworld horizon for everyone.</li>
 * </ul>
 *
 * <p>Client only, FORGE bus. Explicit {@code modid = "dmz_ragnarok"} as the multi-mod jar requires.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DhDimensionGate
{
    private DhDimensionGate() {}

    // The dimensions DH must not run in. The live ids are dmz_ragnarok:space and dmz_ragnarok:planet_surface (baked into
    // level.dat since the dimension rename), taken straight from the core-owned SpaceKeys so this never drifts from the
    // real dimension identity. The shuruisutilities:* pair are the pre-rename ids that still exist as datapack shims for
    // migrated worlds, matched so an old save that still reports the legacy id is covered too. planet_vegeta is the one
    // standalone real planet dimension, another generated planet that would cost the same as the surface.
    private static final Set<ResourceLocation> TARGET_DIMENSIONS = Set.of(
            SpaceKeys.SPACE_ID,
            SpaceKeys.SURFACE_ID,
            SpaceKeys.PLANET_VEGETA_ID,
            new ResourceLocation("shuruisutilities", "space"),
            new ResourceLocation("shuruisutilities", "planet_surface"));

    // one-time probe result
    private static boolean probed;
    private static boolean dhUsable;

    // cached DH config value handles (IDhApiConfigValue<Boolean>) and their methods, resolved once by reflection
    private static Object renderingEnabledValue;
    private static Object worldGenEnabledValue;
    private static Method setValueMethod;
    private static Method clearValueMethod;

    // whether our override is currently applied, so we only touch DH on an actual transition
    private static boolean suppressing;

    private static final AtomicBoolean ARM_LOGGED = new AtomicBoolean(false);
    private static final AtomicBoolean SUPPRESS_LOGGED = new AtomicBoolean(false);

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        boolean inTarget = level != null && TARGET_DIMENSIONS.contains(level.dimension().location());
        apply(inTarget);
    }

    // Restore DH on leaving a world or server so an override can never leak into the next session (main menu, another
    // save, another server). Cheap no-op when nothing is suppressed or DH is absent.
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        apply(false);
    }

    private static void apply(boolean suppress)
    {
        if (suppress == suppressing)
        {
            return;
        }
        if (!ensureProbed())
        {
            return;
        }
        try
        {
            if (suppress)
            {
                setValueMethod.invoke(renderingEnabledValue, Boolean.FALSE);
                setValueMethod.invoke(worldGenEnabledValue, Boolean.FALSE);
                if (SUPPRESS_LOGGED.compareAndSet(false, true))
                {
                    LoggingHandler.sulog.info("[DH] Suppressed Distant Horizons rendering and distant generation in a "
                            + "space/planet dimension (reversible API override).");
                }
            }
            else
            {
                clearValueMethod.invoke(renderingEnabledValue);
                clearValueMethod.invoke(worldGenEnabledValue);
            }
            suppressing = suppress;
        }
        catch (Throwable t)
        {
            // A cosmetic/perf guard must never take the client down. If a DH call fails, stop trying (leave suppressing
            // as it was) and log once via the arm log path already used; the worst case is DH behaves normally.
            LoggingHandler.sulog.warn("[DH] Distant Horizons dimension gate call failed, leaving DH as-is: " + t);
        }
    }

    /**
     * Resolve and cache the DH config handles, exactly once. Returns true only when DH is loaded AND every reflective
     * handle we need is present and non-null, so a partial or changed API disables the gate rather than half-applying it.
     */
    private static synchronized boolean ensureProbed()
    {
        if (probed)
        {
            return dhUsable;
        }
        probed = true;
        dhUsable = false;

        if (!ModList.get().isLoaded("distanthorizons"))
        {
            return false;
        }
        try
        {
            // config entry point + the two reversible toggles we drive
            Class<?> configClass = Class.forName(
                    "com.seibel.distanthorizons.core.api.external.methods.config.DhApiConfig");
            Object configInstance = configClass.getField("INSTANCE").get(null);
            Object graphics = configClass.getMethod("graphics").invoke(configInstance);
            Object worldGen = configClass.getMethod("worldGenerator").invoke(configInstance);

            Class<?> graphicsIface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.config.client.IDhApiGraphicsConfig");
            Class<?> worldGenIface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.config.both.IDhApiWorldGenerationConfig");
            renderingEnabledValue = graphicsIface.getMethod("renderingEnabled").invoke(graphics);
            worldGenEnabledValue = worldGenIface.getMethod("enableDistantWorldGeneration").invoke(worldGen);

            Class<?> valueIface = Class.forName(
                    "com.seibel.distanthorizons.api.interfaces.config.IDhApiConfigValue");
            setValueMethod = valueIface.getMethod("setValue", Object.class);
            clearValueMethod = valueIface.getMethod("clearValue");

            dhUsable = renderingEnabledValue != null && worldGenEnabledValue != null
                    && setValueMethod != null && clearValueMethod != null;

            if (dhUsable && ARM_LOGGED.compareAndSet(false, true))
            {
                String version = probeVersion();
                LoggingHandler.sulog.info("[DH] Distant Horizons detected (" + version + "); dimension gate armed for "
                        + "space and generated-planet dimensions.");
            }
        }
        catch (Throwable t)
        {
            dhUsable = false;
            if (ARM_LOGGED.compareAndSet(false, true))
            {
                LoggingHandler.sulog.info("[DH] Distant Horizons is present but its config API did not match the "
                        + "expected 3.x shape; leaving DH untouched (" + t + ").");
            }
        }
        return dhUsable;
    }

    // best-effort version string for the arm log, purely informational
    private static String probeVersion()
    {
        try
        {
            Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
            Object v = dhApi.getMethod("getModVersion").invoke(null);
            return v == null ? "unknown version" : ("v" + v);
        }
        catch (Throwable t)
        {
            return "unknown version";
        }
    }
}
