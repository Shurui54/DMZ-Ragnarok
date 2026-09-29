package net.shurui.shuruisutilities.cosmetics.wardrobe;

import net.shurui.shuruisutilities.hub.EditorServer;
import net.shurui.shuruisutilities.hub.HubRows;

/**
 * The player "wardrobe" hub row, the one cosmetics row that stays in core (S17a): the public Patreon wardrobe (S17p)
 * lists and wears Patreon-granted copies through it without the Ragnarok Key. The private cosmetics rows (the admin
 * catalogue, crate and shop editors, the mounts, animations and shop screens) are registered by the key. The Patreon
 * cosmetics menu ("cosmetics") and form cosmetics ("formcosmetics") stay in HubServer; the menu reads each row's
 * {@code available} flag through {@link HubRows#available}.
 */
public final class HubRowWardrobe
{
    private HubRowWardrobe() {}

    public static void register()
    {
        // The wardrobe is reached from the cosmetics menu and from /cosmetic wardrobe. Gated on the module
        // only: owning the cosmetic is the real gate, and a player with nothing owned sees an empty list
        // rather than a refusal, which is the honest answer.
        // wearActive, not active: without the key the row stays up for the public Patreon wardrobe (S17p), which
        // lists and wears only Patreon-granted copies. With the key the two answers are the same.
        HubRows.row("wardrobe")
                .available(() -> WardrobeManager.wearActive())
                .hub(p -> {
                    if (WardrobeManager.wearActive())
                        EditorServer.open(p, "wardrobe");
                })
                .editor(p -> CosmeticEditorServer.openWardrobe(p))
                // the wardrobe always re-sends itself so a refused equip shows the unchanged truth rather than
                // nothing at all.
                .action((p, action, args) -> { CosmeticEditorServer.handleWardrobe(p, action, args); return true; })
                .register();
    }
}
