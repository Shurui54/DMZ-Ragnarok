package net.shurui.shuruisutilities.dragons;

import java.util.ArrayList;
import java.util.List;

/**
 * The shadow dragon race ids, and the rule that Omega inherits every dragon's move.
 *
 * <p>No DMZ imports, so this is safe to touch from anywhere. The ids themselves are the DMZ race folder names
 * shipped in {@code resources/races/}; they are persisted on every character, so they are frozen strings.
 */
public final class DragonRaces
{
    private DragonRaces() {}

    /** The base shadow dragon race, which is Omega. Its transformation is what unlocks the whole kit. */
    public static final String OMEGA_RACE = "shadow_dragon";

    /**
     * The race allowed to use one move: the owning dragon, plus Omega.
     *
     * <p>This single list IS the "Omega gains all abilities of all other dragons" rule. Adding the base race to
     * every move's allowed list means DMZ's own race filter grants Omega the entire kit, with no separate unlock
     * pass to run and nothing to keep in step when a move is added.
     */
    public static List<String> allowedRacesFor(DragonMove move)
    {
        List<String> races = new ArrayList<>(2);
        races.add(move.race);
        if (!OMEGA_RACE.equals(move.race))
            races.add(OMEGA_RACE);
        return races;
    }
}
