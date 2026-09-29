package net.shurui.dev.shuruis_dmz_tournaments.client.gui;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.shurui.dev.shuruis_dmz_tournaments.client.GameRegistries;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.shuruis_dmz_tournaments.network.SaveDefPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.SetBoundsPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.region.Region;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentFormat;

/**
 * The tabbed tournament editor. Options are grouped into tabs (General, Bounds, Schedule, Rules,
 * Messages, Rewards) so a single screen isn't overwhelming. Built on the ported sdu {@link FieldEditScreen}
 * toolkit: labelled fields, toggles, searchable dropdowns and hover tooltips.
 */
public class TournamentEditScreen extends FieldEditScreen {
    private static final int UI_W = GuiTheme.SCREEN_W;
    // The tall tabs (Rules runs to ~18 rows) exceed this canvas, so the tab body is wrapped in a scroll
    // band (see init) and overflow scrolls behind the pinned footer rather than under it.
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] TAB_KEYS = {
            "gui.dmz_ragnarok.tournaments.edit.tab_general",
            "gui.dmz_ragnarok.tournaments.edit.tab_bounds",
            "gui.dmz_ragnarok.tournaments.edit.tab_schedule",
            "gui.dmz_ragnarok.tournaments.edit.tab_rules",
            "gui.dmz_ragnarok.tournaments.edit.tab_messages",
            "gui.dmz_ragnarok.tournaments.edit.tab_rewards"};

    private final TournamentDef def;
    private int tab = 0;
    // Content scroll offset, PER TAB: reset to 0 on a tab change so a switch never lands scrolled into
    // empty space. A tall tab (Rules, 18 rows, taller than the panel) then scrolls behind the band's scrollbar.
    private int tabScroll = 0;

    public TournamentEditScreen(TournamentDef def, Screen parent) {
        super(Component.translatable("gui.dmz_ragnarok.tournaments.edit.title"), UI_W, UI_H, parent);
        this.def = def;
    }

    private static String[] tabLabels() {
        String[] out = new String[TAB_KEYS.length];
        for (int i = 0; i < TAB_KEYS.length; i++) out[i] = I18n.get(TAB_KEYS[i]);
        return out;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();

        // Named entity: show the tournament name top-left (buildTabHeader lays the tab bar below the logo,
        // so the name no longer collides with the tabs).
        topLeftName = def.name;
        rowY = buildTabHeader(tabLabels(), tab, this::selectSection);
        // Wrap the tab body in a scroll band from the first content row to the reserved content bottom, so a
        // tall tab scrolls instead of spilling under the footer. Footer buttons are added AFTER endScrollBand,
        // so they stay pinned.
        beginScrollBand(rowY, net.shurui.dev.sdu.client.gui.theme.GuiTheme.contentBottom(UI_H),
                tabScroll, v -> { tabScroll = v; rebuildWidgets(); });
        switch (tab) {
            case 0 -> generalTab();
            case 1 -> boundsTab();
            case 2 -> scheduleTab();
            case 3 -> rulesTab();
            case 4 -> messagesTab();
            case 5 -> rewardsTab();
            default -> { }
        }
        endScrollBand();

        int by = GuiTheme.footerY(UI_H);
        btn(UI_W / 2 - 104, by, 100, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.common.save"), this::save);
        btn(UI_W / 2 + 4, by, 100, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.common.back"), this::back);
    }

    /** Apply pending edits and switch to the given tab (invoked by the {@code buildTabHeader} tab bar). */
    private void selectSection(int index) {
        if (tab != index) {
            applyFields();
            tab = index;
            tabScroll = 0; // a fresh tab always starts at the top, never at the previous tab's scroll offset
            rebuildWidgets();
        }
    }

    private void generalTab() {
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.display_name"), def.name, v -> def.name = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.display_name_tip"));
        df(I18n.get("gui.dmz_ragnarok.tournaments.edit.format"), java.util.List.of("1v1", "2v2", "3v3", "FFA"), def.format().label(),
                v -> def.format = TournamentFormat.fromId(v).name());
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.format_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.group"), def.group, v -> def.group = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.group_tip"));
        // The ragnarok characters are IN this list; see RgNpcPicker for why they cannot come from the registry.
        df(I18n.get("gui.dmz_ragnarok.tournaments.edit.host_entity"),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.options(GameRegistries.entityIds()),
                net.shurui.shuruisutilities.ragnarok.RgNpcPicker.value(def.npcEntityType, def.npcModelId),
                v -> {
                    if (v.isBlank()) return;
                    def.npcEntityType = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.entityOf(v);
                    def.npcModelId = net.shurui.shuruisutilities.ragnarok.RgNpcPicker.modelOf(v);
                });
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.host_entity_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.npc_name"), def.npcName, v -> def.npcName = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.npc_name_tip"));
    }

    private void boundsTab() {
        boundsRow(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_arena"), def.arena, "arena", r -> def.arena = r);
        boundsRow(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_waiting"), def.waiting, "waiting", r -> def.waiting = r);
        boundsRow(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_stands"), def.stands, "stands", r -> def.stands = r);
    }

    private void scheduleTab() {
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.auto_schedule"), def.scheduleEnabled, () -> def.scheduleEnabled = !def.scheduleEnabled);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.auto_schedule_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.hour"), str(def.scheduleHour), v -> def.scheduleHour = parseI(v, def.scheduleHour));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.hour_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.minute"), str(def.scheduleMinute), v -> def.scheduleMinute = parseI(v, def.scheduleMinute));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.minute_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.day_of_week"), str(def.scheduleDayOfWeek), v -> def.scheduleDayOfWeek = parseI(v, def.scheduleDayOfWeek));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.day_of_week_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.day_of_month"), str(def.scheduleDayOfMonth), v -> def.scheduleDayOfMonth = parseI(v, def.scheduleDayOfMonth));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.day_of_month_tip"));
    }

    private void rulesTab() {
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.signup_minutes"), str(def.signupMinutes), v -> def.signupMinutes = parseI(v, def.signupMinutes));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.signup_minutes_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.min_participants"), str(def.minParticipants), v -> def.minParticipants = parseI(v, def.minParticipants));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.min_participants_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.max_participants"), str(def.maxParticipants), v -> def.maxParticipants = parseI(v, def.maxParticipants));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.max_participants_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.require_character"), def.requireCharacter, () -> def.requireCharacter = !def.requireCharacter);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.require_character_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.tournament_chars"), def.tournamentChars, () -> def.tournamentChars = !def.tournamentChars);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.tournament_chars_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.items_allowed"), def.itemsAllowed, () -> def.itemsAllowed = !def.itemsAllowed);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.items_allowed_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.heal_after_fight"), def.healAfterFight, () -> def.healAfterFight = !def.healAfterFight);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.heal_after_fight_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.force_friendly_fist"), def.forceFriendlyFist, () -> def.forceFriendlyFist = !def.forceFriendlyFist);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.force_friendly_fist_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.ring_out"), def.ringOut, () -> def.ringOut = !def.ringOut);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.ring_out_tip"));
        // Floor-out is independent of ring-out: toggle it on and set the Y below which a fighter is eliminated. The
        // field accepts negatives because 1.20.1 worlds go to -64, and parseI keeps the old value on bad input.
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.floor_out"), def.floorOut, () -> def.floorOut = !def.floorOut);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.floor_out_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.floor_out_y"), str(def.floorOutY), v -> def.floorOutY = parseI(v, def.floorOutY));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.floor_out_y_tip"));
        // Bar specific title holders from entering, so a crown changes hands per title rather than all-or-nothing.
        // Still a text field (an operator types the barring ids comma separated, blank bars nobody; TournamentDef trims
        // and drops blanks when parsing), but the client now DOES hold the server's title list via TitleListSyncPacket,
        // so a future multi-select picker is feasible. For now the live list is surfaced in the tooltip instead of the
        // old hardcoded trio, which went stale the moment a title was added.
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.exclude_title_ids"), def.excludeTitleIdsCsv(),
                def::setExcludeTitleIdsFromCsv);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.exclude_title_ids_tip") + " "
                + I18n.get("gui.dmz_ragnarok.tournaments.edit.exclude_title_ids_live",
                        String.join(", ", GameRegistries.titleIds())));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.flight_allowed"), def.flightAllowed,
                () -> def.flightAllowed = !def.flightAllowed);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.flight_allowed_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.ground_out"), def.groundOut,
                () -> def.groundOut = !def.groundOut);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.ground_out_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.random_fill"), def.allowRandomFill, () -> def.allowRandomFill = !def.allowRandomFill);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.random_fill_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.pvp_waiting"), def.pvpInWaiting, () -> def.pvpInWaiting = !def.pvpInWaiting);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.pvp_waiting_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.pvp_stands"), def.pvpInStands, () -> def.pvpInStands = !def.pvpInStands);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.pvp_stands_tip"));
        bf(I18n.get("gui.dmz_ragnarok.tournaments.edit.use_titles"), def.useTitles, () -> def.useTitles = !def.useTitles);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.use_titles_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.countdown"), str(def.countdownSeconds), v -> def.countdownSeconds = parseI(v, def.countdownSeconds));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.countdown_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.time_limit"), str(def.matchTimeLimit), v -> def.matchTimeLimit = parseI(v, def.matchTimeLimit));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.time_limit_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.reminder"), str(def.signupReminderSeconds), v -> def.signupReminderSeconds = parseI(v, def.signupReminderSeconds));
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.reminder_tip"));
    }

    private void messagesTab() {
        df(I18n.get("gui.dmz_ragnarok.tournaments.edit.announce_sound"), GameRegistries.soundIds(), def.announceSound, v -> def.announceSound = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.announce_sound_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_signup_open"), def.msgSignupOpen, v -> def.msgSignupOpen = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_signup_open_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_starting"), def.msgStarting, v -> def.msgStarting = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_starting_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_match"), def.msgMatch, v -> def.msgMatch = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_match_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_match_win"), def.msgMatchWin, v -> def.msgMatchWin = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_match_win_tip"));
        tf(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_champion"), def.msgChampion, v -> def.msgChampion = v);
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.msg_champion_tip"));
    }

    private void rewardsTab() {
        label(I18n.get("gui.dmz_ragnarok.tournaments.edit.rewards_header"), 14, rowY);
        rowY += 18;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.edit.rewards_button",
                        I18n.get("gui.dmz_ragnarok.tournaments.reward_list.winner"), def.winnerRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.tournaments.reward_list.winner", def.winnerRewards)));
        rowY += 20;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.edit.rewards_button",
                        I18n.get("gui.dmz_ragnarok.tournaments.reward_list.runner_up"), def.runnerUpRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.tournaments.reward_list.runner_up", def.runnerUpRewards)));
        rowY += 20;
        btn(20, rowY, 260, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.edit.rewards_button",
                        I18n.get("gui.dmz_ragnarok.tournaments.reward_list.participation"), def.participationRewards.size()),
                () -> this.minecraft.setScreen(new RewardListScreen(this, "gui.dmz_ragnarok.tournaments.reward_list.participation", def.participationRewards)));
    }

    private void boundsRow(String name, Region region, String key, java.util.function.Consumer<Region> setter) {
        String status = region == null
                ? I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_not_set")
                : I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_range", region.min().toShortString(), region.max().toShortString());
        label(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_row", name, status), 14, rowY + 2);
        btn(212, rowY - 1, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.edit.set_via_we"), () -> setBounds(key));
        rowY += ROW_H;

        // Manual coordinate entry for anyone not using WorldEdit: type both corners, press "Set manually".
        BlockPos mn = region == null ? null : region.min();
        BlockPos mx = region == null ? null : region.max();
        label(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_min"), 14, rowY + 2);
        EditBox minX = coordBox(42, mn == null ? "" : Integer.toString(mn.getX()), "x");
        EditBox minY = coordBox(90, mn == null ? "" : Integer.toString(mn.getY()), "y");
        EditBox minZ = coordBox(138, mn == null ? "" : Integer.toString(mn.getZ()), "z");
        rowY += ROW_H;
        label(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_max"), 14, rowY + 2);
        EditBox maxX = coordBox(42, mx == null ? "" : Integer.toString(mx.getX()), "x");
        EditBox maxY = coordBox(90, mx == null ? "" : Integer.toString(mx.getY()), "y");
        EditBox maxZ = coordBox(138, mx == null ? "" : Integer.toString(mx.getZ()), "z");
        // Region keeps its own dimension if it already exists, else use the editor's current dimension.
        ResourceKey<Level> dim = region != null ? region.dimension()
                : this.minecraft.player.level().dimension();
        btn(212, rowY - 1, 76, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.tournaments.edit.set_manually"),
                () -> setBoundsManual(setter, dim, minX, minY, minZ, maxX, maxY, maxZ));
        rowY += ROW_H;
        tip(I18n.get("gui.dmz_ragnarok.tournaments.edit.bounds_tip", name));
    }

    /** A compact numeric coordinate box at the current row, pre-filled and hinted (x/y/z). */
    private EditBox coordBox(int x, String value, String hint) {
        EditBox b = field(x, rowY + 1, 44, value);
        b.setHint(Component.literal("§8" + hint));
        return b;
    }

    /** Build a Region from the six manually-typed corner coordinates and save it, if all six parse. */
    private void setBoundsManual(java.util.function.Consumer<Region> setter, ResourceKey<Level> dim,
            EditBox mnx, EditBox mny, EditBox mnz, EditBox mxx, EditBox mxy, EditBox mxz) {
        applyFields();
        Integer a = coordVal(mnx), b = coordVal(mny), c = coordVal(mnz);
        Integer d = coordVal(mxx), e = coordVal(mxy), f = coordVal(mxz);
        if (a == null || b == null || c == null || d == null || e == null || f == null)
            return; // incomplete/invalid input - leave the current bounds untouched
        setter.accept(new Region(dim, new BlockPos(a, b, c), new BlockPos(d, e, f)));
        TournamentNet.sendToServer(new SaveDefPacket(def.save()));
        rebuildWidgets();
    }

    private static Integer coordVal(EditBox box) {
        try {
            return Integer.valueOf(box.getValue().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void setBounds(String key) {
        applyFields();
        TournamentNet.sendToServer(new SaveDefPacket(def.save()));
        TournamentNet.sendToServer(new SetBoundsPacket(def.id, key));
    }

    private void save() {
        applyFields();
        TournamentNet.sendToServer(new SaveDefPacket(def.save()));
        back();
    }

    /** Called from the network layer when the server captured a WorldEdit selection for this def. */
    public void onBoundsUpdated(String defId, CompoundTag defNbt) {
        if (!def.id.equalsIgnoreCase(defId)) return;
        TournamentDef updated = TournamentDef.load(defNbt);
        def.arena = updated.arena;
        def.waiting = updated.waiting;
        def.stands = updated.stands;
        rebuildWidgets();
    }

    private static String str(int v) {
        return Integer.toString(v);
    }
}
