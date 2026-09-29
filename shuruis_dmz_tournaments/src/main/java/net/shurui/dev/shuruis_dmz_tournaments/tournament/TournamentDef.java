package net.shurui.dev.shuruis_dmz_tournaments.tournament;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;

import java.util.ArrayList;
import java.util.List;

// a named tournament definition. many can exist at once, each with its own arena/waiting/stands bounds,
// schedule, announcements, rewards. edited via TournamentEditScreen, stored in TournamentData.
// serialized to a CompoundTag for both world-save and network transfer to the editor client.
public class TournamentDef {
    public String id;                 // unique key, lowercase
    public String name = "Tournament";

    // SOLO 1v1, DUOS 2v2, TRIOS 3v3, FFA free-for-all. stored as the enum name.
    public String format = "SOLO";
    // groups tournaments under collapsible dropdowns in the NPC browser
    public String group = "";

    public Region arena;
    public Region waiting;
    public Region stands;

    // schedule. cadence from the day fields (0 = ignore): dayOfMonth>0 monthly, else dayOfWeek>0 weekly,
    // else daily. hour/minute are always the time of day (0 = midnight, not ignored).
    public boolean scheduleEnabled = false;
    public int scheduleHour = 18;
    public int scheduleMinute = 0;
    public int scheduleDayOfWeek = 0;   // 0 = ignore
    public int scheduleDayOfMonth = 0;  // 0 = ignore

    // sign-up / limits
    public int signupMinutes = 10;
    public int minParticipants = 2;
    public int maxParticipants = 16;
    public boolean requireCharacter = true;

    // whether THIS tournament swaps contestants into a dedicated tournament character on entry. When off it never
    // touches the real character (they fight as themselves, inventory optionally vaulted). Seeded from the global
    // Config default and still gated by the global master switch (global off = off everywhere).
    public boolean tournamentChars = true;

    // rules
    public boolean itemsAllowed = false;
    public boolean healAfterFight = true;
    public boolean forceFriendlyFist = true;
    public boolean pvpInWaiting = false;
    public boolean pvpInStands = false;
    public boolean ringOut = true;          // leaving arena X/Z eliminates a fighter

    // FLOOR-OUT (Tournament of Power style): drop below floorOutY and you are eliminated, whether airborne or not.
    // Kept as a separate boolean plus an int rather than a sentinel int, for two reasons: every int is a legitimate
    // threshold (0 is a normal build height and 1.20.1 worlds reach -64, so no int value can safely mean "off"), and
    // splitting them lets an operator pre-set a Y, toggle the rule off, and toggle it back on without losing the
    // number. It is fully independent of ringOut, so a def can run ring-out only, floor-out only, both, or neither.
    // Off by default so no existing tournament changes behaviour.
    public boolean floorOut = false;
    public int floorOutY = 0;

    // TITLE-HOLDER LOCKOUT, PER TITLE: the ids of the titles that bar entry to THIS tournament. A player is refused
    // only when they hold at least one id in this set; everyone else, including the reigning champion, enters freely.
    // Empty means nobody is barred, which is the default so no existing tournament changes behaviour. Example: a
    // Tournament of Power lists god_of_destruction and angel, so a G.O.D. or an Angel is turned away while the World
    // Martial Arts champion competes. The ids match the tournaments titles.definitions config and the RoleTitles
    // constants; the holder map they are checked against (tournaments:titles) is cross-shard synced, so a crown won on
    // another shard bars entry here too. It is a LinkedHashSet only so the editor can echo the operator's ids back in a
    // stable way; the SAVE path SORTS the ids (see save()), which is load-bearing, not cosmetic: this def feeds the
    // tournaments:defs sync blob, hashed and republished whenever its bytes change, and an unordered collection
    // re-serialised in a different order each save would loop shards into republishing each other forever. That exact
    // bug has hit this repo three times (DungeonFloors.saveEpochs, DungeonCrateData.save, TransformForm stat buffs).
    public final java.util.Set<String> excludeTitleIds = new java.util.LinkedHashSet<>();

    /** True when this player, given the titles they currently hold, is barred from entry by this def. */
    public boolean barsAny(java.util.Collection<String> heldTitleIds) {
        if (excludeTitleIds.isEmpty()) return false;
        for (String held : heldTitleIds) if (excludeTitleIds.contains(held)) return true;
        return false;
    }

