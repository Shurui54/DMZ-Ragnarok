package net.shurui.dev.shuruis_raid_bosses.raid;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.region.Region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A named raid boss definition: its own arena, boss entity, scaling, schedule, announcements and rewards.
 * Edited through {@code RaidEditScreen}, persisted in {@code RaidData}. Serialised to a {@link CompoundTag}
 * for world-save persistence and network transfer to the editor client.
 */
public class RaidBossDef {

    /**
     * The Duke Snipperjack boss entity and the track its encounter defaults to. Used ONLY by the editor to
     * seed {@link #bossMusic} when an operator points a fresh encounter at Snipperjack: a default value copied
     * into an editable field, never a runtime boss to track lookup. Clearing the field afterwards turns music
     * off, and any other boss is silent unless its own field is set.
     */
    public static final String SNIPPERJACK_ENTITY = "dmz_ragnarok:duke_snipperjack";
    public static final String SNIPPERJACK_MUSIC = "dmz_ragnarok:music.boss_snipperjack";

    public String id;                 // unique key, lowercase
    public String name = "Raid";

    public RaidType raidType = RaidType.STANDARD;
    /** groups raids into the NPC browser dropdowns */
    public String category = "General";
    /** appears in the raid-NPC browser (off for template/item-only raids) */
    public boolean showInNpcMenu = true;

    /**
     * Admin flag: an eventOnly raid never opens sign-ups, joins, force-starts or schedules on its own, and is
     * hidden from the raid-NPC browser. It runs only while an active timed event lists it (checked through
     * {@code EventHooks.raidRunnable}). Default false, and OMITTED from the saved tag when false, so every
     * existing raid stays byte-identical on disk and over the {@code raids:defs} sync. Keyless the event engine
     * is inert, so an eventOnly raid never runs.
     */
    public boolean eventOnly = false;

    public Region arena;
    /**
     * Optional area players are placed into on entry; unset falls back to the arena. Unlike {@link #arena}
     * this keeps the selection's Y instead of being flattened to the build column, because a spawn point is
     * a specific place an admin picked, not an X/Z containment box players surface-snap inside.
     */
    public Region playerSpawn;

    /**
     * Ids this raid picks from at random; empty means a normal raid running its own content. When set, this
     * def is a SELECTOR: at fight start one entry is drawn and runs instead, in THIS raid's arena and
     * spawn. Any raid type may be in the pool, since the drawn raid's own {@link #raidType} drives the fight.
     */
    public final List<String> randomPool = new ArrayList<>();

    // Boss
    public String bossEntityType = "minecraft:warden"; // any registered entity id

    /**
     * Which ragnarok NPC model the BOSS wears, when {@link #bossEntityType} is {@code dmz_ragnarok:rgnpc}.
     * All 386 ragnarok NPCs share one entity type, so without this the picker could only ever produce the
     * default model. Blank leaves the entity alone, which every other type wants.
     */
    public String bossModelId = "";
    public String bossName = "Raid Boss";

    // Scaling with the number of participants
    public double baseHealth = 500.0;
    public double healthPerPlayer = 250.0;
    public double maxHealthCap = 0.0;         // 0 = no cap
    public boolean scaleDamage = false;
    public double damagePerPlayer = 2.0;

    /**
     * Ceiling on a SINGLE hit against the boss, as a percent of the boss's current max health. 0 disables it.
     *
     * <p>This is the one deliberate cap in the raid mod, and it is not a cap on anybody's stats: a player who can hit
     * for a million still hits for a million everywhere else, they just cannot end a raid in one punch. Without it the
     * whole encounter (waves, phases, the time limit, the damage leaderboard) collapses the moment one fighter
     * out-scales the boss, and the fight stops being a fight for everyone else in the arena.
     *
     * <p>A percent rather than a flat number so it follows the boss's own participant scaling: 2.5 means the boss
     * always takes at least 40 landed hits, whether it spawned with 500 HP or 500 million.
     */
    public double maxDamagePercentPerHit = 2.5;

