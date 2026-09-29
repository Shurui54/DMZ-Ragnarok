package net.shurui.shuruisutilities.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.KeyMapping;
import net.shurui.dev.sdu.api.ClientGate;

/**
 * The keybinds of PRIVATE features, and whether the connected server opened each one (OWNER-SPECS section 4).
 *
 * <p>A private keybind is left out of the Controls screen (MixinKeyBindsListHidePrivate builds the list from
 * {@link #visible(KeyMapping[])}) and is inert in {@link SUKeybinds}. It is NEVER unregistered: the mapping stays in
 * {@code Options.keyMappings}, so options.txt keeps loading and saving the player's binding, and a keyed server shows
 * the row again with the binding intact. The answer is the synced one (ClientGate), never a client-side key check.
 */
public final class PrivateKeybinds
{
    private PrivateKeybinds() {}

    /** Whether this mapping belongs to a private feature the connected server has NOT opened. */
    public static boolean hidden(KeyMapping key)
    {
        if (key == null)
            return false;
        // licence-gated: NPC regions (map overlay toggle, region HUD mover), the task board, the dragon ball bag
        if (key == SUKeybinds.TOGGLE_REGION_OVERLAY || key == SUKeybinds.MOVE_REGION_HUD
                || key == SUKeybinds.OPEN_TASKS || key == SUKeybinds.OPEN_DRAGONBALL_BAG)
            return !ClientGate.key();
        // key features: the god roles and racing
        if (key == SUKeybinds.ROLE_ABILITY)
            return !ClientGate.feature(net.shurui.shuruisutilities.api.key.RoleHooks.FEATURE_ID);
        if (key == SUKeybinds.RACE_STEER_LEFT || key == SUKeybinds.RACE_STEER_RIGHT
                || key == SUKeybinds.RACE_ACCELERATE || key == SUKeybinds.RACE_BRAKE
                || key == SUKeybinds.RACE_MINIMAP)
            return !ClientGate.feature(net.shurui.shuruisutilities.api.key.RaceHooks.FEATURE_ID);
        return false;
    }

    /** A copy of {@code all} without the hidden private mappings; the array itself when nothing is hidden. */
    public static KeyMapping[] visible(KeyMapping[] all)
    {
        if (all == null)
            return null;
        List<KeyMapping> out = new ArrayList<>(all.length);
        for (KeyMapping key : all)
            if (!hidden(key))
                out.add(key);
        return out.size() == all.length ? all : out.toArray(new KeyMapping[0]);
    }
}
