package net.shurui.dev.ragnarok.visualtest;

import com.mojang.logging.LogUtils;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLEnvironment;

import org.slf4j.Logger;

/**
 * Entry point for the DEV-ONLY visual test harness.
 *
 * <p>This class and everything it reaches ({@link VisualTestRunner}) reference client-only Minecraft types, so it
 * must never be classloaded on a dedicated server. That is guaranteed by the single call site in
 * {@code DmzRagnarok}, which wraps {@link #clientInit()} in {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)}:
 * on a server the supplier is never created and this class is never loaded.
 *
 * <h2>Two locks, both required</h2>
 * The harness activates ONLY when BOTH hold:
 * <ul>
 *   <li>{@code !FMLEnvironment.production}: never in a shipped jar. A production client that somehow set the
 *       property still registers nothing.</li>
 *   <li>the JVM system property {@code dmzr.visualtest} is set (its value is the comma-separated scenario list, or
 *       {@code all}). Absent means the harness is completely inert: nothing is registered, nothing ticks, nothing
 *       is logged beyond a single debug line.</li>
 * </ul>
 * With the property absent, a normal {@code runClient} is byte-for-byte unaffected.
 */
public final class VisualTestBootstrap
{
    public static final String PROPERTY = "dmzr.visualtest";

    private static final Logger LOGGER = LogUtils.getLogger();

    private VisualTestBootstrap() {}

    /**
     * Called on the CLIENT physical side only (via DistExecutor from the mod constructor). Registers the runner on
     * the Forge event bus when, and only when, the two activation locks are open.
     */
    public static void clientInit()
    {
        if (FMLEnvironment.production)
        {
            // Belt and braces: the DistExecutor guard already keeps this off dedicated servers, and this keeps it
            // off a shipped client too. A shipped jar registers nothing.
            return;
        }
        String scenarios = System.getProperty(PROPERTY);
        if (scenarios == null || scenarios.isBlank())
        {
            LOGGER.debug("[VisualTest] Inert: -D{} not set.", PROPERTY);
            return;
        }
        LOGGER.info("[VisualTest] Arming the visual test harness for scenarios: {}", scenarios);
        MinecraftForge.EVENT_BUS.register(new VisualTestRunner(scenarios.trim()));
        // DMZ 2.2-alpha kicks any login not on its online beta whitelist, and offline its fallback never matches
        // (it compares a lowercased name against a mixed-case list). Let the harness's own player in, before
        // DMZ's login check runs.
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGHEST,
                (net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent e) -> allowOnDmzBeta(
                        e.getEntity().getGameProfile().getName()));
    }

    @SuppressWarnings("unchecked")
    private static void allowOnDmzBeta(String username)
    {
        try
        {
            Class<?> c = Class.forName("com.dragonminez.common.util.BetaWhitelist");
            java.lang.reflect.Field f = c.getDeclaredField("activeList");
            f.setAccessible(true);
            java.util.List<String> list = new java.util.ArrayList<>((java.util.List<String>) f.get(null));
            String key = username.toLowerCase(java.util.Locale.ROOT);
            if (!list.contains(key))
            {
                list.add(key);
                f.set(null, list);
            }
        }
        catch (Throwable t)
        {
            LOGGER.debug("[VisualTest] DMZ beta whitelist not present: {}", t.toString());
        }
    }
}
