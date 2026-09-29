package net.shurui.shuruisutilities.api.key;

import net.shurui.dev.sdu.api.KeyFeatures;

/**
 * Presence of the PRIVATE saibaman pet editor (S19a), whose logic lives in the Ragnarok Key ({@code dmz_ragnarok_key}):
 * the {@code SaibamanPets} module, the "saibamanpets" admin hub row and the {@code su:cfg_saibaman_pet} shard state.
 * The seed-to-pet path is PUBLIC and stays in core: the seed, the crop, the pet entity, and {@code SaibamanPet.toml}
 * itself, which core loads and bakes into {@code ConfigSaibamanPet} on every server (keyed and keyless) so a pet reads
 * the same stats either way. Only editing those stats in game is private.
 *
 * <p>The {@link Impl} DEFAULT is the keyless behaviour: {@link #available()} is false, and there is no editor.
 */
public final class SaibamanPetHooks
{
    /** The {@link KeyFeatures} id this hook marks on install. */
    public static final String FEATURE_ID = "saibamanpets";

    private SaibamanPetHooks() {}

    /** The behaviour the key installs. Every method has a keyless default. */
    public interface Impl
    {
        /** Whether the pet editor is installed (the key installed it). Keyless: false. */
        default boolean available()
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

    /** Whether the pet editor is installed on this server. */
    public static boolean available()
    {
        return impl.available();
    }
}
