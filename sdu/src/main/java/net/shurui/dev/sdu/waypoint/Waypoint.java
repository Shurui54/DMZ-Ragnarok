package net.shurui.dev.sdu.waypoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

// one HUD compass marker: a named point in a dimension. two flavours: manual (set via /rg npc waypoint, persisted
// per player in WaypointStore) and quest (derived each tick from the tracked DMZ quest, transient).
//
// A MANUAL waypoint is only ever drawn in its own dimension: it is a pin somebody dropped on a spot, and it means
// nothing from another world. A QUEST waypoint is drawn everywhere, because a mission you are on is a mission you
// are on wherever you happen to be standing; in the wrong dimension the compass stops pointing and names the
// dimension to travel to instead (see CompassOverlay).
public final class Waypoint {

    public static final int COLOR_MANUAL = 0xFF55FFFF;
    public static final int COLOR_QUEST = 0xFFFFD24A;

    public final String dim;
    public final double x;
    public final double y;
    public final double z;
    public final String name;
    public final int color;
    public final boolean quest;  // true = quest-derived (transient); false = manual (persisted)
    // optional item id (e.g. minecraft:chest) rendered instead of the coloured triangle. blank = triangle.
    public final String icon;

    /**
     * A destination with NO position: all it carries is which dimension the objective is in. DMZ objectives
     * with no world coordinate (a bare "be in the Nether") used to produce no waypoint, so the player got a
     * blank compass. x/y/z are meaningless here and must never be drawn as a bearing; the client drops it the
     * moment the player is in {@link #dim}.
     */
    public final boolean travel;

    /**
     * Which commissioned pin this marker wears, and therefore its beam colour in world.
     * {@link WaypointMark#NONE} for anything not given a kind, so markers predating the pins draw as before.
     */
    public final WaypointMark mark;

    /**
     * Whether this marker also raises a world beacon beam and floating pin (client {@code WaypointMarkerRenderer}),
     * as opposed to living only on the HUD compass bar. Default false everywhere. Set true for an authored COORDS
     * quest objective (a fixed operator-placed location) and the airdrop. A structure/biome/kill/interact target
     * does NOT get one: those move (an NPC wanders, a nearer copy is found), and a beam chasing a wandering entity
     * is the "respawning on the NPC" complaint. The compass bar reads {@link #mark} directly and ignores this flag.
     */
    public final boolean beacon;

    /**
     * Trailing text for a marker with NO world position: a task's progress ("3/10"), a raid's "sign-ups open".
     * Blank on every ordinary marker. A notice shows on the quest tracker and nowhere else: no beam, no pin, no
     * compass-bar slot, because there is nothing to point at. {@link #travel} knows a dimension but not a
     * position; this knows neither.
     */
    public final String note;

    public Waypoint(String dim, double x, double y, double z, String name, int color, boolean quest) {
        this(dim, x, y, z, name, color, quest, "");
    }

    public Waypoint(String dim, double x, double y, double z, String name, int color, boolean quest, String icon) {
        this(dim, x, y, z, name, color, quest, icon, false, WaypointMark.NONE, false);
    }

    public Waypoint(String dim, double x, double y, double z, String name, int color, boolean quest, String icon,
            boolean travel, WaypointMark mark) {
        this(dim, x, y, z, name, color, quest, icon, travel, mark, false);
    }

    public Waypoint(String dim, double x, double y, double z, String name, int color, boolean quest, String icon,
            boolean travel, WaypointMark mark, boolean beacon) {
        this(dim, x, y, z, name, color, quest, icon, travel, mark, beacon, "");
    }

    public Waypoint(String dim, double x, double y, double z, String name, int color, boolean quest, String icon,
            boolean travel, WaypointMark mark, boolean beacon, String note) {
        this.note = note == null ? "" : note;
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.name = name == null ? "" : name;
        this.color = color;
        this.quest = quest;
        this.icon = icon == null ? "" : icon;
        this.travel = travel;
        this.mark = mark == null ? WaypointMark.NONE : mark;
        this.beacon = beacon;
    }

    /**
     * A quest marker wearing one of the pins; its colour follows the pin so bar, icon and beam agree.
     * {@code beacon} asks for a world beam, which only an authored COORDS objective should request.
     */
    public static Waypoint quest(String dim, double x, double y, double z, String name, WaypointMark mark,
            boolean beacon) {
        return new Waypoint(dim, x, y, z, name, mark.hasPin() ? mark.color : COLOR_QUEST, true, "", false, mark,
                beacon);
    }

    /** A "the mission is in another dimension" marker: a target dimension and an objective name, no position. */
    public static Waypoint travelTo(String dim, String name, WaypointMark mark) {
        return new Waypoint(dim, 0, 0, 0, name, mark.hasPin() ? mark.color : COLOR_QUEST, true, "", true, mark, false);
    }

    /**
     * A marker with no location: a row on the quest tracker and nowhere else. {@code note} is drawn where a
     * distance would go, so keep it short: a count, a state, a countdown.
     */
    public static Waypoint notice(String name, WaypointMark mark, String note) {
        return new Waypoint("", 0, 0, 0, name, mark.hasPin() ? mark.color : COLOR_QUEST, true, "", false, mark,
                false, note == null ? "" : note);
    }

    /** True when this marker has no position and is only ever a tracker row. */
    public boolean isNotice() {
        return !note.isEmpty();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(dim);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeUtf(name);
        buf.writeInt(color);
        buf.writeBoolean(quest);
        buf.writeUtf(icon);
        buf.writeBoolean(travel);
        buf.writeVarInt(mark.ordinal());
        buf.writeBoolean(beacon);
        // Appended last on purpose: every field above keeps its position, so this cannot shift an existing one.
        buf.writeUtf(note);
    }

    public static Waypoint decode(FriendlyByteBuf buf) {
        return new Waypoint(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                buf.readUtf(), buf.readInt(), buf.readBoolean(), buf.readUtf(), buf.readBoolean(),
                WaypointMark.byOrdinal(buf.readVarInt()), buf.readBoolean(), buf.readUtf());
    }

    // Only manual waypoints are persisted, so NBT never needs the transient quest colour/flag.

    public CompoundTag toNbt() {
        CompoundTag t = new CompoundTag();
        t.putString("dim", dim);
        t.putDouble("x", x);
        t.putDouble("y", y);
        t.putDouble("z", z);
        t.putString("name", name);
        return t;
    }

    public static Waypoint fromNbt(CompoundTag t) {
        return new Waypoint(t.getString("dim"), t.getDouble("x"), t.getDouble("y"), t.getDouble("z"),
                t.getString("name"), COLOR_MANUAL, false);
    }
}
