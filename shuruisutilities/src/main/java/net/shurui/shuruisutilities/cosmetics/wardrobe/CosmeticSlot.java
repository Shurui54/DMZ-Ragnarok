package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where a wearable cosmetic sits on the player. One slot holds at most one cosmetic at a time.
 *
 * <h2>The key, never the ordinal</h2>
 * Every persisted and wire form of a slot is its {@link #key}, a stable lowercase string, so the order of the
 * constants here carries no meaning and declaring a new one can never shift an existing record onto a different
 * slot. {@link #byKey} never throws: an unknown key reads as {@link #HEAD} rather than failing a whole load, the
 * same discipline {@code TaskGoal.byKey} uses. The counter-example to avoid is {@code NpcSpawnConfig}, which
 * persists ordinals and therefore has a comment warning that two arrays must stay in step forever.
 *
 * <h2>BODY is declared but switched off</h2>
 * {@link #BODY} exists now and is the only constant whose {@link #enabled} is false. The owner deferred body
 * cosmetics, and declaring the slot now is what stops turning them on later from being a data migration: every
 * place that enumerates slots (the editor dropdown, the wardrobe grid, the sync codec, the future render layer)
 * iterates {@link #active()} rather than {@link #values()}, so flipping that one boolean to true is the entire
 * change. Existing records are untouched because nothing anywhere stores a slot by position.
 *
 * <h2>THE SET IS EXPECTED TO GROW. ADDING ONE MUST STAY FREE.</h2>
 * The owner has already named a PET slot and cosmetic KI WEAPONS beyond the four here, and more will follow, so
 * the cost of a new slot is a design constraint rather than an afterthought. Adding one is, and must remain,
 * ONE constant in this enum plus one lang key. Nothing else in the feature may need touching, which holds
 * because of four rules that are all in force today:
 *
 * <ul>
 *   <li>Every persisted and wire form of a slot is the {@link #key} string. Nothing writes an ordinal, so
 *       inserting a constant in the middle cannot reinterpret a stored record.</li>
 *   <li>{@link #active()} and {@link #activeKeys()} are the ONLY enumeration points. The editor's slot picker,
 *       the list screen's New control, the wardrobe grid, the command's slot suggestions and the editor's sort
 *       order all go through them, so a new slot appears in every one of those without a second edit.</li>
 *   <li>Nothing switches exhaustively over this enum, and nothing asks how many constants there are.
 *       {@link #byKey} is a loop with a safe fallback, never a lookup by index.</li>
 *   <li>The wire format writes slots as a counted list of key strings, never a fixed-width block, so a client
 *       and a server disagreeing about how many slots exist degrade rather than desynchronise.</li>
 * </ul>
 *
 * <p>{@link #PET} was added on 2026-09-21 and cost exactly that: one constant here and one lang key. The four
 * rules above were re-verified first rather than assumed. It is a selector, not a summon: see its own note.
 *
 * <p>KI_WEAPON is deliberately NOT declared yet, and that costs nothing precisely because of the rules above:
 * unlike an ordinal-keyed enum, this one has no reason to reserve space. It is still an open design question
 * with the owner (a cosmetic ki weapon may be a skin applied to DragonMineZ's own ki weapon rather than a slot
 * at all), and declaring a slot before that is settled would be a guess baked into the data.
 */
public enum CosmeticSlot
{
    /** Hats, masks, horns, anything drawn off the head. */
    HEAD("head", true),

    /** Wings, capes, backpacks, anything drawn off the upper body pointing backwards. */
    BACK("back", true),

    /** The catch-all: held trinkets, shoulder pets, orbiting bits. */
    ACCESSORY("accessory", true),

    /**
     * The creature that follows you around. SHIPPED AS A CHOICE, NOT AS AN ENTITY.
     *
     * <p>What this slot means today is exactly what every other slot means: the wardrobe records which cosmetic
     * the player has selected, syncs it and shows it. Nothing summons anything. The entity that eventually walks
     * behind the player is separate work with its own milestone, and {@code SaibamanPetEntity} is the precedent
     * it will start from. Declaring the slot now is what lets the choice exist, persist and travel between shards
     * before the creature does, so turning the summon on later reads a value that is already there.
     *
     * <p>Enabled on purpose, unlike {@link #BODY}: an empty pet slot is an honest "you own no pets", whereas a
     * hidden one would make a granted pet unreachable.
     */
    PET("pet", true),

    /**
     * The creature you ride. ENABLED as of 2026-09-22: the summon entity now exists
     * ({@code CosmeticMountEntity}), so equipping a mount and summoning it with {@code /cosmetic summon} (or the
     * recall toggle) draws and rides the chosen rig. The Halloween mounts that were parked here as icon items and
     * catalogue entries are now offered. No data migration was needed to turn it on, exactly as the class note
     * promised: a mount authored, seeded, owned and synced while the slot was disabled simply starts being offered.
     */
    MOUNT("mount", true),

    /**
     * Full body overlays. DECLARED, NOT SHIPPED. See the class note: flip {@code enabled} to true when the art
     * exists and nothing else has to change.
     */
    BODY("body", false),

    /**
     * PAIRED TRIGGERED slots: what plays when a moment happens, not a thing worn on the body. One triggered slot
     * holds at most one animation, so a player has one join flourish, not five.
     *
     * <h2>Two paired slots, since 2026-09-22, replacing four single ones</h2>
     * The owner asked for one equip to cover BOTH halves of a pair rather than four separate slots where one equip
     * only covered a single trigger. So {@link #JOIN_LEAVE} plays the animation's ARRIVING variant on a genuine
     * join and its DEPARTING variant on a genuine quit, and {@link #TELEPORT} plays the departing variant at the
     * origin of a teleport and the arriving variant at the destination. A single {@link CosmeticAnimation} carries
     * both an in and an out sound (see that class), so one equipped record is all it takes.
     *
     * <p>The value equipped is a catalogue id whose definition carries a {@link CosmeticDef#animation}; nothing is
     * worn or attached to a bone.
     */
    JOIN_LEAVE("join_leave", true, true, "join_leave"),

    TELEPORT("teleport", true, true, "teleport"),

    /**
     * The four ORIGINAL single-direction triggered slots. RETIRED as equip slots on 2026-09-22 (enabled false) but
     * kept DECLARED, never deleted, because they are persisted in equipped maps and on definitions. They still
     * serve two live purposes: they are the DIRECTIONAL playback triggers passed to the client so it picks the in
     * versus out sound ({@link CosmeticAnimation#soundFor}), and they are the source keys the one-time equip
     * migration folds into the two paired slots. Each names its paired equip slot as its {@code equipKey}, so a
     * {@code play(JOIN, ...)} reads what the player equipped in {@link #JOIN_LEAVE}. See {@link #equipSlot()}.
     */
    JOIN("join", false, true, "join_leave"),

    LEAVE("leave", false, true, "join_leave"),

    TP_DEPART("tp_depart", false, true, "teleport"),

    TP_ARRIVE("tp_arrive", false, true, "teleport"),

    /**
     * DECLARED DISABLED, like {@link #BODY}: the art is in/out pairs, not death/kill/respawn effects, and DEATH in
     * particular lands in the same tick as the grave snapshot and the cross-shard death grace, which is the worst
     * place to add an unproven visual. Turning one on later is one boolean plus a handler, never a data migration.
     */
    DEATH("death", false, true),

    KILL("kill", false, true),

    RESPAWN("respawn", false, true);

    /** Stable lowercase key. PERSISTED in every equipped map and on every definition, so never rename these. */
    public final String key;

    /** Whether this slot is offered to admins and players yet. */
    public final boolean enabled;

    /**
     * Whether this is a TRIGGERED slot (an event that plays an animation) rather than a WORN slot (a thing on the
     * body). Kept apart because the two are drawn by different machinery: a worn slot attaches art to a bone, a
     * triggered slot fires a self-expiring client FX. Nothing switches exhaustively on it; it is a filter.
     */
    public final boolean triggered;

    /**
     * For a directional playback trigger (JOIN, LEAVE, TP_DEPART, TP_ARRIVE) this is the key of the paired EQUIP
     * slot the player actually equips into ({@code join_leave} or {@code teleport}); for every other slot it is its
     * own key. Stored as a string, never an enum reference, so it costs no forward reference in the constant list.
     * Resolved through {@link #equipSlot()}.
     */
    public final String equipKey;

    CosmeticSlot(String key, boolean enabled)
    {
        this(key, enabled, false, key);
    }

    CosmeticSlot(String key, boolean enabled, boolean triggered)
    {
        this(key, enabled, triggered, key);
    }

    CosmeticSlot(String key, boolean enabled, boolean triggered, String equipKey)
    {
        this.key = key;
        this.enabled = enabled;
        this.triggered = triggered;
        this.equipKey = equipKey == null ? key : equipKey;
    }

    /** Whether this slot fires an animation on an event rather than being worn. */
    public boolean triggered()
    {
        return triggered;
    }

    /**
     * The slot a player equips into to drive this one. A directional trigger (JOIN, LEAVE, TP_DEPART, TP_ARRIVE)
     * returns its paired slot ({@link #JOIN_LEAVE} or {@link #TELEPORT}); every other slot returns itself. This is
     * what lets {@code play(JOIN, ...)} read the animation the player equipped in JOIN_LEAVE while still telling
     * the client the directional key so it picks the arriving sound.
     */
    public CosmeticSlot equipSlot()
    {
        return byKey(equipKey);
    }

    /**
     * The old single-direction keys a paired equip slot inherits from until the one-time migration folds them in.
     * JOIN_LEAVE prefers a {@code join} record over a {@code leave} one, TELEPORT prefers {@code tp_arrive} over
     * {@code tp_depart}, matching the migration's preference. Empty for every non-paired slot. This is what makes a
     * player who equipped under the old scheme, or whose un-migrated outfit arrived in a cross-shard merge, still
     * play the right animation before the migration has rewritten their record.
     */
    public List<String> legacyKeys()
    {
        if (this == JOIN_LEAVE)
            return List.of("join", "leave");
        if (this == TELEPORT)
            return List.of("tp_arrive", "tp_depart");
        return List.of();
    }

    /**
     * The directional trigger whose ARRIVING (in) half represents this slot, for a preview. JOIN_LEAVE previews as
     * JOIN, TELEPORT as TP_ARRIVE, so a "try before you buy" shows the in variant; any other slot previews itself.
     */
    public CosmeticSlot inTrigger()
    {
        if (this == JOIN_LEAVE)
            return JOIN;
        if (this == TELEPORT)
            return TP_ARRIVE;
        return this;
    }

    public String langKey()
    {
        return "gui.dmz_ragnarok.cosmetics.slot." + key;
    }

    private static final CosmeticSlot[] VALUES = values();

    /** Never throws: an unknown or absent key reads as HEAD rather than failing a load. */
    public static CosmeticSlot byKey(String key)
    {
        if (key != null)
        {
            String lower = key.toLowerCase(Locale.ROOT);
            for (CosmeticSlot s : VALUES)
                if (s.key.equals(lower))
                    return s;
        }
        return HEAD;
    }

    /**
     * The slots that may be used right now, in declaration order.
     *
     * <p>Every enumeration point in the feature uses this rather than {@code values()}, which is what makes
     * {@link #BODY} a one-boolean change instead of a migration.
     */
    public static List<CosmeticSlot> active()
    {
        List<CosmeticSlot> out = new ArrayList<>(VALUES.length);
        for (CosmeticSlot s : VALUES)
            if (s.enabled)
                out.add(s);
        return out;
    }

    /** The active slots' keys, for an editor dropdown or a wire list. */
    public static List<String> activeKeys()
    {
        List<String> out = new ArrayList<>();
        for (CosmeticSlot s : active())
            out.add(s.key);
        return out;
    }

    /** The enabled TRIGGERED slots, in declaration order. Used by the animation triggers and their gate. */
    public static List<CosmeticSlot> triggers()
    {
        List<CosmeticSlot> out = new ArrayList<>(VALUES.length);
        for (CosmeticSlot s : VALUES)
            if (s.enabled && s.triggered)
                out.add(s);
        return out;
    }

    /**
     * Whether a definition on this slot may be offered to a player.
     *
     * <p>A record left on a disabled slot (an admin built a body cosmetic early, or BODY was turned on and off
     * again) is kept, not deleted: it simply stops appearing until the slot is live. Losing an admin's work to a
     * toggle would be far worse than a record that waits.
     */
    public boolean usable()
    {
        return enabled;
    }
}