    /** The held titles this def actually bars, so a refusal can name the offending crown. Empty when none apply. */
    public java.util.List<String> barringTitles(java.util.Collection<String> heldTitleIds) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String held : heldTitleIds) if (excludeTitleIds.contains(held)) out.add(held);
        return out;
    }

    /** Comma-separated barring ids for the editor text field, in sorted (canonical) order; blank when nobody is barred. */
    public String excludeTitleIdsCsv() {
        java.util.List<String> sorted = new java.util.ArrayList<>(excludeTitleIds);
        java.util.Collections.sort(sorted);
        return String.join(", ", sorted);
    }

    /** Replace the barring set from the editor text field: split on commas, trim, drop blanks. */
    public void setExcludeTitleIdsFromCsv(String csv) {
        excludeTitleIds.clear();
        if (csv == null) return;
        for (String part : csv.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) excludeTitleIds.add(s);
        }
    }

    // FLIGHT, two independent switches:
    //   flightAllowed = false -> DMZ's fly skill off for every fighter, whole match: a grounded, on-foot format.
    //   groundOut     = true  -> once a fighter has taken off, touching the ground eliminates them: an AERIAL format.
    // ground-out is ignored when flight is off, since a fighter who cannot fly could never get back up.
    public boolean flightAllowed = true;
    public boolean groundOut = false;
    public boolean allowRandomFill = true;  // team formats: auto-fill leftover solos into teams
    public int countdownSeconds = 5;
    public int matchTimeLimit = 300;
    public int signupReminderSeconds = 30;  // repeat the join prompt every N seconds, 0 = off

    // NPC / announcements
    public String npcName = "Hercule";
    public String npcEntityType = "dmz_ragnarok:tournament_npc"; // any registered entity id

    /**
     * Which ragnarok NPC model the host wears, when {@link #npcEntityType} is {@code dmz_ragnarok:rgnpc}. All 386
     * ragnarok NPCs share one {@code rgnpc} entity whose look is a field on the instance, not a type of its own, so
     * without this field picking rgnpc could only spawn the default model. Blank means "leave the entity alone",
     * which every other entity type and every pre-field tournament wants.
     */
    public String npcModelId = "";
    public boolean useTitles = true;
    public String announceSound = "minecraft:ui.toast.challenge_complete";
    public String msgSignupOpen = "&e[Tournament] &fSign-ups OPEN for {name}! Talk to {npc} or /rg tourney join {id}.";
    public String msgStarting = "&6[Tournament] &f{name} is starting with {count} fighters!";
    public String msgMatch = "&6[{name}] &fNext match: &e{p1} &fvs &e{p2}&f!";
    public String msgMatchWin = "&6[{name}] &e{winner} &fdefeats &e{loser}&f!";
    public String msgChampion = "&6[{name}] &e{champion} &fis the new CHAMPION!";

    // rewards (token format documented in RewardManager)
    public final List<String> winnerRewards = new ArrayList<>();
    public final List<String> runnerUpRewards = new ArrayList<>();
    public final List<String> participationRewards = new ArrayList<>();

    public TournamentDef() {}

    public TournamentDef(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean hasAllRegions() {
        return arena != null && waiting != null && stands != null;
    }

    public TournamentFormat format() {
        return TournamentFormat.fromId(format);
    }

    // fresh def seeded from the global Config defaults
    public static TournamentDef createDefault(String id, String name) {
        TournamentDef d = new TournamentDef(id, name);
        d.scheduleEnabled = !Config.SCHEDULE_MODE.get().equalsIgnoreCase("MANUAL");
        d.scheduleHour = Config.SCHEDULE_HOUR.get();
        d.scheduleMinute = Config.SCHEDULE_MINUTE.get();
        d.scheduleDayOfWeek = Config.SCHEDULE_DAY_OF_WEEK.get();
        d.scheduleDayOfMonth = Config.SCHEDULE_DAY_OF_MONTH.get();
        d.signupMinutes = Config.SIGNUP_MINUTES.get();
        d.minParticipants = Config.MIN_PARTICIPANTS.get();
        d.maxParticipants = Config.MAX_PARTICIPANTS.get();
        d.requireCharacter = Config.REQUIRE_CHARACTER.get();
        d.tournamentChars = Config.TOURNAMENT_CHARS_ENABLED.get();
        d.itemsAllowed = Config.ITEMS_ALLOWED.get();
        d.healAfterFight = Config.HEAL_AFTER_FIGHT.get();
        d.forceFriendlyFist = Config.FORCE_FRIENDLY_FIST.get();
        d.pvpInWaiting = Config.PVP_IN_WAITING.get();
        d.pvpInStands = Config.PVP_IN_STANDS.get();
        d.countdownSeconds = Config.COUNTDOWN_SECONDS.get();
        d.matchTimeLimit = Config.MATCH_TIME_LIMIT.get();
        d.signupReminderSeconds = Config.SIGNUP_REMINDER_SECONDS.get();
        d.npcName = Config.NPC_NAME.get();
        d.useTitles = Config.USE_TITLES.get();
        d.announceSound = Config.ANNOUNCE_SOUND.get();
        d.msgSignupOpen = Config.MSG_SIGNUP_OPEN.get();
        d.msgStarting = Config.MSG_STARTING.get();
        d.msgMatch = Config.MSG_MATCH.get();
        d.msgMatchWin = Config.MSG_MATCH_WIN.get();
        d.msgChampion = Config.MSG_CHAMPION.get();
        Config.WINNER_REWARDS.get().forEach(s -> d.winnerRewards.add(s));
        Config.RUNNERUP_REWARDS.get().forEach(s -> d.runnerUpRewards.add(s));
        Config.PARTICIPATION_REWARDS.get().forEach(s -> d.participationRewards.add(s));
        return d;
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("name", name);
        t.putString("format", format == null ? "SOLO" : format);
        t.putString("group", group == null ? "" : group);
        if (arena != null) t.put("arena", arena.save());
        if (waiting != null) t.put("waiting", waiting.save());
        if (stands != null) t.put("stands", stands.save());

        t.putBoolean("scheduleEnabled", scheduleEnabled);
        t.putInt("scheduleHour", scheduleHour);
        t.putInt("scheduleMinute", scheduleMinute);
        t.putInt("scheduleDayOfWeek", scheduleDayOfWeek);
        t.putInt("scheduleDayOfMonth", scheduleDayOfMonth);

        t.putInt("signupMinutes", signupMinutes);
        t.putInt("minParticipants", minParticipants);
        t.putInt("maxParticipants", maxParticipants);
        t.putBoolean("requireCharacter", requireCharacter);
        t.putBoolean("tournamentChars", tournamentChars);

        t.putBoolean("itemsAllowed", itemsAllowed);
        t.putBoolean("healAfterFight", healAfterFight);
        t.putBoolean("forceFriendlyFist", forceFriendlyFist);
        t.putBoolean("pvpInWaiting", pvpInWaiting);
        t.putBoolean("pvpInStands", pvpInStands);
        t.putBoolean("ringOut", ringOut);
        // Both scalars, so the sync save path stays deterministic (no unordered collection introduced).
        t.putBoolean("floorOut", floorOut);
        t.putInt("floorOutY", floorOutY);
        // PER-TITLE lockout, written as a SORTED list. The sort is load-bearing, not cosmetic: this tag feeds the
        // tournaments:defs ShardStateSync blob, which is hashed and republished whenever its bytes change. A Set
        // iterates in an unspecified order, so re-serialising it raw could emit the same ids in a different byte order
        // on the next save and trip the endless cross-shard republish loop (the DungeonFloors/DungeonCrateData/
        // TransformForm bug). Sorting pins the bytes.
        java.util.List<String> sortedTitleIds = new java.util.ArrayList<>(excludeTitleIds);
        java.util.Collections.sort(sortedTitleIds);
        t.put("excludeTitleIds", toList(sortedTitleIds));
        t.putBoolean("flightAllowed", flightAllowed);
        t.putBoolean("groundOut", groundOut);
        t.putBoolean("allowRandomFill", allowRandomFill);
        t.putInt("countdownSeconds", countdownSeconds);
        t.putInt("matchTimeLimit", matchTimeLimit);
        t.putInt("signupReminderSeconds", signupReminderSeconds);

        t.putString("npcName", npcName);
        t.putString("npcEntityType", npcEntityType);
        t.putString("npcModelId", npcModelId);
        t.putBoolean("useTitles", useTitles);
        t.putString("announceSound", announceSound);
        t.putString("msgSignupOpen", msgSignupOpen);
        t.putString("msgStarting", msgStarting);
        t.putString("msgMatch", msgMatch);
        t.putString("msgMatchWin", msgMatchWin);
        t.putString("msgChampion", msgChampion);

        t.put("winnerRewards", toList(winnerRewards));
        t.put("runnerUpRewards", toList(runnerUpRewards));
        t.put("participationRewards", toList(participationRewards));
        return t;
    }

    public static TournamentDef load(CompoundTag t) {
        TournamentDef d = new TournamentDef();
        d.id = t.getString("id");
        d.name = t.getString("name");
        if (t.contains("format")) d.format = t.getString("format");
        if (t.contains("group")) d.group = t.getString("group");
        if (t.contains("arena")) d.arena = Region.load(t.getCompound("arena"));
        if (t.contains("waiting")) d.waiting = Region.load(t.getCompound("waiting"));
        if (t.contains("stands")) d.stands = Region.load(t.getCompound("stands"));

        d.scheduleEnabled = t.getBoolean("scheduleEnabled");
        d.scheduleHour = t.getInt("scheduleHour");
        d.scheduleMinute = t.getInt("scheduleMinute");
        d.scheduleDayOfWeek = t.getInt("scheduleDayOfWeek");
        d.scheduleDayOfMonth = t.getInt("scheduleDayOfMonth");

        d.signupMinutes = t.getInt("signupMinutes");
        d.minParticipants = t.getInt("minParticipants");
        d.maxParticipants = t.getInt("maxParticipants");
        d.requireCharacter = t.getBoolean("requireCharacter");
        // absent on pre-toggle defs: default true to preserve their global-config-gated behaviour. The global
        // master switch still overrides.
        d.tournamentChars = t.contains("tournamentChars") ? t.getBoolean("tournamentChars") : true;

        d.itemsAllowed = t.getBoolean("itemsAllowed");
        d.healAfterFight = t.getBoolean("healAfterFight");
        d.forceFriendlyFist = t.getBoolean("forceFriendlyFist");
        d.pvpInWaiting = t.getBoolean("pvpInWaiting");
        d.pvpInStands = t.getBoolean("pvpInStands");
        d.ringOut = t.contains("ringOut") ? t.getBoolean("ringOut") : true;
        // Absent on pre-floor-out defs: default off so nothing changes for them. floorOutY is meaningless while
        // floorOut is off, but it is read regardless so a saved threshold survives a toggle off then on.
        d.floorOut = t.contains("floorOut") && t.getBoolean("floorOut");
        d.floorOutY = t.contains("floorOutY") ? t.getInt("floorOutY") : 0;
        // PER-TITLE lockout, three cases handled in order:
        //  - new list tag present: load the barring ids verbatim (already sorted on save; order here is irrelevant to
        //    behaviour, the set is a membership test).
        //  - only the OLD boolean present and true: this came from the previous "bar ANY title holder" pass. Migrate it
        //    to "bar every title defined RIGHT NOW" by snapshotting Config.TITLE_DEFS, so an operator's existing setting
        //    is not silently dropped. This is a faithful reading (the boolean meant exactly "any current title bars"),
        //    with one deliberate difference: a title added LATER will not auto-bar, because per-title is now explicit
        //    and the operator lists the ids they want in the editor.
        //  - neither present (pre-lockout def): empty set, nobody barred, behaviour unchanged.
        d.excludeTitleIds.clear();
        if (t.contains("excludeTitleIds", Tag.TAG_LIST)) {
            ListTag titleList = t.getList("excludeTitleIds", Tag.TAG_STRING);
            for (int i = 0; i < titleList.size(); i++) {
                String s = titleList.getString(i).trim();
                if (!s.isEmpty()) d.excludeTitleIds.add(s);
            }
        } else if (t.getBoolean("excludeTitleHolders")) {
            for (String titleDef : Config.TITLE_DEFS.get()) {
                int sep = titleDef.indexOf('|');
                String tid = (sep > 0 ? titleDef.substring(0, sep) : titleDef).trim();
                if (!tid.isEmpty()) d.excludeTitleIds.add(tid);
            }
        }
        // Absent on every definition written before these existed, so both default to the old behaviour:
        // flight as normal, the ground harmless.
        d.flightAllowed = !t.contains("flightAllowed") || t.getBoolean("flightAllowed");
        d.groundOut = t.contains("groundOut") && t.getBoolean("groundOut");
        d.allowRandomFill = t.contains("allowRandomFill") ? t.getBoolean("allowRandomFill") : true;
        d.countdownSeconds = t.getInt("countdownSeconds");
        d.matchTimeLimit = t.getInt("matchTimeLimit");
        d.signupReminderSeconds = t.contains("signupReminderSeconds") ? t.getInt("signupReminderSeconds") : 30;

        d.npcName = t.getString("npcName");
        if (t.contains("npcEntityType")) d.npcEntityType =
                net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(t.getString("npcEntityType"));
        if (t.contains("npcModelId")) d.npcModelId = t.getString("npcModelId");
        d.useTitles = t.getBoolean("useTitles");
        d.announceSound = t.getString("announceSound");
        d.msgSignupOpen = t.getString("msgSignupOpen");
        d.msgStarting = t.getString("msgStarting");
        d.msgMatch = t.getString("msgMatch");
        d.msgMatchWin = t.getString("msgMatchWin");
        d.msgChampion = t.getString("msgChampion");

        fromList(t.getList("winnerRewards", Tag.TAG_STRING), d.winnerRewards);
        fromList(t.getList("runnerUpRewards", Tag.TAG_STRING), d.runnerUpRewards);
        fromList(t.getList("participationRewards", Tag.TAG_STRING), d.participationRewards);
        return d;
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
