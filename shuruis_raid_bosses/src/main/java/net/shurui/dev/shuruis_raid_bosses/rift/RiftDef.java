package net.shurui.dev.shuruis_raid_bosses.rift;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One kind of DIMENSIONAL TEAR: where it may open, how often, and which fight is on the other side.
 *
 * <p>Thin on purpose. Everything about the FIGHT is already modelled by
 * {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef} (editor, persisted, synced), so a rift NAMES
 * a raid def rather than copying it: one place to balance a boss, reachable through a tear and a scheduled
 * raid at once without drifting. What lives here is only what a tear adds: the roll for WHEN it opens,
 * WHERE, and how it presents itself.
 *
 * <p>Tears open inside SU NPC REGIONS, so "the open world areas" is an operator-editable list, not a config
 * box. {@link #regions} empty means every region. The exact spot uses the airdrop crate's surface-picking
 * pipeline, so a tear cannot open in a wall, underground, or (unless the region allows it) on open water.
 *
 * <p>A tear is a claim, not a queue: the first player through takes the fight and it vanishes for everyone
 * else, which is why there is no participant count.
 */
public class RiftDef {

    /** Unique key, lowercase. */
    public String id;

    /** Operator-facing name. Also what the tear announces itself as when it opens. */
    public String name = "Dimensional Tear";

    /** A disabled rift never rolls and never opens, but keeps its configuration. */
    public boolean enabled = true;

    /**
     * Admin flag: an eventOnly rift never rolls on its own. It opens only while an active timed event lists it
     * (checked through {@code EventHooks.riftRunnable}), and its open tears close when that event ends. Default
     * false, and OMITTED from the saved tag when false, so every existing rift stays byte-identical on disk and
     * over the {@code raids:rifts} sync. Keyless the event engine is inert, so an eventOnly rift never runs.
     */
    public boolean eventOnly = false;

    /**
     * Which portal LOOK the tear wears client-side. Blank or {@code "default"} draws the normal per-uuid coloured
     * swirl exactly as before; {@code "halloween"} draws an orange/purple swirl with a glowing jack-o'-lantern
     * face and ghost particles. A cosmetic string only: it is synced to the tear entity and read by
     * {@code DimensionalTearRenderer}. OMITTED from the saved tag when blank or {@code "default"}, so existing
     * rift data stays byte-identical.
     */
    public String look = "";

    /**
     * The encounter this tear leads to, OWNED by the rift (boss, raid type, stages, waves, allies, ki
     * moves, rewards). Two fields are NOT the operator's to set, because the tear supplies them per run
     * from the cell it was given: the arena and the player spawn.
     *
     * <p>Null means this rift still names a stored raid through {@link #raidId}, how every pre-encounter
     * rift keeps working. {@link RiftDefs#resolveRaid} prefers the embedded encounter and falls back to the
     * named one, so nothing had to be migrated.
     */
    public net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef encounter;

    /**
     * The {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef} id this tear leads to, for a rift with
     * no encounter of its own. Blank, or naming a missing def, makes it unrollable: {@link RiftDefs#isRunnable}
     * refuses it rather than opening a tear that would strand whoever walked in.
     */
    public String raidId = "";

    /**
     * NPC region names this tear may open in. EMPTY MEANS EVERY REGION, so a region added later is eligible
     * with no edit. Case-insensitive; a name that no longer resolves is skipped rather than failing the roll.
     */
    public final List<String> regions = new ArrayList<>();

    /**
     * Wait between tears of THIS rift in minutes, rolled uniformly in [min, max]. Per rift, not global, so a
     * rare and a common boss share the world without one starving the other. Mirrors the airdrop's per-region
     * min/max, max below min collapses to min.
     */
    public int minSpawnMinutes = 45;
    public int maxSpawnMinutes = 90;

    /**
     * Seconds an unclaimed tear stays open before closing. Without it a tear nobody takes sits forever and
     * every later roll finds the slot occupied.
     */
    public int tearLifetimeSeconds = 600;

    /**
     * Label drawn above the tear; blank falls back to {@link #name}. A per-rift string, not the raid's own
     * name, so a tear can tease the fight without naming the boss.
     */
    public String tearLabel = "";

    /**
     * Which dungeon theme's dimension the arena is cut into, and so what the floor is made of. One of the
     * dungeon addon's themes (overworld, namek, kaio, stony, otherworld, nether, end, vegeta, beerus); an
     * unknown one degrades to the overworld dimension with a stone floor. Per rift, not per raid, because it
     * is scenery not balance.
     */
    public String arenaTheme = "otherworld";

    /** Broadcast when a tear opens. Blank sends nothing. {name} and {region} are substituted. */
    public String msgOpened = "&5[Tear] &fA dimensional tear has opened in &d{region}&f: &5{name}";

    /** Broadcast when the player inside is defeated. {player} and {name} are substituted. */
    public String msgFailed = "&5[Tear] &f{player} was lost to &5{name}&f.";

    /** Broadcast when the player inside wins. {player} and {name} are substituted. */
    public String msgVictory = "&5[Tear] &f{player} tore their way out of &5{name}&f!";

    public RiftDef() {
    }

    public RiftDef(String id) {
        this.id = id == null ? null : id.toLowerCase(Locale.ROOT);
    }

    public String label() {
        return tearLabel == null || tearLabel.isBlank() ? name : tearLabel;
    }

    /** empty list means every region */
    public boolean allowsRegion(String regionName) {
        if (regionName == null) {
            return false;
        }
        if (regions.isEmpty()) {
            return true;
        }
        for (String r : regions) {
            if (r != null && r.equalsIgnoreCase(regionName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A wait in TICKS rolled uniformly in [min, max] minutes. Bounds floored at one minute, max below min
     * collapses to min rather than an empty range.
     */
    public long rollIntervalTicks(net.minecraft.util.RandomSource random) {
        long min = Math.max(1, minSpawnMinutes) * 60L * 20L;
        long max = Math.max(Math.max(1, minSpawnMinutes), maxSpawnMinutes) * 60L * 20L;
        return max > min ? min + (long) (random.nextDouble() * (max - min + 1L)) : min;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id == null ? "" : id);
        tag.putString("name", name);
        tag.putBoolean("enabled", enabled);
        // Omitted when false/default so existing rift tags are byte-identical (no republish on the rifts sync).
        if (eventOnly) {
            tag.putBoolean("eventOnly", true);
        }
        if (look != null && !look.isBlank() && !look.equalsIgnoreCase("default")) {
            tag.putString("look", look);
        }
        tag.putString("raidId", raidId == null ? "" : raidId);
        if (encounter != null) {
            tag.put("encounter", encounter.save());
        }
        ListTag regionList = new ListTag();
        for (String r : regions) {
            if (r != null && !r.isBlank()) {
                regionList.add(StringTag.valueOf(r));
            }
        }
        tag.put("regions", regionList);
        tag.putInt("minSpawnMinutes", minSpawnMinutes);
        tag.putInt("maxSpawnMinutes", maxSpawnMinutes);
        tag.putInt("tearLifetimeSeconds", tearLifetimeSeconds);
        tag.putString("tearLabel", tearLabel == null ? "" : tearLabel);
        tag.putString("arenaTheme", arenaTheme == null ? "" : arenaTheme);
        tag.putString("msgOpened", msgOpened == null ? "" : msgOpened);
        tag.putString("msgFailed", msgFailed == null ? "" : msgFailed);
        tag.putString("msgVictory", msgVictory == null ? "" : msgVictory);
        return tag;
    }

    public static RiftDef load(CompoundTag tag) {
        RiftDef def = new RiftDef(tag.getString("id"));
        if (tag.contains("name")) {
            def.name = tag.getString("name");
        }
        // absent means enabled: a pre-field tag reads false from getBoolean, which would silently disable
        // every rift on the first load after an update
        def.enabled = !tag.contains("enabled") || tag.getBoolean("enabled");
        def.eventOnly = tag.getBoolean("eventOnly"); // absent -> false (a normal rift)
        if (tag.contains("look")) {
            def.look = tag.getString("look");
        }
        def.raidId = tag.getString("raidId");
        if (tag.contains("encounter")) {
            def.encounter = net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef.load(tag.getCompound("encounter"));
        }
        for (Tag t : tag.getList("regions", Tag.TAG_STRING)) {
            def.regions.add(t.getAsString());
        }
        if (tag.contains("minSpawnMinutes")) {
            def.minSpawnMinutes = tag.getInt("minSpawnMinutes");
        }
        if (tag.contains("maxSpawnMinutes")) {
            def.maxSpawnMinutes = tag.getInt("maxSpawnMinutes");
        }
        if (tag.contains("tearLifetimeSeconds")) {
            def.tearLifetimeSeconds = tag.getInt("tearLifetimeSeconds");
        }
        if (tag.contains("tearLabel")) {
            def.tearLabel = tag.getString("tearLabel");
        }
        // blank is not a theme: an empty string resolves to the fallback dimension, so a pre-field tag keeps
        // the constructed default
        if (tag.contains("arenaTheme") && !tag.getString("arenaTheme").isBlank()) {
            def.arenaTheme = tag.getString("arenaTheme");
        }
        if (tag.contains("msgOpened")) {
            def.msgOpened = tag.getString("msgOpened");
        }
        if (tag.contains("msgFailed")) {
            def.msgFailed = tag.getString("msgFailed");
        }
        if (tag.contains("msgVictory")) {
            def.msgVictory = tag.getString("msgVictory");
        }
        return def;
    }
}
