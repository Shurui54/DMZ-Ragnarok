package net.shurui.shuruisutilities.corrupted;

import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

import net.shurui.shuruisutilities.corrupted.region.Region;
import net.shurui.shuruisutilities.ragnarok.RgNpcModels;
import net.shurui.shuruisutilities.ragnarok.RgNpcPicker;

/**
 * A single shadow dragon boss definition, one of seven fixed slots (index 1..7). Modelled on the raid-bosses
 * addon's RaidBossDef idiom (plain mutable fields, {@link #save()}/{@link #load(CompoundTag)} used for BOTH
 * world-save persistence and network transport to the editor client), but kept deliberately small: only what
 * seven fixed boss dragons need. Edited by the phase-3b GUI and persisted in {@link ShadowDragonStorage}.
 *
 * <p>The DMZ-specific stats follow RaidBossDef's convention: a value of 0 means "leave the entity's own default
 * untouched" so the default DMZ saga entity keeps its constructor defaults until an admin sets a real value.
 */
public class ShadowDragonDef {
    /** Fixed slot, 1..7. Also used as the map key in storage. */
    public int index;
    /** Display name for this slot. Seeded from {@link #SLOT_DEFAULTS} per slot, admin-editable. */
    public String name = "Shadow Dragon";

    /**
     * The canonical per-slot defaults, in ONE table so the mapping is readable and editable in a single place.
     * Each row is {display name, base rgnpc model id, transformed rgnpc model id}, indexed by slot as
     * {@code SLOT_DEFAULTS[index - 1]}. A blank transform column means "this dragon has no transformed form"
     * (slot 3, Eis Shenron). Every model id here is a live entry in {@link RgNpcModels}. Slot 1 (Syn Shenron)
     * is the base dragon and grants no sub-race; slots 2..7 map to {@code shadow_dragon_<slot>star}. The names
     * are plain strings, matching how sibling boss names in this file are stored (createDefault used a literal
     * "Shadow Dragon N", not a lang key), so no lang entries are involved.
     */
    private static final String[][] SLOT_DEFAULTS = {
        { "Syn Shenron",     "omega2",    "omega"           }, // 1
        { "Haze Shenron",    "2stars",    "omega"           }, // 2
        { "Eis Shenron",     "3or4stars", ""                }, // 3 (no transform)
        { "Nuova Shenron",   "4stars",    "4starsfullpower" }, // 4
        { "Rage Shenron",    "5stars",    "5starsfullpower" }, // 5
        { "Oceanus Shenron", "6stars",    "6starstrueform"  }, // 6
        { "Naturon Shenron", "7stars",    "7starstrue"      }, // 7
    };

    private static boolean inRange(int index) {
        return index >= 1 && index <= SLOT_DEFAULTS.length;
    }

    /** The canonical display name for a slot, or a neutral label for an out-of-range index. */
    public static String defaultName(int index) {
        return inRange(index) ? SLOT_DEFAULTS[index - 1][0] : "Shadow Dragon " + index;
    }

    /** The canonical base rgnpc model id for a slot, or the rgnpc default for an out-of-range index. */
    public static String defaultBaseModel(int index) {
        return inRange(index) ? SLOT_DEFAULTS[index - 1][1] : RgNpcModels.DEFAULT_ID;
    }

    /** The canonical transformed rgnpc model id for a slot, or "" (none) for slot 3 / an out-of-range index. */
    public static String defaultTransformModel(int index) {
        return inRange(index) ? SLOT_DEFAULTS[index - 1][2] : "";
    }

    /**
     * The base rgnpc model this slot's fighter wears, applied at spawn in ShadowDragonBossManager.spawnOne.
     * Seeded per slot from {@link #SLOT_DEFAULTS}; admin-editable.
     */
    public String baseModel = RgNpcModels.DEFAULT_ID;

    /**
     * The transformed rgnpc model this dragon would wear once it changes form, or "" when the dragon has no
     * transformed form (slot 3, Eis Shenron). STORED ONLY for now: the fight does not yet switch to it. See the
     * report and RgNpcFighterEntity for why (native saga transform is deliberately disabled). Seeded per slot
     * from {@link #SLOT_DEFAULTS}; admin-editable.
     */
    public String transformModel = "";

