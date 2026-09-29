package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerConfig;
import net.shurui.dev.shuruis_dmz_dungeons.block.SpawnerPreset;
import net.shurui.dev.shuruis_dmz_dungeons.block.DungeonCrateLoot;

import java.util.ArrayList;
import java.util.List;

// full configurable state of ONE dungeon floor. Kept as a small, extensible plain-data object (public mutable fields
// + save/load + encode/decode, mirroring SpawnerConfig) so new per-floor fields just append to save/load/encode/decode
// without reworking persistence or the wire format.
//
// No shuruisutilities types live here on purpose. The theme is a plain STRING (one of THEMES);
// DungeonDimensions.levelForTheme maps it to the dimension, so this class works whether or not SU is installed.
public class DungeonFloorConfig {

    // every floor theme OFFERED FOR NEW CONFIGURATION, as plain strings so the config, command and GUI never classload
    // an enum. DungeonDimensions.levelForTheme maps them with a safe fallback (KAIO maps to the "kai" dimension by
    // name). VEGETA and BEERUS are the two planet themes: their dimensions are fixed to the dmz_ragnarok:vegeta /
    // :beerus biomes so SU's per-biome tint (NamekBlockTints) colours the shared namek_* surface set to each planet.
    //
    // STONY was RETIRED from this list: VEGETA now carries the "Saiyan" label STONY held. STONY is deliberately NOT
    // here so no NEW floor can pick it, but it stays a fully valid RETAINED theme (see RETAINED_THEMES and the STONY
    // case kept in DungeonDimensions.levelForTheme, DungeonPalettes, ClientCrateTiers, DungeonCrateDefaults,
    // ArenaBuildTask). A world with a stony floor keeps generating it in its baked dmz_ragnarok:stony dimension;
    // dropping those cases would repoint the floor and orphan its built content.
    public static final String[] THEMES = {
            "OVERWORLD", "NAMEK", "NETHER", "END", "KAIO", "OTHERWORLD", "VEGETA", "BEERUS"
    };

    // themes no longer offered for new configuration but that MUST still validate, so an existing floor saved with one
    // survives a GUI save round-trip. canonicalTheme returns these unchanged instead of collapsing to OVERWORLD, which
    // would repoint the floor and orphan its built content. STONY: retired for VEGETA, but resolves everywhere it did.
    public static final String[] RETAINED_THEMES = { "STONY" };

    // a floor is either NORMAL (jigsaw room layout) or BOSS (one large standalone themed arena holding this floor's
    // guardian). Plain STRING, like the theme, so the config/command/GUI never classload an enum. A BOSS floor ignores
    // the layout style; a NORMAL floor ignores the boss config. Unknown values degrade to NORMAL via canonicalType.
    public static final String[] TYPES = {"NORMAL", "BOSS"};

    // hard bounds for the stamp geometry, shared by the command argument ranges and the generation-time clamp so a
    // value can never drive the SU stamp out of the dungeon dimension's Y range (see DungeonFloorLayout).
    //
    // MAX_SIZE is large enough to hold the two biggest FAITHFUL Mine Cells archetypes: the promenade grows a ~64-cell
    // span, needing a grid radius of ~31 cells (about 1024 blocks). 1120 clears that with headroom
    // (gridRadiusForSize(1120) = 34). A CEILING, not a default: the default stays 250 (Config.dungeonFloorSize), and a
    // big floor is opt-in per floor, because the barrier box grows with size squared (a 1120 floor writes roughly
    // twenty times the ceiling-cap blocks of a 250 floor). Floors sit 100,000 blocks apart (CELL_SPACING), so even the
    // largest leaves a ~98,000 block gap.
    public static final int MIN_SIZE = 64;
    public static final int MAX_SIZE = 1120;
    public static final int MIN_DEPTH = 16;
    public static final int MAX_DEPTH = 124;

