package net.shurui.dev.sdu.client.hud.ragnarok;

/**
 * Every HUD element the suite places through DragonMineZ's HUD layout system.
 *
 * <p>DMZ's elements are a Java enum ({@code HudElement}), so the suite's elements are appended to that enum ONCE, in
 * its static initialiser, by {@code DmzHudElementMixin}. That runs before anything can register a renderer, which is
 * why this list is fixed at compile time and carries no Minecraft or DMZ types: a module that is not installed simply
 * never binds its slot, and an unbound slot is neither drawn nor listed in the editor.
 *
 * <p>{@code extra} slots live in DMZ's shared "extras" layout group (shown in every style, under the editor's Extras
 * page, like DMZ's notifications and scouter). The rest belong to the Ragnarok style only and replace DMZ's main HUD.
 *
 * <p>The ids are persisted in DMZ's {@code hud_layout.json}; never rename one.
 */
public enum RagnarokHudSlot
{
    STATS("rg_stats", false),
    CURRENCY("rg_currency", false),
    COMPASS("rg_compass", true),
    QUEST_TRACKER("rg_quest_tracker", true),
    REGION("rg_region", true),
    STAFF_TASK("rg_staff_task", true),
    RAID_BAR("rg_raid_bar", true),
    PLANET_INFO("rg_planet_info", true),
    CLASH("rg_clash", true),
    RACE_STANDINGS("rg_race_standings", true),
    RACE_ITEM("rg_race_item", true);

    /** The DMZ layout group name, and the value of {@code HudLayoutConfig.style}, of the Ragnarok style. */
    public static final String STYLE_NAME = "ragnarok";
    /** Name of the HudStyle constant appended to DMZ's style enum. */
    public static final String STYLE_ENUM_NAME = "RAGNAROK";

    private static final RagnarokHudSlot[] VALUES = values();
    // ordinal of the first appended constant in DMZ's HudElement enum, set by the mixin; -1 until then
    private static int firstDmzOrdinal = -1;

    private final String id;
    private final boolean extra;

    RagnarokHudSlot(String id, boolean extra)
    {
        this.id = id;
        this.extra = extra;
    }

    /** Persisted element id (also the lang key suffix DMZ's editor shows). */
    public String id()
    {
        return id;
    }

    /** True for slots in DMZ's shared extras group, false for the Ragnarok style's own main elements. */
    public boolean extra()
    {
        return extra;
    }

    /** Name of the appended HudElement constant. */
    public String enumName()
    {
        return "RAGNAROK_" + name();
    }

    /** Called once by the HudElement mixin with the ordinal its first appended constant received. */
    public static void markFirstOrdinal(int ordinal)
    {
        firstDmzOrdinal = ordinal;
    }

    /** The slot for a DMZ HudElement ordinal, or null for DMZ's own elements. */
    public static RagnarokHudSlot byDmzOrdinal(int ordinal)
    {
        if (firstDmzOrdinal < 0 || ordinal < firstDmzOrdinal)
            return null;
        int i = ordinal - firstDmzOrdinal;
        return i < VALUES.length ? VALUES[i] : null;
    }

    public static RagnarokHudSlot byId(String id)
    {
        for (RagnarokHudSlot slot : VALUES)
            if (slot.id.equals(id))
                return slot;
        return null;
    }
}
