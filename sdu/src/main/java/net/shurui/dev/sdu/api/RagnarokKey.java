package net.shurui.dev.sdu.api;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.shurui.dev.sdu.DmzNpc;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * The ONE place that answers "does this server hold the Ragnarok Key". This is the single facade every gate in
 * the suite routes through, so the decision of WHAT the key physically is lives in exactly one method.
 *
 * <p>As of the 2.0 tier collapse there is ONE key: the Ragnarok Key mod ({@code dmz_ragnarok_key}); there is no
 * other key tier and no separate licence jar (SF). Presence is
 * INSTALLATION, not the jar: the key mod marks {@link KeyFeatures} {@value #KEY_FEATURE} as the last step of
 * installing every private hook, so a jar that merely carries the mod id (or marks feature ids without installing
 * anything) unlocks nothing, because every private behaviour sits behind a hook only the real key installs.
 *
 * <p>Readable from every tree, including the three module projects, because it sits in {@code sdu/api} next to
 * {@link ModulePresence}. Route every key gate through here rather than calling a {@code KeyGate} copy directly,
 * so the swap above stays a one-line edit.
 */
public final class RagnarokKey {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** The {@link KeyFeatures} id the key mod marks LAST in its hook install. */
    public static final String KEY_FEATURE = "ragnarok_key";

    /** The key mod's container id, used only by the dev-time early-read trip below (never to grant anything). */
    private static final String KEY_MODID = "dmz_ragnarok_key";

    private static boolean logged;
    private static boolean renderThreadWarned;
    private static volatile boolean earlyReadWarned;

    private RagnarokKey() {}

    /**
     * Whether the server holds the Ragnarok Key.
     *
     * <p>True once the Ragnarok Key mod has installed its hooks and marked {@value #KEY_FEATURE} (the last line of
     * its hook install, at mod construction). Nothing is cached here: {@link KeyFeatures} is the single source, so a
     * read made before the key finished constructing answers false and a later read answers true. Never cache this
     * at construction time; read it at the point of use.
     *
     * <p>This answers the SERVER's key state. Client code must NOT call it: on a physical client the key mod is
     * not present, so this is always false there, and a screen gating on it would hide a keyed server's private
     * UI. Client code reads {@link ClientGate} instead, fed by the login sync. The render-thread tripwire below
     * catches a stray client read once, loudly, with a stack trace, so the offending call site is easy to find.
     */
    public static boolean present() {
        if (!renderThreadWarned && "Render thread".equals(Thread.currentThread().getName())) {
            renderThreadWarned = true;
            LOGGER.warn("[{}] RagnarokKey.present() was called on the render thread. Client code must read "
                    + "ClientGate, never the server-side key. Stack trace so the call site can be fixed:",
                    DmzNpc.MODID, new Throwable("RagnarokKey read on the render thread"));
        }
        boolean installed = KeyFeatures.installed(KEY_FEATURE);
        if (!installed && !earlyReadWarned && !FMLEnvironment.production) {
            tripEarlyRead();
        }
        return installed;
    }

    /**
     * Dev-only guard: logs one stack trace when {@link #present()} is read while the key mod is loading but has not
     * finished installing its hooks yet (its container exists and has no mod instance). Such a read answers false on
     * a keyed server, so the caller must read later instead. Keyless runs and a fully constructed key never trip it.
     */
    private static void tripEarlyRead() {
        try {
            ModList list = ModList.get();
            if (list == null) return;
            Optional<? extends ModContainer> c = list.getModContainerById(KEY_MODID);
            if (c.isEmpty() || c.get().getMod() != null) return;
            earlyReadWarned = true;
            LOGGER.warn("[{}] RagnarokKey.present() was read before the Ragnarok Key finished installing its hooks; "
                    + "it answers false here. Read it later, never at construction. Stack trace so the call site "
                    + "can be fixed:", DmzNpc.MODID, new Throwable("early RagnarokKey read"));
        } catch (Throwable ignored) {
            // A diagnostic must never break a key read.
        }
    }

    /**
     * Whether key-gated features unlock here. As of the "private UI is not rendered without the key" pass this
     * is exactly {@link #present()}: there is NO singleplayer/LAN exemption any more.
     *
     * <p>The owner's rule is that private features do not appear without the key, singleplayer included, so the
     * old {@code !isDedicatedServer()} shortcut is gone. Server-side and common code calls this. Client code must
     * NOT: with the exemption removed this is false on every physical client (the client cannot see the server's
     * key), so a client gate must read {@link ClientGate}, which the server states over the login sync.
     */
    public static boolean unlocked() {
        return present();
    }

    /** The inverse of {@link #unlocked()}: this instance is running the restricted public feature set. */
    public static boolean restricted() {
        return !present();
    }

    /** One status line per boot, no matter how many trees ask. */
    public static void logStatusOnce() {
        if (logged) return;
        logged = true;
        if (present()) {
            LOGGER.info("[{}] Ragnarok Key found, private features unlocked.", DmzNpc.MODID);
        } else {
            LOGGER.warn("[{}] Ragnarok Key not detected, public feature set, this is not a bug.", DmzNpc.MODID);
        }
    }
}
