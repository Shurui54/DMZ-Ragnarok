package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.events.EventDef;
import net.shurui.shuruisutilities.events.EventSchedule;
import net.shurui.shuruisutilities.events.network.PacketEventEditorSave;

/**
 * The admin whole-event editor (E11). It edits a LOCAL {@link EventDef} copy decoded from the packet-128 payload
 * and, on Save, resolves the authored local window into absolute instants, serialises the whole def to NBT and
 * ships it over packet 129 ({@link PacketEventEditorSave}); the server (Ragnarok Key) is authoritative and
 * validates before persisting. Nothing here talks to the key directly, so core carries no event logic.
 *
 * <p>Built on the shared {@link FieldEditScreen}/{@link GuiTheme} idiom (the same one the crate and NPC-region
 * editors use): a named-entity tab header, {@code tf}/{@code bf}/{@code df}/{@code cf}/{@code dfMulti} rows, DMZ
 * menu art and sounds, no home-made fill-rect chrome and no subtitle counters. E11a shipped the pure-field tabs
 * (General, Schedule, Theme, Announce, Boosts, Rewards); E11b/c turned Content into a second-level nav over the
 * per-row sub-editors (Regions, Loot, Quests, Shop, Holograms, Floors), each editing the local def in place.
 */
public class EventEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.event.tab_general",
            "gui.dmz_ragnarok.core.event.tab_schedule",
            "gui.dmz_ragnarok.core.event.tab_theme",
            "gui.dmz_ragnarok.core.event.tab_announce",
            "gui.dmz_ragnarok.core.event.tab_boosts",
            "gui.dmz_ragnarok.core.event.tab_rewards",
            "gui.dmz_ragnarok.core.event.tab_content" };
    private static final int SECTION_CONTENT = 6;

    /** The Content tab's second-level nav, in index order (the sub switch in buildContent matches it). */
    private static final String[] SUB_KEYS = {
            "gui.dmz_ragnarok.core.event.sub_regions",
            "gui.dmz_ragnarok.core.event.sub_loot",
            "gui.dmz_ragnarok.core.event.sub_quests",
            "gui.dmz_ragnarok.core.event.sub_shop",
            "gui.dmz_ragnarok.core.event.sub_holograms",
            "gui.dmz_ragnarok.core.event.sub_floors",
            "gui.dmz_ragnarok.core.event.sub_lootboxes" };
    private static final int SUB_FLOORS = 5;

    /** The event-token variants the loot box token helper and the Rewards token field offer, matching
     * {@code EventTokenItem}'s variants. A stored off-list variant is still shown and stays selectable. */
    private static final List<String> TOKEN_VARIANTS = List.of(
            "halloween", "candy", "candy_corn", "chocolate_dabura", "chocolate_dabura_wrapped");

    /** The theme keys the website styles ({@code body.theme-<key>}); "" is none. Extend this one list to add more;
     * a stored off-list key is still shown and stays selectable. */
    private static final List<String> THEME_KEYS = List.of("halloween");

    private static final List<String> LOOT_HOSTS;
    static
    {
        List<String> hosts = new ArrayList<>();
        for (EventDef.LootHost h : EventDef.LootHost.values())
            hosts.add(h.name());
        LOOT_HOSTS = List.copyOf(hosts);
    }

    /** The counters the key records (EventQuests / EventLeaderboard); df keeps an off-list value too. */
    private static final List<String> METRICS = List.of(
            "EVENT_MOB_KILL", "REGION_KILL", "EVENT_CRATE_OPEN", "EVENT_FLOOR_CLEAR", "TOKEN_EARNED");

    private final EventDef def;
    private final List<String> pickRegions;
    private final List<String> pickRaids;
    private final List<String> pickRifts;
    /** The server's live dimension ids (overworld, nether, end, dungeon and any dynamic/space dims), from packet 128. */
    private final List<String> pickDims;
    /** "*" (every region) plus the region names, the option list for the region-mob targets multi-select. */
    private final List<String> targetOptions = new ArrayList<>();
    /** Floor picker for "add floor": values are floor numbers, labels the provider's human labels. */
    private final List<String> floorValues = new ArrayList<>();
    private final List<String> floorLabels = new ArrayList<>();
    /** Cosmetic catalogue picker for the loot box reward helper (ids + human names, from the packet-128 picks). */
    private final List<String> pickCosmeticIds = new ArrayList<>();
    private final List<String> pickCosmeticNames = new ArrayList<>();
    private int section = 0;

    // Content sub-editor state: the active sub-section, the shared temp-field set its add block binds to, the row
    // being edited (-1 = the block adds a new row) and the list scroll.
    private int sub = 0;
    private final String[] ct = new String[6];
    private int cEdit = -1;
    private int cScroll = 0;
    /** The loot box being edited on the Loot Boxes sub (index into content.lootBoxes), or -1 for the box list. */
    private int lbBox = -1;
    /** Within one box: false shows its Settings view, true shows its Rewards view (keeps each view short enough). */
    private boolean lbRewards = false;
    /** The label typed for a floor add, applied when the server's copied config comes back (acceptFloor). */
    private String pendingFloorLabel = "";

    public EventEditScreen(CompoundTag eventTag, CompoundTag picks)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.events"), UI_W, UI_H, null);
        this.def = EventDef.fromNbt(eventTag == null ? new CompoundTag() : eventTag);
        CompoundTag p = picks == null ? new CompoundTag() : picks;
        this.pickRegions = readList(p, "regions");
        this.pickRaids = readList(p, "raids");
        this.pickRifts = readList(p, "rifts");
        this.pickDims = readList(p, "dims");
        targetOptions.add("*");
        targetOptions.addAll(pickRegions);
        ListTag floors = p.getList("floors", Tag.TAG_COMPOUND);
        for (int i = 0; i < floors.size(); i++)
        {
            CompoundTag f = floors.getCompound(i);
            floorValues.add(Integer.toString(f.getInt("n")));
            floorLabels.add(f.getString("label"));
        }
        pickCosmeticIds.addAll(readList(p, "cosmeticIds"));
        pickCosmeticNames.addAll(readList(p, "cosmeticNames"));
        java.util.Arrays.fill(ct, "");
    }

    private static List<String> readList(CompoundTag t, String key)
    {
        List<String> out = new ArrayList<>();
        ListTag list = t.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++)
            out.add(list.getString(i));
        return out;
    }

    private void selectSection(int s)
    {
        applyFields();
        section = s;
        rebuildWidgets();
    }

    /**
     * Jump straight to the Content tab's Loot Boxes sub, optionally into one box's reward editor ({@code boxIndex}
     * &gt;= 0) or the box list ({@code boxIndex} &lt; 0). For the dev visual-test harness; safe to call once the
     * screen is initialised.
     */
    public void showLootBoxesTab(int boxIndex)
    {
        showLootBoxesTab(boxIndex, false);
    }

    public void showLootBoxesTab(int boxIndex, boolean rewards)
    {
        section = SECTION_CONTENT;
        sub = SUB_KEYS.length - 1;
        resetContentInputs();
        lbBox = boxIndex;
        lbRewards = rewards;
        rebuildWidgets();
    }

    /** Jump to a top-level tab (0 General .. 5 Rewards, 6 Content) for the dev visual-test harness. */
    public void showSection(int s)
    {
        section = Math.max(0, Math.min(s, SECTION_KEYS.length - 1));
        rebuildWidgets();
    }

    /** Jump straight to a Content sub-section (0 Regions .. 6 Loot Boxes) for the dev visual-test harness. */
    public void showContentSub(int subIndex)
    {
        section = SECTION_CONTENT;
        sub = Math.max(0, Math.min(subIndex, SUB_KEYS.length - 1));
        lbBox = -1;
        resetContentInputs();
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // The header draws the name with '&' colour codes translated ("&6Halloween" shows gold, not "&6"); the
        // General tab's Name field keeps the raw '&' form, which is what is saved.
        String name = def.name == null || def.name.isBlank() ? def.id : def.name;
        rowY = buildNamedTabHeader(name.replace('&', '§'), trAll(SECTION_KEYS), section, this::selectSection);

        switch (section)
        {
            case 0 -> buildGeneral();
            case 1 -> buildSchedule();
            case 2 -> buildTheme();
            case 3 -> buildAnnounce();
            case 4 -> buildBoosts();
            case 5 -> buildRewards();
            default -> buildContent();
        }

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> { applyFields(); EditorScreens.reopen("events"); });
        btn(80, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"),
                this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void buildGeneral()
    {
        label("§7" + tr("gui.dmz_ragnarok.core.event.field_id") + " §f" + def.id, 14, rowY + 2);
        rowY += ROW_H;
        tf(tr("gui.dmz_ragnarok.core.event.field_name"), def.name, v -> def.name = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_desc"), def.description, v -> def.description = v);
        bf(tr("gui.dmz_ragnarok.core.event.field_enabled"), def.enabled, () -> def.enabled = !def.enabled);
        df(tr("gui.dmz_ragnarok.core.event.field_manual"),
                List.of("AUTO", "FORCE_ON", "FORCE_OFF"), def.manualState.name(),
                v -> def.manualState = EventDef.ManualState.parse(v == null || v.isBlank() ? "AUTO" : v));
    }

    private void buildSchedule()
    {
        tf(tr("gui.dmz_ragnarok.core.event.field_zone"), def.schedule.zone,
                v -> def.schedule.zone = v == null || v.isBlank() ? "UTC" : v.trim());
        tf(tr("gui.dmz_ragnarok.core.event.field_start_local"), def.schedule.startLocal,
                v -> def.schedule.startLocal = v == null ? "" : v.trim());
        tf(tr("gui.dmz_ragnarok.core.event.field_end_local"), def.schedule.endLocal,
                v -> def.schedule.endLocal = v == null ? "" : v.trim());
        bf(tr("gui.dmz_ragnarok.core.event.field_annual"), def.schedule.annual,
                () -> def.schedule.annual = !def.schedule.annual);
        label("§7" + tr("gui.dmz_ragnarok.core.event.resolved") + " §f" + resolvedWindow(), 14, rowY + 4);
        rowY += ROW_H;
        label("§8" + tr("gui.dmz_ragnarok.core.event.schedule_hint"), 14, rowY + 2);
    }

    /** A read-only preview of the window the current local strings + zone resolve to, in UTC, or a hint if unset. */
    private String resolvedWindow()
    {
        long s = tryResolve(def.schedule.startLocal, def.schedule.zone, def.schedule.startEpochMillis);
        long e = tryResolve(def.schedule.endLocal, def.schedule.zone, def.schedule.endEpochMillis);
        if (s <= 0 && e <= 0)
            return tr("gui.dmz_ragnarok.core.event.resolved_unset");
        return EventSchedule.toDbUtc(s) + " -> " + EventSchedule.toDbUtc(e) + " UTC";
    }

    private static long tryResolve(String local, String zone, long fallback)
    {
        if (local == null || local.isBlank())
            return fallback;
        try
        {
            return EventSchedule.resolveEpoch(local, zone == null || zone.isBlank() ? "UTC" : zone);
        }
        catch (Exception e)
        {
            return fallback;
        }
    }

    private void buildTheme()
    {
        df(tr("gui.dmz_ragnarok.core.event.field_theme_key"), THEME_KEYS, def.theme.key,
                v -> def.theme.key = v == null ? "" : v.trim());
        tip(tr("gui.dmz_ragnarok.core.event.theme_key_tip"));
        cf(tr("gui.dmz_ragnarok.core.event.field_primary"), () -> def.theme.primary, v -> def.theme.primary = v);
        cf(tr("gui.dmz_ragnarok.core.event.field_accent"), () -> def.theme.accent, v -> def.theme.accent = v);
        cf(tr("gui.dmz_ragnarok.core.event.field_background"), () -> def.theme.background, v -> def.theme.background = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_banner_text"), def.theme.bannerText, v -> def.theme.bannerText = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_banner"), def.theme.banner, v -> def.theme.banner = v);
    }

    private void buildAnnounce()
    {
        bf(tr("gui.dmz_ragnarok.core.event.field_announce"), def.announce.announce,
                () -> def.announce.announce = !def.announce.announce);
        tf(tr("gui.dmz_ragnarok.core.event.field_reminder"), intStr(def.announce.reminderMinutes),
                v -> def.announce.reminderMinutes = Math.max(0, parseI(v, def.announce.reminderMinutes)));
        tf(tr("gui.dmz_ragnarok.core.event.field_start_title"), def.announce.inGameStartTitle,
                v -> def.announce.inGameStartTitle = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_start_msg"), def.announce.inGameStartMessage,
                v -> def.announce.inGameStartMessage = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_end_msg"), def.announce.inGameEndMessage,
                v -> def.announce.inGameEndMessage = v);
    }

    private void buildBoosts()
    {
        tf(tr("gui.dmz_ragnarok.core.event.field_tp_mult"), dbl(def.content.boosts.tpMultiplier),
                v -> def.content.boosts.tpMultiplier = Math.max(0.0, parseD(v, def.content.boosts.tpMultiplier)));
        tf(tr("gui.dmz_ragnarok.core.event.field_spawn_mult"), dbl(def.content.boosts.spawnRateMultiplier),
                v -> def.content.boosts.spawnRateMultiplier =
                        Math.max(0.0, parseD(v, def.content.boosts.spawnRateMultiplier)));
    }

    private void buildRewards()
    {
        df(tr("gui.dmz_ragnarok.core.event.field_token_variant"), TOKEN_VARIANTS, def.content.tokens.variant,
                v -> def.content.tokens.variant = v == null ? "" : v.trim());
        tf(tr("gui.dmz_ragnarok.core.event.field_token_name"), def.content.tokens.displayName,
                v -> def.content.tokens.displayName = v);
        bf(tr("gui.dmz_ragnarok.core.event.field_board_on"), def.content.leaderboard.enabled,
                () -> def.content.leaderboard.enabled = !def.content.leaderboard.enabled);
        df(tr("gui.dmz_ragnarok.core.event.field_board_metric"), METRICS, def.content.leaderboard.metric,
                v -> def.content.leaderboard.metric = v == null ? "" : v.trim());
        tf(tr("gui.dmz_ragnarok.core.event.field_board_topn"), intStr(def.content.leaderboard.topN),
                v -> def.content.leaderboard.topN = Math.max(0, parseI(v, def.content.leaderboard.topN)));
        tf(tr("gui.dmz_ragnarok.core.event.field_board_rewards"), joinList(def.content.leaderboard.rewards),
                v -> replaceList(def.content.leaderboard.rewards, splitList(v)));
        tip(tr("gui.dmz_ragnarok.core.event.board_rewards_tip"));
        label("§8" + tr("gui.dmz_ragnarok.core.event.rewards_hint"), 14, rowY + 2);
    }

    // ---- Content: a second-level nav over the per-row sub-editors (E11b/c) --------------------------------------
    //
    // Every sub-section mutates the LOCAL def only (no per-row server round trip); the whole def still ships on
    // Save. Each section is the same shape: a few tf/df rows bound to a SHARED temp-field set (ct[]), an inline
    // Add/Update button (cEdit = -1 adds, >= 0 updates that row in place), then the row list with Edit/Delete.
    // Reward-grammar and other string lists are edited as ONE semicolon-joined field. The one exception is adding a
    // NEW event floor: its config is copied server side (DungeonEventFloorHook.copyFloorConfig), so "addfloor"
    // rides EditorScreens.act and the reply (packet 128, mode "floor") lands in acceptFloor.

    private void selectSub(int s)
    {
        applyFields();
        sub = s;
        lbBox = -1;
        resetContentInputs();
        rebuildWidgets();
    }

    private void resetContentInputs()
    {
        java.util.Arrays.fill(ct, "");
        cEdit = -1;
        cScroll = 0;
    }

    private String ct(int i)
    {
        return ct[i] == null ? "" : ct[i];
    }

    private void buildContent()
    {
        // A fixed single-row 6-cell nav: one tabs() call per cell so the row never wraps 5 + 1 on a narrow caption.
        String[] caps = trAll(SUB_KEYS);
        int x = 10, w = uiWidth - 20, gap = GuiTheme.UNIT;
        int cellW = (w - gap * (caps.length - 1)) / caps.length;
        int below = rowY;
        for (int i = 0; i < caps.length; i++)
        {
            final int idx = i;
            below = tabs(x + i * (cellW + gap), rowY, cellW, new String[] { caps[i] }, sub == i ? 0 : -1,
                    ignored -> selectSub(idx));
        }
        rowY = below + 4;

        switch (sub)
        {
            case 0 -> buildRegionsSub();
            case 1 -> buildLootSub();
            case 2 -> buildQuestsSub();
            case 3 -> buildShopSub();
            case 4 -> buildHologramsSub();
            case 5 -> buildFloorsSub();
            default -> buildLootBoxesSub();
        }
    }

    // ---- Regions: eventOnly regions/raids/rifts pickers + region mob additions -------------------------------------

    private void buildRegionsSub()
    {
        dfMulti(tr("gui.dmz_ragnarok.core.event.field_regions"), pickRegions, def.content.eventRegions,
                list -> replaceList(def.content.eventRegions, list));
        tip(tr("gui.dmz_ragnarok.core.event.content_hint"));
        dfMulti(tr("gui.dmz_ragnarok.core.event.field_raids"), pickRaids, def.content.eventRaids,
                list -> replaceList(def.content.eventRaids, list));
        tip(tr("gui.dmz_ragnarok.core.event.content_hint"));
        dfMulti(tr("gui.dmz_ragnarok.core.event.field_rifts"), pickRifts, def.content.eventRifts,
                list -> replaceList(def.content.eventRifts, list));
        tip(tr("gui.dmz_ragnarok.core.event.content_hint"));

        // ct: 0 template region, 1 targets (;), 2 dims (;)
        df(tr("gui.dmz_ragnarok.core.event.field_mob_template"), pickRegions, ct(0), v -> ct[0] = v);
        tip(tr("gui.dmz_ragnarok.core.event.mob_template_tip"));
        // Targets and dims stay semicolon-joined in ct[] (the stored format), but are picked with multi-selects: the
        // dropdown reads the split list and writes it back joined. A "*" (every region) option and any stored value
        // no longer in the list (a deleted region, an unloaded dim) stay selectable through dfMulti's own preserving.
        dfMulti(tr("gui.dmz_ragnarok.core.event.field_mob_targets"), targetOptions, splitList(ct(1)),
                list -> ct[1] = joinList(list));
        tip(tr("gui.dmz_ragnarok.core.event.mob_targets_tip"));
        dfMulti(tr("gui.dmz_ragnarok.core.event.field_mob_dims"), pickDims, splitList(ct(2)),
                list -> ct[2] = joinList(list));
        tip(tr("gui.dmz_ragnarok.core.event.mob_dims_tip"));

        List<EventDef.RegionMobAdd> rows = def.content.regionMobs;
        addBlock(rows.size(), () -> {
            if (ct(0).isBlank())
                return false;
            EventDef.RegionMobAdd r = new EventDef.RegionMobAdd();
            r.templateRegion = ct(0).trim();
            List<String> targets = splitList(ct(1));
            r.targets.addAll(targets.isEmpty() ? List.of("*") : targets);
            r.dims.addAll(splitList(ct(2)));
            put(rows, r);
            return true;
        }, null);
        rowList(rows.size(), i -> {
            EventDef.RegionMobAdd r = rows.get(i);
            return "§f" + r.templateRegion + " §7-> " + String.join(",", r.targets)
                    + (r.dims.isEmpty() ? "" : " §8" + String.join(",", r.dims));
        }, i -> {
            EventDef.RegionMobAdd r = rows.get(i);
            ct[0] = r.templateRegion;
            ct[1] = joinList(r.targets);
            ct[2] = joinList(r.dims);
        }, rows::remove);
    }

    // ---- Loot: host adds ---------------------------------------------------------------------------------------

    private void buildLootSub()
    {
        // ct: 0 host, 1 host id, 2 filter, 3 drops (;)
        df(tr("gui.dmz_ragnarok.core.event.field_loot_host"), LOOT_HOSTS, ct(0).isBlank() ? "MOB_ANY" : ct(0),
                v -> ct[0] = v);
        tip(tr("gui.dmz_ragnarok.core.event.loot_host_tip"));
        tf(tr("gui.dmz_ragnarok.core.event.field_loot_host_id"), ct(1), v -> ct[1] = v);
        tip(tr("gui.dmz_ragnarok.core.event.loot_host_id_tip"));
        tf(tr("gui.dmz_ragnarok.core.event.field_loot_filter"), ct(2), v -> ct[2] = v);
        tip(tr("gui.dmz_ragnarok.core.event.loot_filter_tip"));
        tf(tr("gui.dmz_ragnarok.core.event.field_loot_drops"), ct(3), v -> ct[3] = v);
        tip(tr("gui.dmz_ragnarok.core.event.loot_drops_tip"));

        List<EventDef.LootAdd> rows = def.content.loot;
        addBlock(rows.size(), () -> {
            List<String> drops = splitList(ct(3));
            if (drops.isEmpty())
                return false;
            EventDef.LootAdd l = new EventDef.LootAdd();
            l.host = lootHost(ct(0));
            l.hostId = ct(1).isBlank() ? "*" : ct(1).trim();
            l.filter = ct(2).trim();
            l.tokens.addAll(drops);
            put(rows, l);
            return true;
        }, null);
        rowList(rows.size(), i -> {
            EventDef.LootAdd l = rows.get(i);
            return "§f" + l.host.name() + " §7" + l.hostId + (l.filter.isBlank() ? "" : " [" + l.filter + "]")
                    + " §8" + tr("gui.dmz_ragnarok.core.event.loot_count", l.tokens.size());
        }, i -> {
            EventDef.LootAdd l = rows.get(i);
            ct[0] = l.host.name();
            ct[1] = l.hostId;
            ct[2] = l.filter;
            ct[3] = joinList(l.tokens);
        }, rows::remove);
    }

    private static EventDef.LootHost lootHost(String s)
    {
        try
        {
            return EventDef.LootHost.valueOf(s == null ? "" : s.trim());
        }
        catch (IllegalArgumentException e)
        {
            return EventDef.LootHost.MOB_ANY;
        }
    }

    // ---- Quests ------------------------------------------------------------------------------------------------

    private void buildQuestsSub()
    {
        // ct: 0 id, 1 title, 2 metric, 3 filter, 4 threshold, 5 rewards (;)
        tf(tr("gui.dmz_ragnarok.core.event.field_quest_id"), ct(0), v -> ct[0] = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_quest_title"), ct(1), v -> ct[1] = v);
        df(tr("gui.dmz_ragnarok.core.event.field_quest_metric"), METRICS, ct(2), v -> ct[2] = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_quest_filter"), ct(3), v -> ct[3] = v);
        tip(tr("gui.dmz_ragnarok.core.event.quest_filter_tip"));
        tf(tr("gui.dmz_ragnarok.core.event.field_quest_threshold"), ct(4).isBlank() ? "1" : ct(4), v -> ct[4] = v);
        tf(tr("gui.dmz_ragnarok.core.event.field_quest_rewards"), ct(5), v -> ct[5] = v);
        tip(tr("gui.dmz_ragnarok.core.event.grammar_tip"));

        List<EventDef.EventQuest> rows = def.content.quests;
        addBlock(rows.size(), () -> {
            String id = ct(0).trim().toLowerCase(java.util.Locale.ROOT);
            if (id.isBlank() || ct(2).isBlank())
                return false;
            // Quest ids key the exactly-once claim set: refuse a duplicate id on a different row.
            for (int i = 0; i < rows.size(); i++)
                if (i != cEdit && id.equals(rows.get(i).id))
                    return false;
            EventDef.EventQuest q = new EventDef.EventQuest();
            q.id = id;
            q.title = ct(1);
            q.metric = ct(2).trim();
            q.filter = ct(3).trim();
            q.threshold = Math.max(1, parseI(ct(4), 1));
            q.rewardTokens.addAll(splitList(ct(5)));
            put(rows, q);
            return true;
        }, null);
        rowList(rows.size(), i -> {
            EventDef.EventQuest q = rows.get(i);
            String title = q.title == null || q.title.isBlank() ? q.id : q.title.replace('&', '§');
            return "§f" + title + " §7" + q.metric + " x" + q.threshold;
        }, i -> {
            EventDef.EventQuest q = rows.get(i);
            ct[0] = q.id;
            ct[1] = q.title;
            ct[2] = q.metric;
            ct[3] = q.filter;
            ct[4] = intStr(q.threshold);
            ct[5] = joinList(q.rewardTokens);
        }, rows::remove);
    }

    // ---- Shop --------------------------------------------------------------------------------------------------

    private void buildShopSub()
    {
        // ct: 0 give, 1 cost
        tf(tr("gui.dmz_ragnarok.core.event.field_shop_give"), ct(0), v -> ct[0] = v);
        tip(tr("gui.dmz_ragnarok.core.event.shop_give_tip"));
        tf(tr("gui.dmz_ragnarok.core.event.field_shop_cost"), ct(1).isBlank() ? "1" : ct(1), v -> ct[1] = v);

        List<EventDef.ShopOffer> rows = def.content.shop;
        addBlock(rows.size(), () -> {
            if (ct(0).isBlank())
                return false;
            EventDef.ShopOffer o = new EventDef.ShopOffer();
            o.give = ct(0).trim();
            o.cost = Math.max(1, parseI(ct(1), 1));
            put(rows, o);
            return true;
        }, null);
        rowList(rows.size(), i -> {
            EventDef.ShopOffer o = rows.get(i);
            return "§f" + o.give + " §7" + tr("gui.dmz_ragnarok.core.event.shop_for", o.cost);
        }, i -> {
            EventDef.ShopOffer o = rows.get(i);
            ct[0] = o.give;
            ct[1] = intStr(o.cost);
        }, rows::remove);
    }

    // ---- Holograms ---------------------------------------------------------------------------------------------

    private void buildHologramsSub()
    {
        // ct: 0 dim, 1 x, 2 y, 3 z, 4 text. A blank dim commits as minecraft:overworld (see the add block below); a
        // stored dim no longer in the list stays selectable through df's own off-list preserving.
        df(tr("gui.dmz_ragnarok.core.event.field_holo_dim"), pickDims, ct(0), v -> ct[0] = v);
        label(tr("gui.dmz_ragnarok.core.event.field_holo_pos"), 14, rowY + 2);
        int fx = fieldColX(), third = (fieldColW() - 2 * GuiTheme.UNIT) / 3;
        rawField(fx, rowY + 1, third, ct(1), v -> ct[1] = v).setHint(Component.literal("x"));
        rawField(fx + third + GuiTheme.UNIT, rowY + 1, third, ct(2), v -> ct[2] = v).setHint(Component.literal("y"));
        rawField(fx + 2 * (third + GuiTheme.UNIT), rowY + 1, third, ct(3), v -> ct[3] = v)
                .setHint(Component.literal("z"));
        rowY += ROW_H;
        tf(tr("gui.dmz_ragnarok.core.event.field_holo_text"), ct(4), v -> ct[4] = v);
        tip(tr("gui.dmz_ragnarok.core.event.holo_text_tip"));

        List<EventDef.EventHologram> rows = def.content.holograms;
        addBlock(rows.size(), () -> {
            if (ct(4).isBlank())
                return false;
            EventDef.EventHologram h = new EventDef.EventHologram();
            h.dim = ct(0).isBlank() ? "minecraft:overworld" : ct(0).trim();
            h.x = parseD(ct(1), 0.0);
            h.y = parseD(ct(2), 0.0);
            h.z = parseD(ct(3), 0.0);
            h.text = ct(4);
            put(rows, h);
            return true;
        }, this::fillHereIntoHologram);
        rowList(rows.size(), i -> {
            EventDef.EventHologram h = rows.get(i);
            return "§f" + h.text.replace('&', '§') + " §7" + dbl(h.x) + " " + dbl(h.y) + " " + dbl(h.z);
        }, i -> {
            EventDef.EventHologram h = rows.get(i);
            ct[0] = h.dim;
            ct[1] = dbl(h.x);
            ct[2] = dbl(h.y);
            ct[3] = dbl(h.z);
            ct[4] = h.text;
        }, rows::remove);
    }

    /** "Here": fill the hologram dim and block-centred position from the admin's own client position. */
    private void fillHereIntoHologram()
    {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null)
            return;
        applyFields();
        ct[0] = p.level().dimension().location().toString();
        ct[1] = dbl(Math.floor(p.getX()) + 0.5);
        ct[2] = dbl(Math.floor(p.getY()));
        ct[3] = dbl(Math.floor(p.getZ()) + 0.5);
        rebuildWidgets();
    }

    // ---- Floors: label edit + delete locally, ADD copies a floor config server side ----------------------------

    private void buildFloorsSub()
    {
        List<EventDef.EventFloor> rows = def.content.eventFloors;
        // ct: 0 source floor number (add mode only), 1 label
        if (cEdit < 0)
        {
            dfNamed(tr("gui.dmz_ragnarok.core.event.field_floor_source"), floorValues, floorLabels, ct(0),
                    v -> ct[0] = v, tr("gui.dmz_ragnarok.core.event.floor_none"));
            tip(tr("gui.dmz_ragnarok.core.event.floor_source_tip"));
        }
        tf(tr("gui.dmz_ragnarok.core.event.field_floor_label"), ct(1), v -> ct[1] = v);

        int bY = rowY + GuiTheme.UNIT;
        if (cEdit >= 0)
        {
            commitBtn(14, bY, 76, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.event.btn_update"), () -> {
                        applyFields();
                        if (cEdit >= 0 && cEdit < rows.size() && !ct(1).isBlank())
                            rows.get(cEdit).label = ct(1);
                        resetContentInputs();
                        rebuildWidgets();
                    });
            cancelContentBtn(94, bY);
        }
        else
        {
            commitBtn(14, bY, 76, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.event.btn_add_floor"), () -> {
                        applyFields();
                        int n = parseI(ct(0), 0);
                        if (n <= 0)
                            return;
                        pendingFloorLabel = ct(1).trim();
                        // Server copies the floor config and answers with packet 128 mode "floor" -> acceptFloor.
                        EditorScreens.act("events", "addfloor", Integer.toString(n));
                    });
            tooltip(14, bY, 76, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.core.event.add_floor_tip"));
        }
        countLabel(rows.size(), bY);
        rowY = bY + GuiTheme.BUTTON_HEIGHT + 4;

        rowList(rows.size(), i -> {
            EventDef.EventFloor f = rows.get(i);
            String theme = f.floorCfg.getString("Theme");
            String type = f.floorCfg.getString("Type");
            return "§f" + f.label.replace('&', '§') + " §7" + theme + (type.isBlank() ? "" : " " + type);
        }, i -> ct[1] = rows.get(i).label, rows::remove);
    }

    /**
     * The server's answer to "addfloor": append a new event floor built from the copied config to the LOCAL def,
     * keeping every other unsaved edit. The admin's typed label wins; the server's label is the fallback.
     */
    public void acceptFloor(String serverLabel, CompoundTag floorCfg)
    {
        if (floorCfg == null || floorCfg.isEmpty())
            return;
        applyFields();
        EventDef.EventFloor f = new EventDef.EventFloor();
        String lab = pendingFloorLabel == null || pendingFloorLabel.isBlank() ? serverLabel : pendingFloorLabel;
        f.label = lab == null ? "" : lab;
        f.floorCfg = floorCfg.copy();
        def.content.eventFloors.add(f);
        pendingFloorLabel = "";
        section = SECTION_CONTENT;
        sub = SUB_FLOORS;
        resetContentInputs();
        rebuildWidgets();
    }

    // ---- Loot Boxes: a two-level editor (box list, then one box's weighted reward bundles) ----------------------
    //
    // The Halloween Box and the Pumpkin Bag (and any others an admin adds) are LootBox entries on the local def. The
    // box list adds/opens/removes boxes; opening one edits its display name, announcement toggle + template (with a
    // live preview), and its weighted reward list. Every reward row is a weight, an optional rarity and a set of
    // reward-grammar tokens (the same grammar the rest of the event loot uses, plus token:<variant> and cosmetic:).
    // Helper buttons append a token from the held item, a chosen token variant + count, or a catalogue cosmetic. All
    // edits are local; the whole def ships on Save (packet 129) and the key validates before persisting.

    private void buildLootBoxesSub()
    {
        List<EventDef.LootBox> boxes = def.content.lootBoxes;
        if (lbBox >= 0 && lbBox < boxes.size())
            buildOneLootBox(boxes.get(lbBox));
        else
        {
            lbBox = -1;
            buildLootBoxList(boxes);
        }
    }

    private void buildLootBoxList(List<EventDef.LootBox> boxes)
    {
        // ct: 0 new box type
        tf(tr("gui.dmz_ragnarok.core.event.field_lb_type"), ct(0), v -> ct[0] = v);
        tip(tr("gui.dmz_ragnarok.core.event.lb_type_tip"));
        addBlock(boxes.size(), () -> {
            String bt = sanitizeType(ct(0));
            if (bt.isBlank())
                return false;
            for (EventDef.LootBox b : boxes)
                if (bt.equals(b.boxType))
                    return false;
            EventDef.LootBox box = new EventDef.LootBox();
            box.boxType = bt;
            box.displayName = "&6" + bt.replace('_', ' ');
            boxes.add(box);
            return true;
        }, null);

        int rh = 13;
        int listTop = rowY;
        int cap = rowsThatFit(listTop, rh);
        cScroll = Math.max(0, Math.min(cScroll, Math.max(0, boxes.size() - cap)));
        int end = Math.min(boxes.size(), cScroll + cap);
        for (int i = cScroll; i < end; i++)
        {
            final int index = i;
            int ry = listTop + (i - cScroll) * rh;
            EventDef.LootBox b = boxes.get(i);
            String name = b.displayName == null || b.displayName.isBlank() ? b.boxType : b.displayName.replace('&', '§');
            label("§f" + name + " §7" + b.boxType + " §8"
                    + tr("gui.dmz_ragnarok.core.event.lb_reward_count", b.rewards.size()), 14, ry + 3, 0xFFFFFFFF);
            int delX = rowControlRight() - 42;
            int openX = delX - 2 - 34;
            btn(openX, ry, 34, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.event.btn_open"),
                    () -> { applyFields(); resetContentInputs(); lbBox = index; lbRewards = false; rebuildWidgets(); });
            btn(delX, ry, 42, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> { applyFields(); boxes.remove(index); rebuildWidgets(); });
        }
        scrollList(14, uiWidth, listTop, rh, cap, boxes.size(), cScroll,
                v -> { applyFields(); cScroll = v; rebuildWidgets(); });
    }

    private void buildOneLootBox(EventDef.LootBox box)
    {
        // Back to the box list, plus a two-cell Settings / Rewards nav so each view stays short enough to fit above
        // the footer with room for the reward list.
        btn(14, rowY, 70, GuiTheme.BUTTON_HEIGHT,
                Component.translatable("gui.dmz_ragnarok.core.event.btn_lb_back"),
                () -> { applyFields(); resetContentInputs(); lbBox = -1; rebuildWidgets(); });
        String[] caps = { tr("gui.dmz_ragnarok.core.event.lb_tab_settings"),
                tr("gui.dmz_ragnarok.core.event.lb_tab_rewards") };
        int navX = 92;
        int navW = uiWidth - navX - 14;
        int cellW = (navW - GuiTheme.UNIT) / 2;
        int below = rowY;
        below = tabs(navX, rowY, cellW, new String[] { caps[0] }, lbRewards ? -1 : 0,
                ignored -> { applyFields(); lbRewards = false; java.util.Arrays.fill(ct, ""); cEdit = -1; rebuildWidgets(); });
        below = tabs(navX + cellW + GuiTheme.UNIT, rowY, cellW, new String[] { caps[1] }, lbRewards ? 0 : -1,
                ignored -> { applyFields(); lbRewards = true; java.util.Arrays.fill(ct, ""); cEdit = -1; rebuildWidgets(); });
        rowY = below + 4;

        if (lbRewards)
            buildBoxRewards(box);
        else
            buildBoxSettings(box);
    }

    /** The box's own settings: display name, the announcement toggle + template, and a live preview line. */
    private void buildBoxSettings(EventDef.LootBox box)
    {
        label("§7" + tr("gui.dmz_ragnarok.core.event.field_lb_type") + " §f" + box.boxType, 14, rowY + 2);
        rowY += ROW_H;
        tf(tr("gui.dmz_ragnarok.core.event.field_lb_name"), box.displayName, v -> box.displayName = v);
        bf(tr("gui.dmz_ragnarok.core.event.field_lb_announce"), box.announce, () -> box.announce = !box.announce);
        tf(tr("gui.dmz_ragnarok.core.event.field_lb_template"), box.announceTemplate,
                v -> box.announceTemplate = v == null ? "" : v);
        tip(tr("gui.dmz_ragnarok.core.event.lb_template_tip"));
        label("§7" + tr("gui.dmz_ragnarok.core.event.lb_preview") + " §r" + previewAnnounce(box), 14, rowY + 2);
        rowY += ROW_H;
    }

    /** The box's weighted reward bundles: the add/edit form (with token helpers) then the reward list with reorder. */
    private void buildBoxRewards(EventDef.LootBox box)
    {
        // ct: 0 weight, 1 rarity, 2 tokens, 3 variant pick, 4 token count, 5 cosmetic pick. Weight + rarity share a
        // row, and the token variant + cosmetic pickers share a row, to keep the form short.
        label(tr("gui.dmz_ragnarok.core.event.field_lb_weight") + " / "
                + tr("gui.dmz_ragnarok.core.event.field_lb_rarity"), 14, rowY + 2);
        int fx = fieldColX();
        int half = (fieldColW() - GuiTheme.UNIT) / 2;
        rawField(fx, rowY + 1, half, ct(0).isBlank() ? "1" : ct(0), v -> ct[0] = v)
                .setHint(Component.literal("weight"));
        rawField(fx + half + GuiTheme.UNIT, rowY + 1, half, ct(1), v -> ct[1] = v)
                .setHint(Component.literal("rarity"));
        rowY += ROW_H;

        tf(tr("gui.dmz_ragnarok.core.event.field_lb_tokens"), ct(2), v -> ct[2] = v);

        label(tr("gui.dmz_ragnarok.core.event.field_lb_varpick") + " / "
                + tr("gui.dmz_ragnarok.core.event.field_lb_cospick"), 14, rowY + 5);
        dfAt(fx, rowY, half, TOKEN_VARIANTS, ct(3).isBlank() ? "candy_corn" : ct(3), v -> ct[3] = v);
        dfNamedAt(fx + half + GuiTheme.UNIT, rowY, half, pickCosmeticIds, pickCosmeticNames, ct(5), v -> ct[5] = v);
        rowY += ROW_H;

        // Helper buttons that append one token to the tokens field, plus a small token-count field for + Token.
        int hY = rowY + GuiTheme.UNIT;
        btn(14, hY, 46, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.event.btn_lb_hand"),
                this::appendHandToken);
        btn(62, hY, 52, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.event.btn_lb_token"),
                () -> { applyFields(); String var = sanitizeType(ct(3)); if (!var.isBlank())
                        appendToken("token:" + var + ":" + Math.max(1, parseI(ct(4), 1))); });
        rawField(116, hY, 22, ct(4).isBlank() ? "1" : ct(4), v -> ct[4] = v).setHint(Component.literal("x"));
        btn(140, hY, 74, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.event.btn_lb_cos"),
                () -> { applyFields(); String id = ct(5).trim(); if (!id.isBlank()) appendToken("cosmetic:" + id); });
        rowY = hY + GuiTheme.BUTTON_HEIGHT + 4;

        List<EventDef.LootBox.Reward> rows = box.rewards;
        addBlock(rows.size(), () -> {
            List<String> toks = splitList(ct(2));
            if (toks.isEmpty())
                return false;
            EventDef.LootBox.Reward r = new EventDef.LootBox.Reward();
            r.weight = Math.max(1, parseI(ct(0), 1));
            r.rarity = ct(1).trim();
            r.tokens.addAll(toks);
            put(rows, r);
            return true;
        }, null);
        rewardRowList(rows);
    }

    /** Append one grammar token to the reward tokens field (ct[2]), preserving what is already typed. */
    private void appendToken(String token)
    {
        applyFields();
        String cur = ct(2);
        ct[2] = cur.isBlank() ? token : cur + "; " + token;
        rebuildWidgets();
    }

    /** "From hand": append an item token for the admin's currently held main-hand item. */
    private void appendHandToken()
    {
        net.minecraft.client.player.LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p == null)
            return;
        net.minecraft.world.item.ItemStack held = p.getMainHandItem();
        if (held.isEmpty())
            return;
        net.minecraft.resources.ResourceLocation id =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(held.getItem());
        appendToken("item:" + id + ":" + held.getCount());
    }

    /** The reward list for one box: a line per bundle with Up/Down reorder plus Edit and Delete. */
    private void rewardRowList(List<EventDef.LootBox.Reward> rows)
    {
        int rh = 13;
        int listTop = rowY;
        int cap = rowsThatFit(listTop, rh);
        cScroll = Math.max(0, Math.min(cScroll, Math.max(0, rows.size() - cap)));
        int end = Math.min(rows.size(), cScroll + cap);
        for (int i = cScroll; i < end; i++)
        {
            final int index = i;
            int ry = listTop + (i - cScroll) * rh;
            EventDef.LootBox.Reward r = rows.get(i);
            String rarity = r.rarity == null || r.rarity.isBlank() ? "" : " §8[" + r.rarity + "]";
            label((index == cEdit ? "§e> " : "") + "§7w" + r.weight + rarity + " §f" + joinList(r.tokens),
                    14, ry + 3, 0xFFFFFFFF);
            int delX = rowControlRight() - 42;
            int editX = delX - 2 - 30;
            int downX = editX - 2 - 14;
            int upX = downX - 2 - 14;
            btn(upX, ry, 14, GuiTheme.ROW_HEIGHT, Component.literal("↑"), () -> moveReward(rows, index, -1));
            btn(downX, ry, 14, GuiTheme.ROW_HEIGHT, Component.literal("↓"), () -> moveReward(rows, index, 1));
            btn(editX, ry, 30, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.edit"), () -> {
                applyFields();
                java.util.Arrays.fill(ct, "");
                ct[0] = intStr(r.weight);
                ct[1] = r.rarity;
                ct[2] = joinList(r.tokens);
                cEdit = index;
                rebuildWidgets();
            });
            btn(delX, ry, 42, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"), () -> {
                applyFields();
                rows.remove(index);
                if (cEdit == index)
                {
                    java.util.Arrays.fill(ct, "");
                    cEdit = -1;
                }
                else if (cEdit > index)
                {
                    cEdit--;
                }
                rebuildWidgets();
            });
        }
        scrollList(14, uiWidth, listTop, rh, cap, rows.size(), cScroll,
                v -> { applyFields(); cScroll = v; rebuildWidgets(); });
    }

    private void moveReward(List<EventDef.LootBox.Reward> rows, int index, int delta)
    {
        applyFields();
        int to = index + delta;
        if (to < 0 || to >= rows.size())
            return;
        java.util.Collections.swap(rows, index, to);
        if (cEdit == index)
            cEdit = to;
        else if (cEdit == to)
            cEdit = index;
        rebuildWidgets();
    }

    /** Render the box's announcement template with sample values, so the admin sees the coloured line it produces. */
    private String previewAnnounce(EventDef.LootBox box)
    {
        if (!box.announce)
            return "§8(" + tr("gui.dmz_ragnarok.core.event.field_lb_announce") + ": off)";
        String tpl = box.announceTemplate == null ? "" : box.announceTemplate;
        String name = box.displayName == null || box.displayName.isBlank() ? box.boxType : box.displayName;
        return tpl.replace("{player}", "Steve").replace("{box}", name).replace("{item}", "3x Candy")
                .replace('&', '§');
    }

    /** Sanitise a box type / token variant to lowercase letters, digits and underscore (never a colon or space). */
    private static String sanitizeType(String s)
    {
        if (s == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.trim().toLowerCase(java.util.Locale.ROOT).toCharArray())
        {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_')
                sb.append(c);
            else if (c == ' ' || c == '-')
                sb.append('_');
        }
        return sb.toString();
    }

    // ---- shared add block + row list ----------------------------------------------------------------------------

    /**
     * The inline Add/Update button row under a section's temp fields. {@code commit} reads ct[] into a row (via
     * {@link #put}) and returns false to refuse (missing required value), leaving the inputs as typed. An optional
     * {@code here} action adds a "Here" button (holograms).
     */
    private void addBlock(int count, java.util.function.BooleanSupplier commit, Runnable here)
    {
        int bY = rowY + GuiTheme.UNIT;
        commitBtn(14, bY, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable(cEdit >= 0
                ? "gui.dmz_ragnarok.core.event.btn_update" : "gui.dmz_ragnarok.core.event.btn_add"), () -> {
                    applyFields();
                    if (commit.getAsBoolean())
                    {
                        resetContentInputs();
                        rebuildWidgets();
                    }
                });
        int nx = 94;
        if (cEdit >= 0)
        {
            cancelContentBtn(nx, bY);
            nx += 60;
        }
        if (here != null)
        {
            btn(nx, bY, 56, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.event.btn_here"),
                    here);
            tooltip(nx, bY, 56, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.core.event.here_tip"));
        }
        countLabel(count, bY);
        rowY = bY + GuiTheme.BUTTON_HEIGHT + 4;
    }

    private void countLabel(int count, int bY)
    {
        String s = "§7" + tr("gui.dmz_ragnarok.core.event.row_count", count);
        int w = this.font == null ? 40 : this.font.width(s);
        label(s, rowControlRight() - w, bY + 3);
    }

    private void cancelContentBtn(int x, int bY)
    {
        btn(x, bY, 56, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.cancel"), () -> {
            applyFields();
            resetContentInputs();
            rebuildWidgets();
        });
    }

    /** Append a new row, or replace the row being edited (cEdit), in the section's list. */
    private <E> void put(List<E> rows, E e)
    {
        if (cEdit >= 0 && cEdit < rows.size())
            rows.set(cEdit, e);
        else
            rows.add(e);
    }

    /**
     * The scrolling row list filling the rest of the panel: a text line per row plus Edit (loads the row into the
     * temp fields, switching the block to Update) and Delete (drops it from the local def).
     */
    private void rowList(int count, java.util.function.IntFunction<String> text,
            java.util.function.IntConsumer load, java.util.function.IntConsumer remove)
    {
        int rh = 13;
        int listTop = rowY;
        int cap = rowsThatFit(listTop, rh);
        cScroll = Math.max(0, Math.min(cScroll, Math.max(0, count - cap)));
        int end = Math.min(count, cScroll + cap);
        for (int i = cScroll; i < end; i++)
        {
            final int index = i;
            int ry = listTop + (i - cScroll) * rh;
            // Delete is wider than Edit: its caption needs it to clear the button's end caps.
            int delX = rowControlRight() - 42;
            int editX = delX - 2 - 34;
            label((index == cEdit ? "§e> " : "") + text.apply(index), 14, ry + 3, 0xFFFFFFFF);
            btn(editX, ry, 34, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.edit"), () -> {
                applyFields();
                java.util.Arrays.fill(ct, "");
                load.accept(index);
                cEdit = index;
                rebuildWidgets();
            });
            btn(delX, ry, 42, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"), () -> {
                applyFields();
                remove.accept(index);
                if (cEdit == index)
                {
                    java.util.Arrays.fill(ct, "");
                    cEdit = -1;
                }
                else if (cEdit > index)
                {
                    cEdit--;
                }
                rebuildWidgets();
            });
        }
        scrollList(14, uiWidth, listTop, rh, cap, count, cScroll, v -> { applyFields(); cScroll = v; rebuildWidgets(); });
    }

    /** One semicolon-joined field for a string list (reward grammar, targets, dims). */
    private static String joinList(List<String> values)
    {
        return String.join("; ", values);
    }

    private static List<String> splitList(String s)
    {
        List<String> out = new ArrayList<>();
        if (s == null)
            return out;
        for (String part : s.split(";"))
        {
            String t = part.trim();
            if (!t.isEmpty())
                out.add(t);
        }
        return out;
    }

    /** Flush fields, freeze the authored window into absolute instants, and ship the whole def to the server. */
    private void save()
    {
        applyFields();
        def.schedule.startEpochMillis = tryResolve(def.schedule.startLocal, def.schedule.zone, def.schedule.startEpochMillis);
        def.schedule.endEpochMillis = tryResolve(def.schedule.endLocal, def.schedule.zone, def.schedule.endEpochMillis);
        NetworkUtils.sendToServer(new PacketEventEditorSave(def.toNbt()));
        // The server validates and, on success, re-sends this event (refreshing the screen with the persisted rev);
        // a rejection arrives as a chat line and the local edits are kept so the admin can fix and re-save.
    }
}
