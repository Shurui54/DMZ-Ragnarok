package net.shurui.dev.sdu.api;

import net.minecraftforge.fml.ModList;

/**
 * The ONE place that answers "is module X installed" for the core-plus-modules split.
 *
 * <p>Presence must give the same answer whether a module arrived as its own jar or inside the fat jar: the
 * fat jar declares the module container ids too, so {@link ModList#isLoaded(String)} on the id below is
 * correct either way. Route every module-presence check through here (never hardcode a module id at a call
 * site), so the answer stays consistent and a future packaging change is a one-line edit. This is a
 * presence check only: whether a feature actually runs is still decided on top of it by the module
 * switchboard ({@code SuiteModules} / modules.cfg) and the key gating.
 *
 * <p>For a specific cross-module capability prefer the dedicated core hook ({@link DungeonArenaHook},
 * {@link ZSoulHook}) whose {@code available()} reports the same presence AND gives the callable API; use
 * this class for a plain yes/no.
 */
public final class ModulePresence {

    public static final String CORE = "dmz_ragnarok";
    public static final String DUNGEONS = "dmz_ragnarok_dungeons";
    public static final String RAIDS = "dmz_ragnarok_raids";
    public static final String TOURNAMENTS = "dmz_ragnarok_tournaments";
    public static final String SPACE = "dmz_ragnarok_space";

    private ModulePresence() {
    }

    /** Core is mandatory for every module, so this is effectively always true where a module runs. */
    public static boolean core() {
        return ModList.get().isLoaded(CORE);
    }

    public static boolean dungeons() {
        return ModList.get().isLoaded(DUNGEONS);
    }

    public static boolean raids() {
        return ModList.get().isLoaded(RAIDS);
    }

    public static boolean tournaments() {
        return ModList.get().isLoaded(TOURNAMENTS);
    }

    public static boolean space() {
        return ModList.get().isLoaded(SPACE);
    }

    /** Generic form for a suite container id (one of the constants above). */
    public static boolean loaded(String suiteContainerId) {
        return ModList.get().isLoaded(suiteContainerId);
    }
}