    /** PARALLEL QUEST: spawn the configured boss as a finale after the last wave is cleared */
    public boolean parallelBoss = false;

    // DMZ saga-boss stats, only applied when the boss is a DBSagasEntity. Sentinels leave the constructor
    // default untouched.
    public int battlePower = 0;        // 0 = keep entity default
    public double kiBlastDamage = 0;   // 0 = keep entity default
    public double moveSpeed = 0;       // 0 = keep entity default (movement-speed attribute)
    public double baseMeleeDamage = 0; // 0 = keep entity default (attack-damage attribute)
    // Suite-wide "NPC defense": written as raw persistentData key "dmz_npc_defense" (double). An sdu Forge
    // handler applies DMZ's resistance curve; scale = DMZ player getDefense() (higher = tankier). Written
    // directly, never importing sdu, so raid_bosses works when sdu is absent (key sits unread).
    public double baseDefense = 0;     // 0 = no mitigation (matches the "keep default" sentinel of the others)
    public double bossScale = 0;       // 0 = keep entity default
    // DMZ's 1-based AiTier id (matches setAiTierById): 1=SIMPLE 2=TACTICAL 3=ADVANCED.
    // 0 or -1 = keep the entity default (only 1..3 are ever applied).
    public int aiTier = 0;

    // Ki moves the boss fires (KiMove tokens). Empty = keep the entity's default skill pool.
    public final List<String> kiMoves = new ArrayList<>();

    // Transformation chain (sdu engine), raw NBT to stay sdu-agnostic: a TransformChain compound
    // ({forms:[...],index:0}) written to the boss's persistentData "sdu_tf" key at spawn. Empty (no "forms")
    // means no custom chain. Only the main boss carries this for now.
    public CompoundTag bossTransform = new CompoundTag();
    /** when true and no custom chain, leave DMZ's native transforms alone; a custom chain always overrides */
    public boolean useDefaultTransform = true;

    // PARALLEL_QUEST: enemies to spawn (EnemyWave tokens) and how the MVP is scored
    public final List<String> enemies = new ArrayList<>();
    /** MVP score = total damage + mvpKillWeight * kills; higher weight favours kill-stealers */
    public double mvpKillWeight = 500.0;

    // BOSS_RUSH: ordered bosses (BossStage tokens), each with its own pre-spawn delay
    public final List<String> rushStages = new ArrayList<>();

    /**
     * Allies spawned WITH THE ENCOUNTER, as {@link AllySpawn} tokens: NPCs already fighting beside the
     * player at the start. Per-stage reinforcements live on {@link BossStage#allies}. Applies to every raid
     * type, so an ordinary boss fight can hand the player a partner.
     */
    public final List<String> allies = new ArrayList<>();

    // Sign-up / limits
    public int signupMinutes = 5;
    /** repeat the sign-up announcement every N seconds while open (0 = once only) */
    public int signupReminderSeconds = 30;
    public int minParticipants = 1;
    public int maxParticipants = 10;
    public boolean requireCharacter = true;

    // Rules
    public boolean pvpInArena = false;
    /** if ON, a non-participant is bounced out of the running raid's arena */
    public boolean preventReentry = false;
    /** whether raid enemies still drop their own loot-table items; rewards come from the reward table */
    public boolean vanillaDrops = false;
    public boolean healOnStart = true;
    public int countdownSeconds = 5;
    public int fightTimeLimit = 600;

    // NPC / announcements
    public String npcName = "Raid Master";
    public String npcEntityType = "dmz_ragnarok:raid_npc"; // any registered entity id

    /** ragnarok NPC model the sign-up HOST wears; see {@link #bossModelId}, blank leaves it alone */
    public String npcModelId = "";
    public boolean useTitles = true;
    public String announceSound = "minecraft:ui.toast.challenge_complete";

