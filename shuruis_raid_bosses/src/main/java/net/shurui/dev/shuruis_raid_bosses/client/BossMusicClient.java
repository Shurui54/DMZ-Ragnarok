package net.shurui.dev.shuruis_raid_bosses.client;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Client owner of the boss music loop.
 *
 * <p>The server says, per fighter, when the fight is on ({@link #start}) and when it ends ({@link #stop}); this
 * decides whether a sound actually plays, honouring the player's {@code /bossmusic} toggle and keeping exactly
 * ONE loop at a time (a start for the track already playing is ignored, so it never stacks or restarts). It
 * holds the track the server last asked for so the toggle can start or cut it mid fight without another packet.
 *
 * <p>A track whose sound is not defined degrades to silence with a single log line, never a crash. A track that
 * is defined but whose file is absent is the sound engine's own (single, non fatal) miss.
 */
public final class BossMusicClient {
    private BossMusicClient() {}

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    /** The track the server wants playing, or null for none. Survives a toggle so re-enabling can resume it. */
    private static String desiredId;
    /** The loop actually playing, and its id, or null when silent. */
    private static BossMusicSoundInstance current;
    private static String currentId;
    /** ids already logged as undefined, so a missing track warns once, not once per fight. */
    private static final Set<String> warned = new HashSet<>();

    /** Server: a fighter is in the fight. Remember the track and (re)evaluate what should play. */
    public static void start(String soundId) {
        desiredId = (soundId == null || soundId.isBlank()) ? null : soundId;
        apply();
    }

    /** Server: the fight ended for this client. Cut the loop cleanly. */
    public static void stop() {
        desiredId = null;
        apply();
    }

    /** {@code /bossmusic on|off}: start or cut the loop at once if a fight is under way. */
    public static void setEnabled(boolean enabled) {
        BossMusicOptions.setEnabled(enabled);
        apply();
    }

    /** Leaving the world tears everything down: drop the track so nothing resumes on the next join. */
    public static void onWorldLeave() {
        desiredId = null;
        apply();
    }

    /** Whether a loop is currently playing, used to hush vanilla background music. */
    public static boolean isPlaying() {
        return current != null;
    }

    /** The sound instance polls this each tick and stops itself once it is no longer the wanted loop. */
    public static boolean isCurrent(BossMusicSoundInstance instance) {
        return instance != null && instance == current;
    }

    private static void apply() {
        Minecraft mc = Minecraft.getInstance();
        boolean wantPlay = desiredId != null && BossMusicOptions.enabled();

        if (!wantPlay) {
            cut(mc);
            return;
        }
        // Already playing exactly this track: leave it, never stack or restart.
        if (current != null && desiredId.equals(currentId)) {
            return;
        }
        cut(mc);

        SoundEvent event = resolve(mc, desiredId);
        if (event == null) {
            return; // undefined track: logged once in resolve(), silence is the graceful outcome
        }
        BossMusicSoundInstance instance = new BossMusicSoundInstance(event);
        current = instance;
        currentId = desiredId;
        mc.getSoundManager().play(instance);
    }

    private static void cut(Minecraft mc) {
        if (current != null) {
            mc.getSoundManager().stop(current);
            current = null;
            currentId = null;
        }
    }

    /**
     * Resolve a sound id to an event, or null when nothing by that id is defined (a single log line, then
     * silence). A registered event is used directly; anything else is checked against the loaded sounds.json so
     * a plain vanilla or operator typed id still works, and a genuinely unknown id is reported once.
     */
    private static SoundEvent resolve(Minecraft mc, String id) {
        ResourceLocation rl;
        try {
            rl = new ResourceLocation(id);
        } catch (RuntimeException e) {
            warnOnce(id, "not a valid sound id");
            return null;
        }
        SoundEvent registered = ForgeRegistries.SOUND_EVENTS.getValue(rl);
        if (registered != null) {
            return registered;
        }
        SoundManager sounds = mc.getSoundManager();
        if (sounds.getSoundEvent(rl) == null) {
            warnOnce(id, "no sound is defined for it");
            return null;
        }
        return SoundEvent.createVariableRangeEvent(rl);
    }

    private static void warnOnce(String id, String why) {
        if (warned.add(id)) {
            LOGGER.warn("[Boss Music] Encounter music '{}' will not play: {}. Playing nothing.", id, why);
        }
    }
}
