package net.shurui.dev.sdu.compat.cnpc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * The ragnarok character models, offered to the Custom NPCs model editor as browsable entries rather than raw
 * geo paths.
 *
 * <p>The table ({@code RgNpcModels} / {@code RgNpcModelSizes}) is owned by Shurui's Utilities, and the
 * dependency runs SU -&gt; sdu, never the reverse. So sdu declares the shape it needs and SU fills it in at
 * client setup. With no registration the pickers report an empty catalogue and the editor behaves as before.
 *
 * <p>An entry carries everything to make the model look right in one click. A geo alone is not enough: these
 * models do not use the player skin UV, so a geo on its own renders wearing whatever Steve skin the NPC had,
 * stretched over the wrong map, at the addon's default 0.7 x 2.0 hitbox. The texture and baked collision box
 * travel with the geo for that reason.
 *
 * <p>Key gating: some entries are premium content withheld without Shurui's Key. SU installs a supplier via
 * {@link #setGate(BooleanSupplier)} reporting the connected server's key state, and {@link #available()} filters
 * on it. Defaults to "locked" so a missing or not-yet-received key state can never leak gated entries. Gated
 * entries stay in the table either way, so an NPC already wearing one keeps rendering it.
 *
 * <p>Deliberately free of any Custom NPCs / Gecko-addon type so SU can populate it without those mods present.
 */
public final class RagnarokModelCatalog {

    /**
     * One browsable model. {@code id} is the entry id the rest of the system uses (the same string
     * {@code /su entity} takes), and doubles as the display name so what the editor shows and what a command
     * takes can never drift. {@code geo} and {@code texture} are full resource-location strings; {@code width}
     * and {@code height} are the baked collision box in blocks.
     */
    public record Entry(String id, String geo, String texture, float width, float height, boolean gated) {
    }

    /** Insertion order preserved so {@link #replaceAll} decides the browse order, not a hash. */
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    /** Locked until SU installs a real gate; see the class note on why the default is the closed one. */
    private static volatile BooleanSupplier gate = () -> false;

    private RagnarokModelCatalog() {
    }

    /**
     * Install the whole catalogue, replacing anything registered before. Entries with a blank id, a blank geo or
     * a duplicate id are dropped rather than allowed to shadow one another, because the id is the key the picker
     * resolves a selection back through.
     */
    public static synchronized void replaceAll(List<Entry> entries) {
        ENTRIES.clear();
        if (entries == null) {
            return;
        }
        for (Entry e : entries) {
            if (e == null || e.id() == null || e.id().isBlank() || e.geo() == null || e.geo().isBlank()) {
                continue;
            }
            ENTRIES.putIfAbsent(e.id(), e);
        }
    }

    /** Install the key-state gate. {@code null} restores the closed default. */
    public static void setGate(BooleanSupplier unlocked) {
        gate = unlocked == null ? () -> false : unlocked;
    }

    /** True when the key state currently allows the gated entries. Never throws; a failing gate reads as locked. */
    public static boolean unlocked() {
        try {
            return gate.getAsBoolean();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Entries the editor may offer right now: the whole table with the key, the ungated ones without it. */
    public static synchronized List<Entry> available() {
        boolean open = unlocked();
        List<Entry> out = new ArrayList<>(ENTRIES.size());
        for (Entry e : ENTRIES.values()) {
            if (open || !e.gated()) {
                out.add(e);
            }
        }
        return out;
    }

    /** Ids of {@link #available()}, in the same order, for a string picker. */
    public static List<String> availableIds() {
        List<Entry> entries = available();
        List<String> out = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            out.add(e.id());
        }
        return out;
    }

    /**
     * Look an id back up. Reads the FULL table, gate included, on purpose: the gate decides what may be offered,
     * and re-checking it here would only turn a stale open picker into a silent no-op.
     */
    public static synchronized Entry byId(String id) {
        return id == null ? null : ENTRIES.get(id);
    }

    /** Nothing registered, so the pickers should stay hidden. */
    public static synchronized boolean isEmpty() {
        return ENTRIES.isEmpty();
    }

    /** Unmodifiable view of the whole table, gate ignored, for tooling and validation. */
    public static synchronized List<Entry> all() {
        return Collections.unmodifiableList(new ArrayList<>(ENTRIES.values()));
    }
}