    // theme name (one of THEMES). the material family the whole floor surface is built from.
    public String theme;
    // side length, in blocks, of the square the floor's terrain disc is inscribed in. Also sets how far the room grid
    // may reach (LayoutGenerator.gridRadiusForSize turns it into a cell radius). settable via /rg dungeon floor size.
    public int size;
    // how far, in blocks, the solid body extends below the surface; clamped so the column bottom stays in the build range.
    public int depth;

    // which room-layout algorithm this floor uses (LayoutStyle name), or null/empty to inherit the config default.
    // Plain string, like the theme; read through LayoutStyle.byName at generation time.
    public String layoutStyle;

    // per-player crate loot for this floor: item drops (percentage rolls, same CustomDrop shape as the spawner), a zeni
    // range paid through SU's economy on first loot, and an optional refresh cooldown. DungeonCrates reads this on
    // first open; an empty config falls back to the theme's starter loot (DungeonCrateDefaults). Never null.
    public DungeonCrateLoot crateLoot = new DungeonCrateLoot();

    // the floor's guardian boss, using the SAME SpawnerConfig machinery as the advanced spawn block, so the boss GUI
    // drives it with the exact spawner editor. Only the MAIN section is used (one guardian per floor); its sub-boss
    // fields are ignored. Spawned once at the floor's boss room; defeating it unlocks the next-floor portal (persisted
    // per floor on DungeonFloors). Never null.
    public boolean bossEnabled = true;
    public SpawnerConfig boss = defaultBossConfig();

    // NORMAL (default) or BOSS. A BOSS floor is a first-class floor whose content is a single large themed arena; its
    // guardian gates progression to later floors (DungeonFloors.isBossDefeated, enforced by the SU portal). Appended
    // last so the persisted/wire form stays backward compatible (older saves load as NORMAL).
    public String type = "NORMAL";

    // the floor's regular ENEMY configuration, driving the mobs at the harvested enemyMarkers. This WAS a single
    // SpawnerConfig per floor; the decision was deliberately REVERSED and a floor now holds a LIST of weighted presets
    // (enemyPresets), because "one enemy family per floor" was too uniform and generation wants a different family per
    // marker. Each preset is a THIN WRAPPER around a full SpawnerConfig so the shared spawner editor and spawn path are
    // reused verbatim. The legacy single `enemies` field is KEPT and still saved/encoded: it seeds a one-element
    // enemyPresets on load for older floors, and keeping it written means a downgrade does not hard-fail. The list is
    // appended AFTER `enemies` in NBT and wire, so both formats stay append-only and symmetric. enemiesEnabled gates
    // the whole system. Never null.
    public boolean enemiesEnabled = false;
    public SpawnerConfig enemies = defaultEnemyConfig();
    public List<SpawnerPreset> enemyPresets = new ArrayList<>();

    // when true, a manual SU /portal onto THIS floor is refused unless the player carries a floor ticket (SU's
    // ss_ticket or the dungeon's floor_ticket) stamped for this exact floor number. A POSSESSION check, not a consume:
    // the ticket is a keycard for the portal, and the separate right-click warp (FloorTicketItem /
    // UtilitiesTicketCompat) spends it. Ops and the dungeon bypass node skip the check, like the boss and cooldown
    // gates. Off by default, appended last.
    public boolean requireTicket = false;

    // whether this floor is IN ROTATION. Taking a floor OUT of rotation (staff remove its portal from the world so
    // players bypass it) keeps ALL of its config and any built terrain, but stops it gating progression: the
    // sequential boss gate (UtilitiesPortalCompat.firstUnclearedBossBefore) treats an out-of-rotation boss floor as
    // already satisfied. So a boss floor whose boss can no longer be reached (its portal gone) does not seal every
    // floor above it with "the guardian of boss floor N still stands". Default true, so every existing floor stays in
    // rotation and a floor saved before this flag existed loads in rotation. Toggled with /rg dungeon floor rotation.
    // Appended last so the persisted/wire form stays backward compatible.
    public boolean inRotation = true;