    /**
     * Sound event id (any registered sound) looped as boss music for every fighter inside this encounter, at
     * {@link net.minecraft.sounds.SoundSource#MUSIC} so a player's own music volume applies and vanilla
     * background music is hushed while it plays. BLANK means no music, the default for every encounter that
     * does not set one. Editable in game like {@link #announceSound}; nothing maps a boss to a track in code,
     * the track is whatever this field holds. A track pointing at a sound that is not present degrades to
     * silence client side (one log line), never a crash. Duke Snipperjack's encounter seeds this to
     * {@code dmz_ragnarok:music.boss_snipperjack} when it is built in the editor; clearing the field turns it off.
     */
    public String bossMusic = "";

    /**
     * When true, the boss is seated on a block of {@link #spawnAnchorBlock} inside the arena instead of at the
     * arena floor centre, so a builder can place that block to pick exactly where he appears in their scene.
     * Duke Snipperjack's encounter seeds this on in the editor; and because his encounter may predate the field,
     * the spawn path also treats his boss entity as anchor-on-obsidian by default (see RaidInstance). If the
     * block is not found in the arena, the boss falls back to the normal spawn and a warning names the encounter.
     */
    public boolean spawnOnObsidian = false;
    /** Block id the boss is seated on when {@link #spawnOnObsidian}; default vanilla obsidian. */
    public String spawnAnchorBlock = "minecraft:obsidian";
    public String msgSignupOpen = "&c[Raid] &fSign-ups OPEN for {name}! Talk to {npc} or /rg raid join {name}.";
    public String msgStarting = "&6[Raid] &f{name} begins with {count} fighters!";
    public String msgBossSpawn = "&4[Raid] &f{boss} has appeared with &c{health} &fHP!";
    public String msgVictory = "&6[Raid] &f{boss} has been defeated! Top damage: &e{top} &f({top_percent}%)";
    public String msgTimeout = "&c[Raid] &fTime is up. {boss} escaped. Better luck next time!";

    // Schedule (0 = ignore: dayOfMonth -> monthly, else dayOfWeek -> weekly, else daily)
    public boolean scheduleEnabled = false;
    public int scheduleHour = 20;
    public int scheduleMinute = 0;
    public int scheduleDayOfWeek = 0;
    public int scheduleDayOfMonth = 0;
    /** if &gt; 0, also fires every this-many minutes (anchored at local midnight) on cadence-matching days; 0 = off */
    public int scheduleIntervalMinutes = 0;
    /** explicit HH:MM (24h) fire times; when non-empty these OVERRIDE the single scheduleHour/scheduleMinute */
    public java.util.List<String> scheduleTimes = new java.util.ArrayList<>();

    // Rewards (token format documented in RewardManager)
    public final List<String> participantRewards = new ArrayList<>();
    public final List<String> topRewards = new ArrayList<>();
    /** extra rewards for runner-up damage dealers, ranks 2 through 4 */
    public final List<String> runnerUpRewards = new ArrayList<>();

    public RaidBossDef() {}

