package net.shurui.shuruisutilities.subrace.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.shurui.shuruisutilities.corrupted.RaceUnlocks;
import net.shurui.shuruisutilities.corrupted.client.RaceUnlockClient;
import net.shurui.shuruisutilities.subrace.SubRaces;

/**
 * Client-side helper that turns a parent race id into the list of sub-race options the local player may SEE, using the
 * fail-closed {@link RaceUnlockClient} entitlement cache. Shared by the DMZ race-select filter mixin (which hides
 * sub-races and unearned gated races) and the SU sub-race screen (which lists the parent plus the unlocked sub-races).
 *
 * <p>{@link RaceUnlocks} is referenced only for its pure, side-effect-free classifiers ({@code isRaceUnlockGated},
 * {@code FREE_SUB_RACES}); none of its {@code ServerPlayer}/permission paths are touched here, so this class is safe to
 * classload on the client.
 */
public final class SubRaceClient
{
    private SubRaceClient() {}

    /**
     * The ordered list of options for a parent race that the local player can see: the parent id itself first, then
     * every registered sub-race of that parent the player is allowed to pick. A sub-race is allowed when it is a free
     * sub-race ({@code half_saiyan}) or the entitlement cache says the player owns it. Ordering follows
     * {@link SubRaces#subRacesOf} (the model-cycle order). The parent id is always index 0. Never null.
     */
    public static List<String> visibleOptions(String parentId)
    {
        List<String> out = new ArrayList<>();
        if (parentId == null)
            return out;
        String parent = parentId.toLowerCase(Locale.ROOT);
        out.add(parent);
        for (String sub : SubRaces.subRacesOf(parent))
        {
            if (canSee(sub))
                out.add(sub);
        }
        return out;
    }

    /**
     * True when the local player may see/select a sub-race id: a free (non-gated) sub-race is always visible; a gated
     * sub-race is visible only when the fail-closed entitlement cache reports it owned. Case-insensitive.
     */
    public static boolean canSee(String subRaceId)
    {
        if (subRaceId == null)
            return false;
        String id = subRaceId.toLowerCase(Locale.ROOT);
        if (!RaceUnlocks.isRaceUnlockGated(id))
            return true; // free sub-race (half_saiyan) or non-gated
        return RaceUnlockClient.isUnlocked(id);
    }

    /**
     * True when the sub-race screen should open for this parent: only when there are at least TWO visible options
     * (the parent plus one or more unlocked sub-races). A parent with no sub-races, or with sub-races but none the
     * player has unlocked, yields a single-option list and must NOT open a one-choice screen; DMZ proceeds straight to
     * customization in those cases.
     */
    public static boolean shouldOpenScreen(String parentId)
    {
        return visibleOptions(parentId).size() >= 2;
    }
}