    public DungeonFloorConfig() {
        this.theme = "OVERWORLD";
        this.size = Config.dungeonFloorSize;
        this.depth = Config.dungeonFloorDepth;
    }

    public DungeonFloorConfig(String theme, int size, int depth) {
        this.theme = theme;
        this.size = size;
        this.depth = depth;
    }

    // a sensible default floor seeded from config: the size/depth come from the mod config and the theme rotates
    // through a small palette so a fresh dungeon has variety out of the box. floorNumber is 1-based.
    public static DungeonFloorConfig defaultFor(int floorNumber) {
        return new DungeonFloorConfig(defaultTheme(floorNumber), Config.dungeonFloorSize, Config.dungeonFloorDepth);
    }

    // map an arbitrary (client-supplied) theme string to the canonical THEMES entry it matches case-insensitively,
    // or "OVERWORLD" if it matches none. used by the GUI save path to reject unknown/renamed theme strings without
    // classloading the gen package (theme validation stays here in the config class, layout validation is done at
    // the save handler where LayoutStyle is already on the classpath).
    public static String canonicalTheme(String s) {
        if (s != null) {
            for (String t : THEMES) {
                if (t.equalsIgnoreCase(s)) {
                    return t;
                }
            }
            // retired-but-retained themes (e.g. STONY) still validate so an existing floor saved with one is preserved
            // on save instead of being rewritten to OVERWORLD and repointed at the wrong dimension.
            for (String t : RETAINED_THEMES) {
                if (t.equalsIgnoreCase(s)) {
                    return t;
                }
            }
        }
        return "OVERWORLD";
    }

    // map an arbitrary (client-supplied) type string to the canonical TYPES entry it matches case-insensitively, or
    // "NORMAL" if it matches none. used by the GUI save path to reject unknown/renamed type strings.
    public static String canonicalType(String s) {
        if (s != null) {
            for (String t : TYPES) {
                if (t.equalsIgnoreCase(s)) {
                    return t;
                }
            }
        }
        return "NORMAL";
    }

    // whether this floor is a boss floor (a standalone arena guarded by its configured boss).
    public boolean isBoss() {
        return "BOSS".equalsIgnoreCase(type);
    }

    private static String defaultTheme(int floorNumber) {
        // STONY was retired from the picker; VEGETA (the theme that inherited the "Saiyan" label) takes its slot here so
        // a fresh dungeon's default rotation only uses currently-offered themes.
        String[] rotation = { "OVERWORLD", "VEGETA", "NAMEK", "NETHER", "KAIO", "END" };
        int i = Math.floorMod(floorNumber - 1, rotation.length);
        return rotation[i];
    }

    // a sensible starter guardian: an sdu DMZ fighter with a chunky health pool, the DMZ NPC-defense curve set, and a
    // modest TP / balance reward, so a fresh floor has a real gate out of the box. tuned to be beatable but not
    // trivial; an admin re-tunes it (or swaps the entity, adds ki moves / a transform chain) via the later GUI. The
    // rest of SpawnerConfig's defaults (no ki, no transform, no custom drops) are fine as-is.
    private static SpawnerConfig defaultBossConfig() {
        SpawnerConfig b = new SpawnerConfig();
        b.entityTypeId = "dmz_ragnarok:dmz_fighter";
        b.maxHealth = 300.0f;
        b.attackDamage = 15.0f;
        b.moveSpeed = 0.28f;
        b.defense = 8.0f;      // dmz_npc_defense scale, not armor points
        b.behavior = 6;        // DMZ Fighter behaviour
        b.aiTier = 2;          // Advanced AI
        b.scale = 1.3f;
        b.tpMin = 5000;
        b.tpMax = 12000;
        b.balMin = 500;
        b.balMax = 1500;
        return b;
    }

