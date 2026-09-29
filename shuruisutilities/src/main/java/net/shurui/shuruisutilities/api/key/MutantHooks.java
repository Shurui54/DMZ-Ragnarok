package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Core-side hook for the PRIVATE mutant nerf and its Joke (OWNER-SPECS 3: "MutantNerf (+ Joke)"; the decision lives in
 * the Ragnarok Key, {@code dmz_ragnarok_key}). The four DMZ mixins stay in core ({@code MixinDmzMutantForms},
 * {@code MixinDmzMutantStats}, {@code MixinDmzMutantMastery}, {@code MixinDmzMutantAnnounce}) because the key carries
 * no mixins. On the SERVER they ask {@link #nerfActive()}; on the CLIENT (the form screens and the TP tooltip read
 * the local {@code StatsData}) they ask {@link #clientNerfActive()}, the server's own {@link #nerfActive()} answer as
 * synced at login (core registers it with {@code DmzNet.answerFeature}, so the {@link #FEATURE_ID} id reaches a client
 * only when the installed hook says the nerf is in force; a jar that merely marks the id turns nothing on).
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: the nerf is off, so a keyless server keeps DragonMineZ's own
 * mutant bonuses and its own "you became a mutant" message, exactly as before.
 *
 * <p>Read LAZILY at the point of use (mod construction is parallel; the key may install after a reader loads).
 */
public final class MutantHooks
{
    /** The {@link KeyFeatures} id this hook marks on install (also the client's {@code ClientGate.feature} id). */
    public static final String FEATURE_ID = "mutantnerf";

    private MutantHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the mutant nerf and the Joke are in force on this server. Keyless: false. */
        default boolean nerfActive()
        {
            return false;
        }
    }

    /** The keyless default until the key installs its own. Never null. */
    private static volatile Impl impl = new Impl() {};

    /** Install the key's implementation and mark the feature. Called once from {@code RagnarokKeyMod}. */
    public static void install(Impl i)
    {
        if (i == null)
            return;
        impl = i;
        KeyFeatures.mark(FEATURE_ID);
    }

    /** The live implementation (never null: the keyless default until the key installs). */
    public static Impl get()
    {
        return impl;
    }

    /** Server side: whether the mutant nerf and the Joke are in force. Never read this on the client. */
    public static boolean nerfActive()
    {
        return impl.nerfActive();
    }

    /**
     * Client side: the connected server's {@link #nerfActive()} answer, as synced at login (the {@link #FEATURE_ID}
     * id is sent only when the server's installed hook answers true). False until the sync arrives and after logout.
     */
    public static boolean clientNerfActive()
    {
        return net.shurui.dev.sdu.api.ClientGate.feature(FEATURE_ID);
    }
}
