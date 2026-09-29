package net.shurui.shuruisutilities.ragnarok;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts the ragnarok characters INTO an entity dropdown, instead of beside it.
 *
 * <h2>The problem this solves, and the wrong answer it replaces</h2>
 * All 386 ragnarok NPCs, the ninjin cast among them, share ONE registered entity type
 * ({@code dmz_ragnarok:rgnpc}); which character an instance is comes from a field on the entity. So an editor
 * that fills its dropdown from the entity-type registry shows the whole cast as a SINGLE row called "rgnpc",
 * and picking it gives the default character every time.
 *
 * <p>The first attempt at fixing that added a second dropdown next to the entity one: pick {@code rgnpc} in the
 * first, then a character in the second. That is not what "the ragnarok NPCs are missing from the NPC dropdown"
 * asks for. Nobody opening an NPC picker wants to learn that a hundred of the NPCs live behind a different
 * control; they want to open the list and see them. So the characters go in the list.
 *
 * <h2>How a character rides in a list of entity ids</h2>
 * As a synthetic option {@code dmz_ragnarok:rgnpc#<character>}. It sorts directly under the plain {@code rgnpc}
 * row, so the whole cast sits together, and the dropdowns filter by case-insensitive SUBSTRING, so typing a
 * character name finds it without knowing the prefix.
 *
 * <p>Nothing persists that string. {@link #entityOf} and {@link #modelOf} split it back into the two fields
 * every config already has (an entity type id and a character id), so saved data, the network codecs and every
 * spawn path are untouched: this is a presentation of two fields as one control, not a new format.
 */
public final class RgNpcPicker
{
    /**
     * The type a picked character SPAWNS AS: the combat one.
     *
     * <p>An editor picking a character is choosing an opponent, and the display NPC is a plain {@code Mob} with
     * no goals, so choosing one used to produce a character standing still with no animation, no AI and nothing
     * the stat fields could drive. {@link RgNpcFighterEntity} is the same cast on DragonMineZ's saga chassis.
     */
    public static final String FIGHTER_ID = "dmz_ragnarok:rgnpc_fighter";

    /** The display NPC's type. Still recognised on the way IN, so configs saved against it keep their character. */
    public static final String RGNPC_ID = "dmz_ragnarok:rgnpc";

    /**
     * Separator between the entity id and the character. A {@code #} because it cannot appear in a
     * {@link net.minecraft.resources.ResourceLocation}, so a synthetic option can never collide with, or be
     * mistaken for, a real entity id.
     */
    public static final String SEP = "#";

    private RgNpcPicker() {}

    /**
     * {@code entityIds} with every ragnarok character added as its own option, sorted.
     *
     * <p>The FULL table rather than the key-filtered one, matching the other operator-facing pickers: the server
     * re-resolves whatever is chosen at spawn time, so filtering here would only hide ids from the person doing
     * the configuring.
     */
    public static List<String> options(List<String> entityIds)
    {
        List<String> out = new ArrayList<>(entityIds == null ? List.of() : entityIds);
        try
        {
            for (String id : RgNpcModels.ids())
                out.add(FIGHTER_ID + SEP + id);
        }
        catch (Throwable ignored)
        {
            // No table, no extra rows. The dropdown is still every entity type, exactly as it was.
        }
        out.sort(String::compareToIgnoreCase);
        return out;
    }

    /**
     * The option that represents an {@code (entityTypeId, rgModelId)} pair, for preselecting the dropdown.
     *
     * <p>A character only shows when the entity actually IS one of the two ragnarok types, so a stale character
     * left on a config whose entity was later changed to something else cannot make the dropdown claim otherwise.
     *
     * <p>A config saved against the DISPLAY type is shown as the row for that character, which is now the fighter
     * row. The old config keeps working exactly as it did until somebody re-picks; re-picking is what upgrades it.
     */
    public static String value(String entityTypeId, String rgModelId)
    {
        String entity = entityTypeId == null ? "" : entityTypeId.trim();
        String model = rgModelId == null ? "" : rgModelId.trim();
        if (model.isEmpty() || !(FIGHTER_ID.equals(entity) || RGNPC_ID.equals(entity)))
            return entity;
        return FIGHTER_ID + SEP + model;
    }

    /** The entity type id an option names. A plain entity id is returned unchanged. */
    public static String entityOf(String option)
    {
        if (option == null)
            return "";
        int at = option.indexOf(SEP);
        return at < 0 ? option.trim() : option.substring(0, at).trim();
    }

    /** The character an option names, or blank when it names a plain entity type. */
    public static String modelOf(String option)
    {
        if (option == null)
            return "";
        int at = option.indexOf(SEP);
        return at < 0 ? "" : option.substring(at + SEP.length()).trim();
    }
}