    /** True when this slot has a transformed form (every slot except Eis Shenron). */
    public boolean hasTransformModel() {
        return transformModel != null && !transformModel.isBlank();
    }

    /** The boss arena. Nullable until an admin sets it via the phase-3b editor. */
    public Region arena;

    /** The original placeholder default. Any def loaded still carrying this value is migrated to
     *  {@link #DEFAULT_ENTITY_TYPE} on load: the ender dragon breaks blocks it passes through (it would wreck a built
     *  arena) and its AI is tied to end crystals + a podium, so it misbehaves outside the End. Kept as a named
     *  constant because {@link ShadowDragonStorage} references it to flag migrated defs dirty. */
    public static final String LEGACY_ENTITY_TYPE = "minecraft:ender_dragon";

    /** The Janemba placeholder that shipped as the default before the rgnpc fighter. Any def still carrying it is
     *  the generic "Shadow Dragon" placeholder the owner asked us to stop guessing at: it was never a deliberate
     *  admin pick (an admin choosing a shadow dragon would never type Janemba), so migrating it to the rgnpc
     *  fighter is safe. Kept as a named constant so it can go in {@link #LEGACY_ENTITY_TYPES}. */
    public static final String LEGACY_DEFAULT_ENTITY_TYPE = "dragonminez:saga_super_janemba";

    /** Every entity id treated as a stale placeholder and rewritten to {@link #DEFAULT_ENTITY_TYPE} on load:
     *  {@link #LEGACY_ENTITY_TYPE} (the original ender-dragon placeholder), {@code minecraft:warden} (the second
     *  placeholder) and {@link #LEGACY_DEFAULT_ENTITY_TYPE} (the Janemba that was the default before this pass).
     *
     *  <p>NOTE ON WHY OVERRIDING THESE IS SAFE: {@link #save()} unconditionally writes {@code entityType}, so every
     *  existing saved def literally holds its old default and there is no "unset" case to distinguish. Overriding
     *  such a def is therefore indistinguishable from an admin who deliberately typed that exact id into the editor.
     *  That is acceptable ONLY because this feature is UNPUBLISHED: no production admin has ever deliberately picked
     *  the ender dragon, the warden or Janemba as a shadow dragon, so rewriting them on load cannot clobber a real
     *  choice, while a def an admin pointed at any OTHER entity (not in this set) is left alone. Once released, do
     *  not add further entries here for a value that was ever a legitimate admin pick. */
    private static final Set<String> LEGACY_ENTITY_TYPES =
            Set.of(LEGACY_ENTITY_TYPE, "minecraft:warden", LEGACY_DEFAULT_ENTITY_TYPE);

    /** Default boss entity: the suite's own saga fighter ({@link RgNpcPicker#FIGHTER_ID}), which wears the per-slot
     *  rgnpc dragon model set in {@link #baseModel} and applied at spawn. It is a DMZ {@code DBSagasEntity}, so the
     *  DMZ-specific editor fields (aiTier, battlePower, kiBlastDamage, scale) still apply, and its native saga
     *  transform is deliberately disabled so it never swaps itself out mid-fight. Still admin-editable per slot. */
    public static final String DEFAULT_ENTITY_TYPE = RgNpcPicker.FIGHTER_ID;

    /** True if {@code entityType} is a stale placeholder that should be migrated to {@link #DEFAULT_ENTITY_TYPE}. */
    public static boolean isLegacyEntityType(String entityType) {
        return LEGACY_ENTITY_TYPES.contains(entityType);
    }

    /**
     * Schema version written by {@link #save()}. Bumped to 2 when the editor gained a real PICKER for the entity
     * type and the two dragon models: from 2 on, every stored value is something somebody deliberately chose from a
     * list, so the placeholder migrations below must leave it alone. Before 2 the editor only had a free-text entity
     * box and no model control at all, which is what made "a stored value is a stale default, not a choice" a safe
     * assumption. A def with no tag is version 1.
     *
     * <p>This is what keeps the migrations from turning into a trap: without it, an admin who picks {@code 2stars}
     * for slot 5, or points a slot at the warden on purpose, would have that pick silently rewritten on the next
     * load, and picking it again would not help.
     */
    private static final int SCHEMA_VERSION = 2;

