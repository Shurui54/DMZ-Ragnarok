package net.shurui.shuruisutilities.hub;

/**
 * The one place core registers the feature hub rows ({@link HubRows}), run once when HubRows is first used.
 *
 * <p>Each line belongs to one private feature. When a batch moves that feature into the Ragnarok Key, it moves the
 * feature's {@code HubRow<Feature>} class with it, deletes the line here, and registers the rows from the key's
 * install() instead; HubServer and EditorServer never change for it. Order does not matter: every row has its own
 * key.
 */
final class CoreHubRows
{
    private CoreHubRows() {}

    static void register()
    {
        net.shurui.shuruisutilities.cosmetics.wardrobe.HubRowWardrobe.register();
    }
}