    public RaidBossDef(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean hasArena() {
        return arena != null;
    }

    /** scaled max health for a participant count, never below baseHealth */
    public double scaledHealth(int participants) {
        double h = baseHealth + healthPerPlayer * Math.max(0, participants - 1);
        if (maxHealthCap > 0) h = Math.min(h, maxHealthCap);
        return Math.max(1.0, h);
    }

    /**
     * The most a single hit may take off {@code boss}, or -1 when this raid has no per-hit ceiling. Reads the boss's
     * LIVE max health rather than {@link #scaledHealth}, so a boss whose max was changed after spawn (a phase, an
     * admin attribute edit) keeps a ceiling that matches the health bar players are actually looking at.
     */
    public double maxDamagePerHit(net.minecraft.world.entity.LivingEntity boss) {
        if (maxDamagePercentPerHit <= 0 || boss == null) return -1;
        double cap = boss.getMaxHealth() * (maxDamagePercentPerHit / 100.0);
        // A percent small enough to round to nothing would make the boss immortal, so never floor the ceiling at 0.
        return Math.max(Double.MIN_NORMAL, cap);
    }

    /** a fresh definition seeded from the global Config defaults */
    public static RaidBossDef createDefault(String id, String name) {
        RaidBossDef d = new RaidBossDef(id, name);
        d.bossEntityType = Config.BOSS_ENTITY.get();
        d.bossName = Config.BOSS_NAME.get();
        d.baseHealth = Config.BASE_HEALTH.get();
        d.healthPerPlayer = Config.HEALTH_PER_PLAYER.get();
        d.maxHealthCap = Config.MAX_HEALTH_CAP.get();
        d.maxDamagePercentPerHit = Config.MAX_DAMAGE_PERCENT_PER_HIT.get();
        d.scaleDamage = Config.SCALE_DAMAGE.get();
        d.damagePerPlayer = Config.DAMAGE_PER_PLAYER.get();
        d.signupMinutes = Config.SIGNUP_MINUTES.get();
        d.signupReminderSeconds = Config.SIGNUP_REMINDER_SECONDS.get();
        d.minParticipants = Config.MIN_PARTICIPANTS.get();
        d.maxParticipants = Config.MAX_PARTICIPANTS.get();
        d.requireCharacter = Config.REQUIRE_CHARACTER.get();
        d.pvpInArena = Config.PVP_IN_ARENA.get();
        d.preventReentry = Config.PREVENT_REENTRY.get();
        d.healOnStart = Config.HEAL_ON_START.get();
        d.countdownSeconds = Config.COUNTDOWN_SECONDS.get();
        d.fightTimeLimit = Config.FIGHT_TIME_LIMIT.get();
        d.npcName = Config.NPC_NAME.get();
        d.scheduleHour = Config.SCHEDULE_HOUR.get();
        d.scheduleMinute = Config.SCHEDULE_MINUTE.get();
        d.scheduleDayOfWeek = Config.SCHEDULE_DAY_OF_WEEK.get();
        d.scheduleDayOfMonth = Config.SCHEDULE_DAY_OF_MONTH.get();
        d.useTitles = Config.USE_TITLES.get();
        d.announceSound = Config.ANNOUNCE_SOUND.get();
        d.msgSignupOpen = Config.MSG_SIGNUP_OPEN.get();
        d.msgStarting = Config.MSG_STARTING.get();
        d.msgBossSpawn = Config.MSG_BOSS_SPAWN.get();
        d.msgVictory = Config.MSG_VICTORY.get();
        d.msgTimeout = Config.MSG_TIMEOUT.get();
        Config.PARTICIPANT_REWARDS.get().forEach(s -> d.participantRewards.add(s));
        Config.TOP_REWARDS.get().forEach(s -> d.topRewards.add(s));
        Config.RUNNERUP_REWARDS.get().forEach(s -> d.runnerUpRewards.add(s));
        return d;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("name", name);
        t.putString("raidType", raidType.name());
        t.putString("category", category == null ? "General" : category);
        t.putBoolean("showInNpcMenu", showInNpcMenu);
        // Omitted when false so existing raid tags are byte-identical (no republish on the raids:defs sync).
        if (eventOnly) {
            t.putBoolean("eventOnly", true);
        }
        if (!randomPool.isEmpty()) {
            net.minecraft.nbt.ListTag pool = new net.minecraft.nbt.ListTag();
            for (String e : randomPool) pool.add(net.minecraft.nbt.StringTag.valueOf(e));
            t.put("randomPool", pool);
        }
        if (arena != null) t.put("arena", arena.save());
        if (playerSpawn != null) t.put("playerSpawn", playerSpawn.save());

        t.putString("bossEntityType", bossEntityType);
        t.putString("bossModelId", bossModelId);
        t.putString("bossName", bossName);

        t.putDouble("baseHealth", baseHealth);
        t.putDouble("healthPerPlayer", healthPerPlayer);
        t.putDouble("maxHealthCap", maxHealthCap);
        t.putDouble("maxDamagePctPerHit", maxDamagePercentPerHit);
        t.putBoolean("scaleDamage", scaleDamage);
        t.putBoolean("parallelBoss", parallelBoss);
        t.putDouble("damagePerPlayer", damagePerPlayer);

        t.putInt("battlePower", battlePower);
        t.putDouble("kiBlastDamage", kiBlastDamage);
        t.putDouble("moveSpeed", moveSpeed);
        t.putDouble("baseMeleeDamage", baseMeleeDamage);
        t.putDouble("baseDefense", baseDefense);
        t.putDouble("bossScale", bossScale);
        t.putInt("aiTier", aiTier);
        t.putBoolean("aiTier1Based", true); // marks aiTier as DMZ's 1-based id (migration guard)
        t.put("kiMoves", toList(kiMoves));

        // keys SORTED, not a plain .copy(). This tag is the read supplier for the "raids:rifts" and
        // "raids:defs" cross-server sync, which republishes a row whenever the serialised BYTES change. A
        // CompoundTag's map preserves INSERTION order, seeded per server, so two shards holding the SAME buff
        // in a different key order each read the other's row as a change and republished for ever (seen live
        // on .rifts[].encounter.bossTransform.forms[].buff, OW1 vs smp). Sorting makes the bytes a function
        // of content alone. Order carries no meaning on load, which reads keys straight into a map.
        t.put("bossTransform", sortedDeepCopy(bossTransform));
        t.putBoolean("useDefaultTransform", useDefaultTransform);

        t.put("enemies", toList(enemies));
        t.put("allies", toList(allies));
        t.putDouble("mvpKillWeight", mvpKillWeight);
        t.put("rushStages", toList(rushStages));

        t.putInt("signupMinutes", signupMinutes);
        t.putInt("signupReminderSeconds", signupReminderSeconds);
        t.putInt("minParticipants", minParticipants);
        t.putInt("maxParticipants", maxParticipants);
        t.putBoolean("requireCharacter", requireCharacter);

        t.putBoolean("pvpInArena", pvpInArena);
        t.putBoolean("preventReentry", preventReentry);
        t.putBoolean("vanillaDrops", vanillaDrops);
        t.putBoolean("healOnStart", healOnStart);
        t.putInt("countdownSeconds", countdownSeconds);
        t.putInt("fightTimeLimit", fightTimeLimit);

        t.putString("npcName", npcName);
        t.putString("npcEntityType", npcEntityType);
        t.putString("npcModelId", npcModelId);
        t.putBoolean("useTitles", useTitles);
        t.putString("announceSound", announceSound);
        t.putString("bossMusic", bossMusic == null ? "" : bossMusic);
        t.putBoolean("spawnOnObsidian", spawnOnObsidian);
        t.putString("spawnAnchorBlock", spawnAnchorBlock == null ? "minecraft:obsidian" : spawnAnchorBlock);
        t.putString("msgSignupOpen", msgSignupOpen);
        t.putString("msgStarting", msgStarting);
        t.putString("msgBossSpawn", msgBossSpawn);
        t.putString("msgVictory", msgVictory);
        t.putString("msgTimeout", msgTimeout);

        t.putBoolean("scheduleEnabled", scheduleEnabled);
        t.putInt("scheduleHour", scheduleHour);
        t.putInt("scheduleMinute", scheduleMinute);
        t.putInt("scheduleDayOfWeek", scheduleDayOfWeek);
        t.putInt("scheduleDayOfMonth", scheduleDayOfMonth);
        t.putInt("scheduleIntervalMinutes", scheduleIntervalMinutes);
        t.putString("scheduleTimes", String.join(",", scheduleTimes));

        t.put("participantRewards", toList(participantRewards));
        t.put("topRewards", toList(topRewards));
        t.put("runnerUpRewards", toList(runnerUpRewards));
        return t;
    }

    public static RaidBossDef load(CompoundTag t) {
        RaidBossDef d = new RaidBossDef();
        d.id = t.getString("id");
        d.name = t.getString("name");
        if (t.contains("raidType")) d.raidType = RaidType.byName(t.getString("raidType"));
        if (t.contains("category")) d.category = t.getString("category");
        if (t.contains("showInNpcMenu")) d.showInNpcMenu = t.getBoolean("showInNpcMenu");
        d.eventOnly = t.getBoolean("eventOnly"); // absent -> false (a normal raid)
        d.randomPool.clear();
        if (t.contains("randomPool")) {
            net.minecraft.nbt.ListTag pool = t.getList("randomPool", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < pool.size(); i++) d.randomPool.add(pool.getString(i));
        }
        if (t.contains("arena")) d.arena = Region.load(t.getCompound("arena"));
        if (t.contains("playerSpawn")) d.playerSpawn = Region.load(t.getCompound("playerSpawn"));

        if (t.contains("bossEntityType")) d.bossEntityType = t.getString("bossEntityType");
        if (t.contains("bossModelId")) d.bossModelId = t.getString("bossModelId");
        if (t.contains("bossName")) d.bossName = t.getString("bossName");

        d.baseHealth = t.getDouble("baseHealth");
        d.healthPerPlayer = t.getDouble("healthPerPlayer");
        d.maxHealthCap = t.getDouble("maxHealthCap");
        // contains(), not a bare getDouble: an absent key reads 0, and 0 is the "no ceiling" sentinel, so every raid
        // saved before this field existed would silently come back with its per-hit cap switched off.
        d.maxDamagePercentPerHit = t.contains("maxDamagePctPerHit")
                ? t.getDouble("maxDamagePctPerHit")
                : Config.MAX_DAMAGE_PERCENT_PER_HIT.get();
        d.scaleDamage = t.getBoolean("scaleDamage");
        d.parallelBoss = t.getBoolean("parallelBoss");
        d.damagePerPlayer = t.getDouble("damagePerPlayer");

        d.battlePower = t.getInt("battlePower");
        d.kiBlastDamage = t.getDouble("kiBlastDamage");
        d.moveSpeed = t.getDouble("moveSpeed");
        d.baseMeleeDamage = t.getDouble("baseMeleeDamage");
        d.baseDefense = t.getDouble("baseDefense"); // missing key -> 0 (no mitigation)
        d.bossScale = t.getDouble("bossScale");
        // aiTier stored as DMZ's 1-based id (1=SIMPLE 2=TACTICAL 3=ADVANCED; 0 = unset). Legacy defs (no
        // "aiTier1Based" marker) stored it 0-based with -1 = unset; migrate them.
        if (t.contains("aiTier")) {
            int stored = t.getInt("aiTier");
            if (t.getBoolean("aiTier1Based")) {
                d.aiTier = stored;
            } else {
                // legacy 0-based: -1 -> 0 (unset); 0/1/2 -> 1/2/3
                d.aiTier = stored < 0 ? 0 : stored + 1;
            }
        } else {
            d.aiTier = 0;
        }
        fromList(t.getList("kiMoves", Tag.TAG_STRING), d.kiMoves);

        d.bossTransform = t.contains("bossTransform") ? t.getCompound("bossTransform").copy() : new CompoundTag();
        d.useDefaultTransform = t.contains("useDefaultTransform") ? t.getBoolean("useDefaultTransform") : true;

        fromList(t.getList("enemies", Tag.TAG_STRING), d.enemies);
        // absent on pre-allies defs; fromList adds nothing, which reads correctly as no allies
        fromList(t.getList("allies", Tag.TAG_STRING), d.allies);
        if (t.contains("mvpKillWeight")) d.mvpKillWeight = t.getDouble("mvpKillWeight");
        fromList(t.getList("rushStages", Tag.TAG_STRING), d.rushStages);

        d.signupMinutes = t.getInt("signupMinutes");
        d.signupReminderSeconds = t.contains("signupReminderSeconds") ? t.getInt("signupReminderSeconds") : 30;
        d.minParticipants = t.getInt("minParticipants");
        d.maxParticipants = t.getInt("maxParticipants");
        d.requireCharacter = t.getBoolean("requireCharacter");

        d.pvpInArena = t.getBoolean("pvpInArena");
        d.preventReentry = t.getBoolean("preventReentry");
        d.vanillaDrops = t.getBoolean("vanillaDrops");
        d.healOnStart = t.getBoolean("healOnStart");
        d.countdownSeconds = t.getInt("countdownSeconds");
        d.fightTimeLimit = t.getInt("fightTimeLimit");

        d.npcName = t.getString("npcName");
        if (t.contains("npcEntityType")) d.npcEntityType =
                net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(t.getString("npcEntityType"));
        if (t.contains("npcModelId")) d.npcModelId = t.getString("npcModelId");
        d.useTitles = t.getBoolean("useTitles");
        d.announceSound = t.getString("announceSound");
        // absent on pre-field defs: reads "" (no music), the intended default
        d.bossMusic = t.getString("bossMusic");
        d.spawnOnObsidian = t.getBoolean("spawnOnObsidian"); // missing -> false (normal spawn)
        d.spawnAnchorBlock = t.contains("spawnAnchorBlock") ? t.getString("spawnAnchorBlock") : "minecraft:obsidian";
        d.msgSignupOpen = t.getString("msgSignupOpen");
        d.msgStarting = t.getString("msgStarting");
        d.msgBossSpawn = t.getString("msgBossSpawn");
        d.msgVictory = t.getString("msgVictory");
        d.msgTimeout = t.getString("msgTimeout");

        d.scheduleEnabled = t.getBoolean("scheduleEnabled");
        d.scheduleHour = t.getInt("scheduleHour");
        d.scheduleMinute = t.getInt("scheduleMinute");
        d.scheduleDayOfWeek = t.getInt("scheduleDayOfWeek");
        d.scheduleDayOfMonth = t.getInt("scheduleDayOfMonth");
        d.scheduleIntervalMinutes = t.getInt("scheduleIntervalMinutes"); // missing key -> 0 (off)
        d.scheduleTimes.clear();
        if (t.contains("scheduleTimes")) {
            for (String s : t.getString("scheduleTimes").split(",")) {
                String v = s.trim();
                if (!v.isEmpty()) d.scheduleTimes.add(v);
            }
        }

        fromList(t.getList("participantRewards", Tag.TAG_STRING), d.participantRewards);
        fromList(t.getList("topRewards", Tag.TAG_STRING), d.topRewards);
        fromList(t.getList("runnerUpRewards", Tag.TAG_STRING), d.runnerUpRewards);
        return d;
    }

    /**
     * Deep copy with every nested compound's keys sorted, lists left in order. Safe because a compound is
     * name-keyed and nothing reads it positionally; a list IS positional, so its order is preserved. Used
     * only where the bytes feed a hash-compared sync, to keep output a function of content, not insertion
     * order.
     */
    private static CompoundTag sortedDeepCopy(CompoundTag tag) {
        CompoundTag out = new CompoundTag();
        List<String> keys = new ArrayList<>(tag.getAllKeys());
        Collections.sort(keys);
        for (String key : keys) {
            out.put(key, sortedDeepCopy(tag.get(key)));
        }
        return out;
    }

    private static Tag sortedDeepCopy(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return sortedDeepCopy(compound);
        }
        if (tag instanceof ListTag list) {
            ListTag out = new ListTag();
            // list order is meaningful, preserved; only the compounds inside get their keys sorted
            for (Tag element : list) {
                out.add(sortedDeepCopy(element));
            }
            return out;
        }
        return tag.copy();
    }

    private static ListTag toList(List<String> src) {
        ListTag list = new ListTag();
        for (String s : src) list.add(StringTag.valueOf(s));
        return list;
    }

    private static void fromList(ListTag list, List<String> target) {
        target.clear();
        for (int i = 0; i < list.size(); i++) target.add(list.getString(i));
    }
}