    /** True when {@code t} predates the picker, so the placeholder migrations in {@link #load(CompoundTag)} apply. */
    public static boolean isLegacySchema(CompoundTag t) {
        return t == null || t.getInt("schema") < SCHEMA_VERSION;
    }

    /** True when {@code name} is one of the generic "Shadow Dragon" placeholders (blank, "Shadow Dragon", or
     *  "Shadow Dragon N"), as opposed to a name an admin deliberately typed. Same UNPUBLISHED-safety reasoning as
     *  {@link #LEGACY_ENTITY_TYPES}: a saved placeholder name is migrated to the canonical per-slot name, while a
     *  real custom name (anything not matching the placeholder pattern) is preserved. */
    public static boolean isLegacyName(String name, int index) {
        if (name == null || name.isBlank()) return true;
        if (name.equals("Shadow Dragon")) return true;
        return name.equals("Shadow Dragon " + index);
    }

    /** Any registered entity id string, exactly like RaidBossDef's bossEntityType. Defaults to a DMZ saga entity so
     *  the DMZ-specific stats below take effect; admin-editable per slot via the phase-3b editor. */
    public String entityType = DEFAULT_ENTITY_TYPE;

    // Stats. The DMZ-specific ones (all but scale) use 0 = "keep the entity default", matching RaidBossDef.
    public double health = 0;       // 0 = keep entity default (max-health attribute)
    public double meleeDamage = 0;  // 0 = keep entity default (attack-damage attribute)
    public double defense = 0;      // 0 = no mitigation (suite-wide dmz_npc_defense; sdu applies the DMZ curve if present)
    public double moveSpeed = 0;    // 0 = keep entity default (movement-speed attribute)
    public double scale = 1.0;      // visual scale; 1.0 = unscaled
    public int battlePower = 0;     // 0 = keep entity default
    public double kiBlastDamage = 0;// 0 = keep entity default
    // DMZ's 1-based AiTier id (1=SIMPLE 2=TACTICAL 3=ADVANCED); 0 = keep the entity default.
    public int aiTier = 0;

    /** Explicit spawn point. Nullable; when null, phase-3c spawn logic derives a point from the arena. */
    public BlockPos spawnPos;

    public ShadowDragonDef() {}

    public ShadowDragonDef(int index) {
        this.index = index;
        this.name = defaultName(index);
        this.baseModel = defaultBaseModel(index);
        this.transformModel = defaultTransformModel(index);
    }

    public boolean hasArena() {
        return arena != null;
    }

    /** A fresh definition for the given fixed slot (1..7), already seeded with that slot's canonical dragon:
     *  its name, base model and transformed model, on the rgnpc fighter. The admin only sets arena and stats. */
    public static ShadowDragonDef createDefault(int index) {
        return new ShadowDragonDef(index);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("schema", SCHEMA_VERSION);
        t.putInt("index", index);
        t.putString("name", name);
        if (arena != null) t.put("arena", arena.save());
        t.putString("entityType", entityType);
        t.putString("baseModel", baseModel);
        t.putString("transformModel", transformModel);
        t.putDouble("health", health);
        t.putDouble("meleeDamage", meleeDamage);
        t.putDouble("defense", defense);
        t.putDouble("moveSpeed", moveSpeed);
        t.putDouble("scale", scale);
        t.putInt("battlePower", battlePower);
        t.putDouble("kiBlastDamage", kiBlastDamage);
        t.putInt("aiTier", aiTier);
        if (spawnPos != null) t.putLong("spawnPos", spawnPos.asLong());
        return t;
    }