    // a modest starter enemy for the floor's spawn markers: a weaker sdu DMZ fighter than the guardian, with the DMZ
    // NPC-defense curve set and a small reward, so an admin has a sane baseline to re-tune (or swap the entity) in the
    // NPCs tab. Disabled by default (enemiesEnabled=false) so enabling enemies is a deliberate opt-in per floor.
    public static SpawnerConfig defaultEnemyConfig() {
        SpawnerConfig e = new SpawnerConfig();
        e.entityTypeId = "dmz_ragnarok:dmz_fighter";
        e.maxHealth = 60.0f;
        e.attackDamage = 6.0f;
        e.moveSpeed = 0.28f;
        e.defense = 3.0f;      // dmz_npc_defense scale, not armor points
        e.behavior = 6;        // DMZ Fighter behaviour
        e.aiTier = 1;          // Tactical AI
        e.scale = 1.0f;
        e.tpMin = 500;
        e.tpMax = 1500;
        e.balMin = 50;
        e.balMax = 200;
        return e;
    }

    // clamped reads used at generation time so a hand-edited NBT or an out-of-range legacy value can never push the
    // stamp past the dungeon dimension bounds.
    public int clampedSize() {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, size));
    }

    public int clampedDepth() {
        return Math.max(MIN_DEPTH, Math.min(MAX_DEPTH, depth));
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putString("Theme", theme == null ? "OVERWORLD" : theme);
        tag.putInt("Size", size);
        tag.putInt("Depth", depth);
        tag.putString("LayoutStyle", layoutStyle == null ? "" : layoutStyle);
        tag.put("CrateLoot", (crateLoot == null ? new DungeonCrateLoot() : crateLoot).save(new CompoundTag()));
        tag.putBoolean("BossEnabled", bossEnabled);
        tag.put("Boss", (boss == null ? new SpawnerConfig() : boss).save(new CompoundTag()));
        tag.putString("Type", type == null ? "NORMAL" : type);
        tag.putBoolean("EnemiesEnabled", enemiesEnabled);
        tag.put("Enemies", (enemies == null ? new SpawnerConfig() : enemies).save(new CompoundTag()));
        // appended AFTER the legacy single "Enemies" compound so older saves (which lack this key) still load; the
        // legacy compound above is still written for the same reason a downgrade must not hard-fail.
        ListTag presetList = new ListTag();
        for (SpawnerPreset p : enemyPresets) {
            presetList.add((p == null ? new SpawnerPreset() : p).save(new CompoundTag()));
        }
        tag.put("EnemyPresets", presetList);
        // appended last so a floor saved before portal ticket gating existed loads with requireTicket = false.
        tag.putBoolean("RequireTicket", requireTicket);
        // appended last so a floor saved before rotation gating existed loads in rotation (load defaults it true).
        tag.putBoolean("InRotation", inRotation);
        return tag;
    }

    public static DungeonFloorConfig load(CompoundTag tag) {
        DungeonFloorConfig c = new DungeonFloorConfig();
        c.theme = tag.contains("Theme") ? tag.getString("Theme") : "OVERWORLD";
        c.size = tag.contains("Size") ? tag.getInt("Size") : Config.dungeonFloorSize;
        c.depth = tag.contains("Depth") ? tag.getInt("Depth") : Config.dungeonFloorDepth;
        c.layoutStyle = tag.contains("LayoutStyle") ? tag.getString("LayoutStyle") : "";
        c.crateLoot = tag.contains("CrateLoot") ? DungeonCrateLoot.load(tag.getCompound("CrateLoot")) : new DungeonCrateLoot();
        c.bossEnabled = !tag.contains("BossEnabled") || tag.getBoolean("BossEnabled");
        c.boss = tag.contains("Boss") ? SpawnerConfig.load(tag.getCompound("Boss")) : defaultBossConfig();
        c.type = tag.contains("Type") ? canonicalType(tag.getString("Type")) : "NORMAL";
        c.enemiesEnabled = tag.getBoolean("EnemiesEnabled");
        c.enemies = tag.contains("Enemies") ? SpawnerConfig.load(tag.getCompound("Enemies")) : defaultEnemyConfig();
        c.enemyPresets = new ArrayList<>();
        if (tag.contains("EnemyPresets")) {
            ListTag presetList = tag.getList("EnemyPresets", Tag.TAG_COMPOUND);
            for (int i = 0; i < presetList.size(); i++) {
                c.enemyPresets.add(SpawnerPreset.load(presetList.getCompound(i)));
            }
        }
        // absent on floors saved before portal ticket gating existed -> false (getBoolean default), leaving them open.
        c.requireTicket = tag.getBoolean("RequireTicket");
        // absent on floors saved before rotation gating existed -> true (default), keeping every existing floor in
        // rotation. Only an explicit stored false takes a floor out.
        c.inRotation = !tag.contains("InRotation") || tag.getBoolean("InRotation");
        // migration: a floor saved before the preset list existed (no "EnemyPresets" key), or one whose list came in
        // empty, folds its legacy single "Enemies" compound into a one-element list so the later generation step sees
        // one preset instead of nothing. the legacy "Enemies" field is left populated (read just above, written in
        // save) so a downgrade to the single-config build still loads.
        if (c.enemyPresets.isEmpty() && tag.contains("Enemies") && c.enemies != null) {
            c.enemyPresets.add(new SpawnerPreset(c.enemies, 1));
        }
        // the RAMPARTS and BLACK_BRIDGE archetypes were removed. A floor configured to either inherits the config
        // default instead (empty string) so it never resolves to a missing generator; the rolled layout, if any, is
        // migrated separately in DungeonFloors.load.
        if ("RAMPARTS".equalsIgnoreCase(c.layoutStyle) || "BLACK_BRIDGE".equalsIgnoreCase(c.layoutStyle)) {
            Shuruis_dmz_dungeons.LOGGER.warn(
                    "[{}] A floor config used the removed {} layout style; migrating it to inherit the default.",
                    Shuruis_dmz_dungeons.MODID, c.layoutStyle);
            c.layoutStyle = "";
        }
        return c;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(theme == null ? "OVERWORLD" : theme);
        buf.writeInt(size);
        buf.writeInt(depth);
        buf.writeUtf(layoutStyle == null ? "" : layoutStyle);
        (crateLoot == null ? new DungeonCrateLoot() : crateLoot).encode(buf);
        buf.writeBoolean(bossEnabled);
        (boss == null ? new SpawnerConfig() : boss).encode(buf);
        buf.writeUtf(type == null ? "NORMAL" : type);
        buf.writeBoolean(enemiesEnabled);
        (enemies == null ? new SpawnerConfig() : enemies).encode(buf);
        // appended AFTER the legacy single enemies config; same-version wire, so decode reads it back in this order.
        buf.writeInt(enemyPresets.size());
        for (SpawnerPreset p : enemyPresets) {
            (p == null ? new SpawnerPreset() : p).encode(buf);
        }
        // appended last; decode reads it back in this order. Same-version wire, so an older reader simply stops here.
        buf.writeBoolean(requireTicket);
        buf.writeBoolean(inRotation);
    }

    public static DungeonFloorConfig decode(FriendlyByteBuf buf) {
        DungeonFloorConfig c = new DungeonFloorConfig();
        c.theme = buf.readUtf();
        c.size = buf.readInt();
        c.depth = buf.readInt();
        c.layoutStyle = buf.readUtf();
        c.crateLoot = DungeonCrateLoot.decode(buf);
        c.bossEnabled = buf.readBoolean();
        c.boss = SpawnerConfig.decode(buf);
        c.type = canonicalType(buf.readUtf());
        c.enemiesEnabled = buf.readBoolean();
        c.enemies = SpawnerConfig.decode(buf);
        c.enemyPresets = new ArrayList<>();
        int presetCount = buf.readInt();
        for (int i = 0; i < presetCount; i++) {
            c.enemyPresets.add(SpawnerPreset.decode(buf));
        }
        c.requireTicket = buf.readBoolean();
        c.inRotation = buf.readBoolean();
        return c;
    }
}
