package net.shurui.shuruisutilities.core.config;

// toggle helper for features that aren't standalone @SUModule modules (prestige, character slots, CustomNPCs
// economy, mini clone). the operator switchboard was removed in batch M, so these are simply on; any private one
// is still gated by PublicContent / the Ragnarok Key at its own site (e.g. mini clone reads
// PublicContent.allows(FEATURE_MINI_CLONE)). the constants stay because callers reference them.
public final class Features
{
    private Features() {}

    public static final String PRESTIGE = "Prestige";
    public static final String CHARACTER_SLOTS = "CharacterSlots";
    public static final String CUSTOMNPCS_ECONOMY = "CustomNpcsEconomy";
    public static final String MINI_CLONE = "MiniClone";

    // Always enabled since batch M removed the switchboard. Kept so the many call sites read the same.
    public static boolean enabled(String key)
    {
        return true;
    }

    // No longer seeds anything: there is no switchboard file to register keys into. Kept as a no-op for its caller.
    public static void seed()
    {
    }
}