    public static ShadowDragonDef load(CompoundTag t) {
        ShadowDragonDef d = new ShadowDragonDef();
        d.index = t.getInt("index");
        // Every rewrite below is a ONE-TIME migration of a pre-picker save, so none of it runs on a def the current
        // editor wrote. See isLegacySchema: once the admin can pick these values from a list, a stored value is a
        // choice, and rewriting a choice is a bug the admin cannot work around.
        boolean legacy = isLegacySchema(t);
        // Name: an admin's real custom name is kept, but a generic "Shadow Dragon"/"Shadow Dragon N" placeholder
        // (or an absent name) is migrated to the slot's canonical dragon name, so an older saved slot stops
        // reading as the generic guess. See isLegacyName for the unpublished-safety reasoning.
        d.name = t.contains("name") ? t.getString("name") : defaultName(d.index);
        if (legacy && isLegacyName(d.name, d.index)) d.name = defaultName(d.index);
        // Models: contains(...) ? ... : default, so a slot saved before these fields existed loads with its
        // canonical per-slot model rather than blank. transformModel "" is a legitimate stored value (Eis has none).
        d.baseModel = t.contains("baseModel") ? t.getString("baseModel") : defaultBaseModel(d.index);
        d.transformModel = t.contains("transformModel") ? t.getString("transformModel") : defaultTransformModel(d.index);
        // The absent-tag fallback above was not enough. save() writes baseModel UNCONDITIONALLY, so a slot saved in
        // the window between the field existing and SLOT_DEFAULTS seeding it holds the plain rgnpc default on disk,
        // and the contains() check happily loads it back: every dragon then read as the generic fighter in the
        // editor and spawned as one. Treat the bare default as the placeholder it is, exactly as isLegacyName and
        // LEGACY_ENTITY_TYPES already do for the name and the entity, and for the same unpublished-safety reason:
        // an admin picking a shadow dragon would not deliberately choose "the default". A model an admin actually
        // chose is anything else, and is left alone.
        if (d.baseModel == null || d.baseModel.isBlank()
                || (legacy && d.baseModel.equals(RgNpcModels.DEFAULT_ID)))
            d.baseModel = defaultBaseModel(d.index);
        // Same for the transformed model, with one carve-out: blank is a REAL value for a slot whose canonical
        // transform is itself blank (slot 3, Eis Shenron, who has no transformed form), so only migrate a blank
        // when the slot is supposed to have one.
        // A blank transform is a REAL value once the editor can set one (it means "this dragon does not change
        // form"), so outside the legacy path only an absent tag falls back to the slot default.
        if (d.transformModel == null
                || (legacy && (d.transformModel.isBlank() || d.transformModel.equals(RgNpcModels.DEFAULT_ID))))
            d.transformModel = defaultTransformModel(d.index);
        if (t.contains("arena")) d.arena = Region.load(t.getCompound("arena"));
        if (t.contains("entityType")) d.entityType =
                net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(t.getString("entityType"));
        // One-time migration: defs seeded under a previous default still carry a stale placeholder entity id (the
        // original ender dragon, the warden, or the Janemba that shipped as the default before the rgnpc fighter).
        // Rewrite any of them to the current default on load. The ender dragon would wreck a built arena; the warden
        // ignores the DMZ-specific editor fields (it is not a DBSagasEntity); the Janemba was the generic placeholder
        // the owner asked us to replace. A def an admin pointed at any OTHER entity is not in the set and is left
        // alone. See the LEGACY_ENTITY_TYPES javadoc for why overriding these cannot clobber a real choice (unpublished).
        if (legacy && isLegacyEntityType(d.entityType)) d.entityType = DEFAULT_ENTITY_TYPE;
        d.health = t.getDouble("health");
        d.meleeDamage = t.getDouble("meleeDamage");
        d.defense = t.getDouble("defense");
        d.moveSpeed = t.getDouble("moveSpeed");
        d.scale = t.contains("scale") ? t.getDouble("scale") : 1.0;
        d.battlePower = t.getInt("battlePower");
        d.kiBlastDamage = t.getDouble("kiBlastDamage");
        d.aiTier = t.getInt("aiTier");
        if (t.contains("spawnPos")) d.spawnPos = BlockPos.of(t.getLong("spawnPos"));
        return d;
    }
}
