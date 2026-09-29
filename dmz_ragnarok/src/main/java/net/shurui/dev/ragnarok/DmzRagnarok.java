package net.shurui.dev.ragnarok;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

/**
 * DMZ Ragnarok: the single @Mod container that aggregates the five DragonMine Z addons into one
 * loaded mod. Slice 1 of the merge only builds the structure; the five addons keep their own
 * registry namespaces (each DeferredRegister still uses its own MODID constant), so existing saves
 * load unchanged.
 *
 * <p>This constructor runs inside the one {@code dmz_ragnarok} mod-loading context, so every addon
 * initialiser it invokes sees this container's mod event bus via
 * {@code FMLJavaModLoadingContext.get().getModEventBus()} and this container via
 * {@code ModLoadingContext.get()}. SU caching {@code MOD_CONTAINER = getActiveContainer()} therefore
 * resolves to this container, which is intended.
 *
 * <p>Load order follows the workspace contract: sdu initialises FIRST (the others order AFTER it), then
 * shuruisutilities, then dungeons, raid bosses and tournaments.
 */
@Mod(DmzRagnarok.MODID)
public class DmzRagnarok {

    public static final String MODID = "dmz_ragnarok";

    private static final Logger LOGGER = LogUtils.getLogger();

    public DmzRagnarok() {
        // Pin the netty read timeout from our own jar so it is not forgotten on a panel we do not
        // control, and so every shard gets the same value. Forge's ServerConnectionListener reads
        // System property "forge.readTimeout" (in SECONDS) exactly once, at class initialisation, to
        // size the "timeout" ReadTimeoutHandler on the server pipeline. This @Mod constructor runs
        // before the server socket binds, so that class has not initialised yet and setting the
        // property here still takes effect.
        //
        // Why 120: it matches the login completion budget the slow_login mixin already allows
        // (LOGIN_TICK_LIMIT 2400 ticks = 120 s), so the two gates stop being mismatched. It lets a
        // brief proxy relay stall (Ambassador's handshake replay relay degrading with uptime) be
        // ridden out instead of killing the connection at the vanilla 30 s.
        //
        // This is a MITIGATION only: a hard stall now dies at 120 s instead of 30 s, and the real
        // cure is proxy side. We only set the property when the operator has not set it themselves,
        // so an explicit -Dforge.readTimeout=... in the panel JVM args wins.
        if (System.getProperty("forge.readTimeout") == null) {
            System.setProperty("forge.readTimeout", "120");
            LOGGER.info("Pinned forge.readTimeout to 120 s so a brief proxy relay stall is not killed at the vanilla 30 s (mitigation only; matches the 120 s slow_login budget).");
        } else {
            LOGGER.info("Left forge.readTimeout untouched at operator value {} s.", System.getProperty("forge.readTimeout"));
        }

        // Core = sdu + shuruisutilities, one indivisible container (this mod, dmz_ragnarok). sdu first: SU
        // and the modules historically declared ordering AFTER it.
        new net.shurui.dev.sdu.DmzNpc();
        // shuruisutilities second.
        new net.shurui.shuruisutilities.core.ShuruisUtilities();
        // The three optional modules (dungeons, raid bosses, tournaments) are NO LONGER constructed here.
        // Each is its own @Mod container now (dmz_ragnarok_dungeons / _raids / _tournaments), so Forge
        // constructs it on its OWN mod event bus, whether it arrives as its own jar or as one of the mods
        // declared by the fat jar. Constructing them here as well would double-register their content on the
        // wrong bus. They still register into the dmz_ragnarok namespace and load AFTER this core mod.

        // DEV-ONLY visual test harness. The DistExecutor supplier is created (and VisualTestBootstrap loaded) only
        // on the CLIENT physical side, so the client-only harness classes are never classloaded on a dedicated
        // server. VisualTestBootstrap.clientInit then registers NOTHING unless this is a dev environment AND the
        // -Ddmzr.visualtest system property is set, so a shipped client is equally unaffected.
        net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> net.shurui.dev.ragnarok.visualtest.VisualTestBootstrap::clientInit);
    }
}
