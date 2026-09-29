package net.shurui.shuruisutilities.guilds.client;

import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.guilds.network.GuildView;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

// player-facing guild GUI on the shared SagaBaseScreen toolkit, DMZ-styled. tabbed panel (Info, Members,
// Relations, Flags, Permissions, Warps, Planet, Salvage), each section renders its data and offers buttons that dispatch the matching
// /guild ... command then re-request the screen to refresh. active tab kept across refreshes via lastTab.
public class GuildScreen extends SagaBaseScreen
{
    private static final String[] TAB_NAMES = { "Info", "Members", "Relations", "Flags", "Permissions", "Warps", "Planet", "Salvage" };
    private static final String[] ROLE_ORDER = { "RECRUIT", "MEMBER", "OFFICER", "LEADER" };

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    /** Remembered so a command-driven refresh reopens on the same tab. */
    private static int lastTab = 0;

    // The Y at which the historical section layouts start their content. Every section was written against this
    // literal; it is now a base the real body origin is offset from so a wrapped tab row can push content down.
    private static final int BODY_BASE_Y = 50;

    private final GuildView v;
    private int tab;
    private EditBox input;
    // Top row index of the Permissions matrix, kept across command-driven refreshes like GuildAdminScreen's scroll.
    private int permScroll = 0;
    // Top row index of the Members list, kept across command-driven refreshes exactly like permScroll.
    private int memberScroll = 0;

    // The Y a section's body actually begins at this frame. Equal to BODY_BASE_Y when the tab bar is a single row
    // (the historical case), pushed down by exactly one tab row's worth when the six tabs wrap onto a second row,
    // so the first content row never lands on the second tab row. Set in init() from the tabs() return.
    private int bodyTop = BODY_BASE_Y;

    public GuildScreen(GuildView view)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.guild.title"), UI_W, UI_H, null);
        this.v = view;
        this.tab = view.hasGuild ? lastTab : 0;
    }

    private static int rank(String roleName)
    {
        for (int i = 0; i < ROLE_ORDER.length; i++)
            if (ROLE_ORDER[i].equalsIgnoreCase(roleName))
                return i;
        return -1;
    }

    private static String roleDisplay(String roleName)
    {
        if (roleName == null || roleName.isEmpty())
            return "";
        return roleName.charAt(0) + roleName.substring(1).toLowerCase();
    }

    // True when a member/leader name arrived as a bare UUID, i.e. the server could resolve neither a stored
    // last-known name nor the profile cache. Such an entry is a genuine unresolved member, not a phantom.
    private static boolean looksLikeUuid(String s)
    {
        return s != null && s.length() == 36 && s.charAt(8) == '-' && s.charAt(13) == '-'
                && s.charAt(18) == '-' && s.charAt(23) == '-';
    }

    // Turn a raw-UUID name into a short, clearly-marked label ("Unknown (a1b2c3d4)") in the viewer's language,
    // instead of 36 hex characters that read as a phantom. Any already-resolved name is returned unchanged.
    private static String displayName(String name)
    {
        if (looksLikeUuid(name))
            return net.minecraft.client.resources.language.I18n.get(
                    "gui.dmz_ragnarok.core.guild.member.unknown", name.substring(0, 8));
        return name;
    }

    private void action(String command)
    {
        lastTab = tab;
        GuildGuiClient.run(command);
        GuildGuiClient.run("guild gui");
    }

    private void selectTab(int t)
    {
        tab = t;
        lastTab = t;
        rebuildWidgets();
    }

    private EditBox makeInput(int vx, int vy, int vw, String hint)
    {
        EditBox box = field(vx, vy, vw, "");
        box.setHint(Component.literal(hint));
        box.setMaxLength(32);
        return box;
    }

    @Override
    protected void init()
    {
        super.init();
        if (!v.hasGuild)
        {
            headerSubtitle = "Not in a guild";
            label("§7You are not in a guild.", 14, 40);
            label("§7Use /guild create <name> to found one.", 14, 54);
            btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"), this::onClose);
            return;
        }
        headerName = v.name + "  (" + roleDisplay(v.myRole) + ")";
        // Anchor the body off the actual bottom of the tab bar so a wrapped second tab row (which the sixth Planet
        // tab can introduce) pushes content down instead of overlapping it. When the bar is one row this leaves
        // bodyTop at BODY_BASE_Y, so the five original sections lay out exactly as before.
        int belowTabs = tabs(10, 28, UI_W - 20, TAB_NAMES, tab, this::selectTab);
        bodyTop = Math.max(BODY_BASE_Y, belowTabs + net.shurui.dev.sdu.client.gui.theme.GuiTheme.GAP);
        switch (tab)
        {
            case 1 -> buildMembers();
            case 2 -> buildRelations();
            case 3 -> buildFlags();
            case 4 -> buildPermissions();
            case 5 -> buildWarps();
            case 6 -> buildPlanet();
            case 7 -> buildSalvage();
            default -> buildInfo();
        }
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.close"), this::onClose);
    }

    private void buildInfo()
    {
        int y = bodyTop;
        line(y, "Leader: " + displayName(v.leaderName)); y += 12;
        line(y, "Members: " + v.members.size()); y += 12;
        line(y, "Battle power: " + String.format("%,.0f", v.power)); y += 12;
        line(y, "Claims: " + v.claimCount + " / " + v.maxClaims
                + (v.claimCount > v.maxClaims ? " §c(raidable!)" : "")); y += 12;
        line(y, "Bank: " + String.format("%,d %s", v.bank, v.currency)); y += 12;
        line(y, "Access: " + (v.open ? "open" : "invite-only")); y += 12;
        line(y, "Home: " + (v.homeSet ? "set" : "not set")); y += 12;
        if (!v.motd.isEmpty())
            line(y, "MOTD: " + net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(v.motd));

        // Right-hand action column, anchored to the panel's inner right rather than a literal X. At the old
        // literal 200 these 96-wide buttons reached 296, two px past the inner content edge and onto the panel
        // border art. Deriving it keeps the column flush with the edge whatever the canvas width is.
        int bx = UI_W - GuiTheme.CONTENT_PADDING - 96;
        int by = bodyTop - BODY_BASE_Y;   // keep the right column in step with the body when a tab row wraps
        btn(bx, 50 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Claim chunk"), () -> action("guild claim"));
        btn(bx, 66 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Unclaim chunk"), () -> action("guild unclaim"));
        btn(bx, 82 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Set home"), () -> action("guild sethome"));
        btn(bx, 98 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Go home"), () -> action("guild home"));
        btn(bx, 114 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Deposit 100"), () -> action("guild bank deposit 100"));
        btn(bx, 130 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal("Withdraw 100"), () -> action("guild bank withdraw 100"));
        btn(bx, 146 + by, 96, GuiTheme.BUTTON_HEIGHT, Component.literal(v.open ? "Make invite-only" : "Make open"), () -> action("guild open"));
        btn(14, footerY(), 90, footerBtnHeight(), Component.literal("Leave guild"), () -> action("guild leave"));
    }

    private void buildMembers()
    {
        int myRank = rank(v.myRole);
        int firstRowY = bodyTop;

        // Scrolls with the same idiom as the Permissions tab below: clamp the offset, render only the visible
        // window, then register the band with scrollList so the base screen handles the wheel, the drag and the
        // commissioned bar art. This list used to stop dead at 7 rows and silently drop everyone after them, which
        // on a guild at the default cap of 20 members hid most of the roster with nothing on screen saying so.
        int count = v.members.size();
        // the invite field sits on the footer row (UI_H - 24), so the member list just fills down to it.
        int maxRows = rowsThatFit(firstRowY, 14);
        memberScroll = Math.max(0, Math.min(memberScroll, Math.max(0, count - maxRows)));
        int end = Math.min(count, memberScroll + maxRows);

        // Trailing controls stop short of the reserved scrollbar column so a button can never sit under the bar.
        // Widths are unchanged; only their right edge is now derived rather than hardcoded.
        int kickRight = rowControlRight();
        int kickX = kickRight - 34;
        int demoteX = kickX - 22;
        int promoteX = demoteX - 22;

        for (int i = memberScroll; i < end; i++)
        {
            String[] m = v.members.get(i);
            int y = firstRowY + (i - memberScroll) * 14;
            String raw = m[0];
            String name = displayName(raw);
            int r = rank(m[1]);
            label(name + " §7- " + roleDisplay(m[1]), 14, y + 3);
            // Manage controls only for a resolved member ranked below us: an unresolved (raw-uuid) member cannot be
            // targeted by the name-based /guild promote|demote|kick commands anyway, so we do not offer the buttons.
            boolean manageable = myRank > r && !looksLikeUuid(raw) && !name.equalsIgnoreCase(playerName());
            if (manageable)
            {
                btn(promoteX, y, 20, GuiTheme.ROW_HEIGHT, Component.literal("+"), () -> action("guild promote " + name));
                btn(demoteX, y, 20, GuiTheme.ROW_HEIGHT, Component.literal("-"), () -> action("guild demote " + name));
                btn(kickX, y, 34, GuiTheme.ROW_HEIGHT, Component.literal("Kick"), () -> action("guild kick " + name));
            }
        }
        scrollList(14, uiWidth, firstRowY, 14, maxRows, count, memberScroll,
                s -> { memberScroll = s; rebuildWidgets(); });
        input = makeInput(14, UI_H - 24, 140, "player to invite");
        btn(158, footerY(), 60, footerBtnHeight(), Component.literal("Invite"), () -> {
            if (!input.getValue().isBlank())
                action("guild invite " + input.getValue().trim());
        });
    }

    // The Relations list. Each row shows a guild, our declared stance and the effective stance. For a row whose
    // EFFECTIVE stance is ENEMY it also carries a raid control on the right; every other stance gets no control.
    // The raid control mirrors the server's authoritative RaidCheck (sent in r[3]/r[4]): it is a live "Raid" button
    // only when the server said OK, and otherwise a DISABLED button whose caption plus the row's inline reason say
    // why it cannot start (no planet, either side already raiding, or a cooldown with the time left). The GUI never
    // re-derives the rules; an illegal click cannot happen because the button is inactive, and even if it did the
    // server would refuse it. Row pitch and the 12px-tall control match the Members/Flags/Warps tabs.
    private void buildRelations()
    {
        int y = bodyTop;
        if (v.relations.isEmpty())
            label("§7No declared relations.", 14, y);

        // The raid button is a fixed-width control anchored on the right at rowControlRight() (inside the panel,
        // clear of the border), so the left text always has a known width to stop short of and can never run under
        // the button. rowControlRight() is used even though this tab has no scrollbar, so the control sits where a
        // trailing row control sits on every other list.
        final int raidBtnW = 52;
        final int raidBtnX = rowControlRight() - raidBtnW;
        // Width the row's left text is fitted into so it stops before the raid control (enemy rows) or before the
        // panel's right content edge (non-enemy rows, which have no control).
        final int textRightEnemy = raidBtnX - net.shurui.dev.sdu.client.gui.theme.GuiTheme.GAP;
        final int textRightPlain = rowControlRight();

        for (String[] r : v.relations)
        {
            String on = r[0];
            String declared = r[1];
            String effective = r[2];
            String raidReason = r.length > 3 ? r[3] : "";
            String lockoutLeft = r.length > 4 ? r[4] : "";
            boolean enemy = !raidReason.isEmpty();

            String base = on + ": " + declared + " §7(eff. " + effective + ")";
            if (enemy && !"OK".equals(raidReason))
                base += " §8- " + raidReasonShort(raidReason, on, lockoutLeft);

            int textRight = enemy ? textRightEnemy : textRightPlain;
            label(fit(base, textRight - 14), 14, y + 3);

            if (enemy)
            {
                boolean canRaid = "OK".equals(raidReason);
                final String target = on;
                DmzTextureButton raid = btn(raidBtnX, y, raidBtnW, GuiTheme.ROW_HEIGHT, Component.literal("Raid"),
                        () -> action("guild raid " + target));
                // A non-startable raid is a DISABLED button (greyed art + caption, no click), not a hidden or dead
                // control: the inline reason on the left already says why, and vanilla never fires onPress on it.
                raid.active = canRaid;
            }
            y += 14;
            // The Relations list is a fixed, non-scrolling list (as it always was): it stops at this cap so it never
            // reaches the input row / footer. Because it does not scroll there is no scroll offset that could move a
            // row under the footer or desync the raid button from its row.
            if (y > 150)
                break;
        }
        input = makeInput(14, UI_H - 42, 150, "guild name");
        btn(14, footerY(), 46, footerBtnHeight(), Component.literal("Ally"), () -> rel("ally"));
        btn(64, footerY(), 56, footerBtnHeight(), Component.literal("Enemy"), () -> rel("enemy"));
        btn(124, footerY(), 52, footerBtnHeight(), Component.literal("Truce"), () -> rel("truce"));
        // Narrowed from 62 so the row's right edge (180+54=234) clears the persistent Close button at UI_W-62
        // (x=238) now that both sit on the shared footerY() row; previously they nearly collided at the corner.
        btn(180, footerY(), 54, footerBtnHeight(), Component.literal("Neutral"), () -> rel("neutral"));
    }

    // Short inline reason a disabled raid control shows on the left of an enemy row. Reuses the server's RaidCheck
    // Result name (never re-derived here) and turns it into a brief translated phrase; LOCKED_OUT carries the time
    // remaining the server already formatted. Any reason that is not one of the surfaced few (it should not occur
    // for an enemy row) falls back to a generic "cannot raid" line.
    private String raidReasonShort(String reason, String guildName, String lockoutLeft)
    {
        return switch (reason)
        {
            case "DEFENDER_HAS_NO_PLANET" -> tr("gui.dmz_ragnarok.core.guild.raid.no_planet");
            case "ATTACKER_ALREADY_RAIDING" -> tr("gui.dmz_ragnarok.core.guild.raid.you_raiding");
            case "DEFENDER_ALREADY_RAIDING" -> tr("gui.dmz_ragnarok.core.guild.raid.they_raiding");
            case "LOCKED_OUT" -> tr("gui.dmz_ragnarok.core.guild.raid.cooldown", lockoutLeft);
            default -> tr("gui.dmz_ragnarok.core.guild.raid.blocked");
        };
    }

    // Ellipsize a row's left text to a pixel budget so it can never run under a trailing raid control on a narrow
    // row (the panel is only 300 virtual px wide, so a long guild name plus stance plus reason can overrun). Uses
    // the theme's shared ellipsize so the trim reads the same as every other fitted string.
    private String fit(String text, int budget)
    {
        if (font == null || budget <= 0)
            return text;
        return font.width(text) <= budget
                ? text
                : net.shurui.shuruisutilities.client.gui.theme.GuiText.ellipsize(font, text, budget);
    }

    private void rel(String kind)
    {
        if (!input.getValue().isBlank())
            action("guild " + kind + " " + input.getValue().trim());
    }

    private void buildFlags()
    {
        int y = bodyTop;
        for (String[] f : v.flags)
        {
            final String id = f[0];
            boolean on = Boolean.parseBoolean(f[1]);
            label(id + ": " + (on ? "§aON" : "§cOFF"), 14, y + 3);
            btn(150, y, 40, GuiTheme.ROW_HEIGHT, Component.literal("On"), () -> action("guild flag " + id + " on"));
            btn(194, y, 40, GuiTheme.ROW_HEIGHT, Component.literal("Off"), () -> action("guild flag " + id + " off"));
            y += 14;
            if (y > UI_H - 30)
                break;
        }
    }

    // The per-rank permission matrix: one row per GuildPermission, one toggle cell per role column. Each toggle
    // dispatches the EXISTING "/guild perm <role> <permission> <on|off>" command through action(), exactly like the
    // Flags tab, so the server stays the single write authority; there is no new packet or write path here. The list
    // scrolls with the same idiom as GuildAdminScreen (render the visible window + register scrollList) since the
    // permission list is longer than the panel.
    //
    // Edit gate: only the guild leader may change the matrix (the /guild perm command is leader-only). Leadership is
    // read from v.myRole (LEADER means the viewer is the leader); GuildView carries no separate leader flag. A leader
    // sees interactive toggles on the three lower ranks; everyone else sees the SAME matrix fully read-only, so a
    // member can still see what their rank actually grants and can never click a toggle the server would just reject.
    //
    // LEADER column: Guild.hasPermission short circuits to true for the leader regardless of the stored
    // rolePermissions, so that column is always rendered on and non-interactive; the GUI must not imply a leader
    // grant can be toggled off.
    private void buildPermissions()
    {
        boolean canEdit = "LEADER".equalsIgnoreCase(v.myRole);

        final int cols = ROLE_ORDER.length;
        final int cellW = 30;
        final int cellGap = 6;
        final int gridW = cols * cellW + (cols - 1) * cellGap;
        final int gridX = rowControlRight() - gridW;

        // column headers: the role each toggle column edits.
        int headerY = bodyTop;
        for (int c = 0; c < cols; c++)
        {
            int cx = gridX + c * (cellW + cellGap) + cellW / 2;
            labelCentered(roleDisplay(ROLE_ORDER[c]), cx, headerY,
                    net.shurui.dev.sdu.client.gui.theme.GuiTheme.COLOR_TITLE);
        }

        int firstRowY = headerY + 14;
        int count = v.permissions.size();
        int cap = rowsThatFit(firstRowY, 14);
        permScroll = Math.max(0, Math.min(permScroll, Math.max(0, count - cap)));
        int end = Math.min(count, permScroll + cap);

        for (int i = permScroll; i < end; i++)
        {
            String[] row = v.permissions.get(i);
            final String permId = row[0];
            int ry = firstRowY + (i - permScroll) * 14;
            label(permId, 14, ry + 3);
            for (int c = 0; c < cols; c++)
            {
                boolean on = Boolean.parseBoolean(row[c + 1]);
                int cx = gridX + c * (cellW + cellGap);
                boolean leaderCol = ROLE_ORDER[c].equalsIgnoreCase("LEADER");
                // The leader column is never editable (its grant is ignored by the code), and for a non-leader viewer
                // every column is read-only; so only a leader gets a live toggle on the three lower ranks.
                boolean interactive = canEdit && !leaderCol;
                final String roleArg = ROLE_ORDER[c].toLowerCase(java.util.Locale.ROOT);
                final boolean cur = on;
                DmzTextureButton cell = btn(cx, ry, cellW, GuiTheme.ROW_HEIGHT, Component.literal(on ? "§aON" : "§cOFF"),
                        () -> action("guild perm " + roleArg + " " + permId + " " + (cur ? "off" : "on")));
                // A read-only cell is a DISABLED button (greyed art, no click), not a hidden control: the state still
                // reads clearly and vanilla never fires onPress on it, so a non-leader click cannot reach the server.
                cell.active = interactive;
            }
        }

        scrollList(14, uiWidth, firstRowY, 14, cap, count, permScroll,
                s -> { permScroll = s; rebuildWidgets(); });
    }

    private void buildWarps()
    {
        int y = bodyTop;
        if (v.warps.isEmpty())
            label("§7No guild warps set.", 14, y);
        int shown = 0;
        for (String w : v.warps)
        {
            if (shown++ >= 6)
                break;
            final String name = w;
            label(name, 14, y + 3);
            btn(150, y, 46, GuiTheme.ROW_HEIGHT, Component.literal("Warp"), () -> action("guild warp " + name));
            btn(200, y, 40, GuiTheme.ROW_HEIGHT, Component.literal("Del"), () -> action("guild delwarp " + name));
            y += 14;
        }
        input = makeInput(14, UI_H - 24, 140, "new warp name");
        btn(158, footerY(), 60, footerBtnHeight(), Component.literal("Set warp"), () -> {
            if (!input.getValue().isBlank())
                action("guild setwarp " + input.getValue().trim());
        });
    }

    // The generated-planet tab. Surfaces the one-planet-per-guild claim built in phase 2 (previously only reachable
    // via /spaceplanet). It is a pure reflection of GuildView.planet* fields: the server is authoritative, so the
    // buttons just dispatch /spaceplanet claim|unclaim through the shared action() path (same as every other tab)
    // and the screen refreshes from the server's re-sent view. The rank gate, the not-your-planet refusal and the
    // one-per-guild rule all stay server-side; the tab only mirrors their current outcome so nothing is a surprise.
    private void buildPlanet()
    {
        int y = bodyTop;

        // line 1: whether this guild already owns a planet, named. The one-per-guild rule is stated here, not hidden
        // behind a silently disabled button.
        if (v.ownedPlanetName.isEmpty())
            line(y, tr("gui.dmz_ragnarok.core.guild.planet.none"));
        else
            line(y, tr("gui.dmz_ragnarok.core.guild.planet.owned", v.ownedPlanetName));
        y += 12;
        line(y, tr("gui.dmz_ragnarok.core.guild.planet.rule"));
        y += 16;

        // if the player is not standing on a generated planet there is nothing to claim from here: say so and stop.
        if (!v.onPlanet)
        {
            label("§7" + tr("gui.dmz_ragnarok.core.guild.planet.off_world"), 14, y);
            return;
        }

        // line block for the planet under the player: its name, its surface size, and who (if anyone) owns it.
        line(y, tr("gui.dmz_ragnarok.core.guild.planet.here", v.hereName));
        y += 12;
        String size = String.valueOf(v.hereSurfaceSize);
        line(y, tr("gui.dmz_ragnarok.core.guild.planet.size", size, size));
        y += 12;

        if (v.hereOwner.isEmpty())
            line(y, tr("gui.dmz_ragnarok.core.guild.planet.here_unclaimed"));
        else if (v.hereOwner.equals("self"))
            line(y, tr("gui.dmz_ragnarok.core.guild.planet.here_yours"));
        else
            line(y, tr("gui.dmz_ragnarok.core.guild.planet.here_other", v.hereOwner));
        y += 18;

        // the single action button. Its availability mirrors the server's rule set, but the server stays the sole
        // authority: an illegal click just comes back with a chat error and an unchanged view.
        if (v.hereOwner.equals("self"))
        {
            // this guild owns the planet under the player: offer Unclaim.
            btn(14, y, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.planet.unclaim"),
                    () -> action("spaceplanet unclaim"));
        }
        else if (v.hereOwner.isEmpty())
        {
            // unclaimed underfoot. If the guild already holds a different planet, the one-per-guild rule means a claim
            // would be refused, so show why instead of a button; otherwise offer Claim.
            if (v.ownedPlanetName.isEmpty())
                btn(14, y, 120, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.planet.claim"),
                        () -> action("spaceplanet claim"));
            else
                label("§7" + tr("gui.dmz_ragnarok.core.guild.planet.owns_elsewhere", v.ownedPlanetName), 14, y + 3);
        }
        // else: owned by another guild. The here_other line already named them; no button, since the server would
        // refuse the claim anyway.
    }

    // The raid-loss-recovery tab. Surfaces the per-GUILD salvage vault filled when one of this guild's planets was
    // DESTROYED (see GuildRaidSalvageVault, distinct from the per-raider GuildRaidVault). It is a pure reflection of
    // GuildView.salvage* : the server owns the pile, so a Take button just dispatches "guild salvage take <index>" (or
    // "takeall") through the shared action() path and the screen refreshes from the re-sent view. The withdraw rank
    // gate (BANK_WITHDRAW, officer+ by default) is ENFORCED server-side; this tab merely hides the Take controls for a
    // member who lacks it, and the server refuses the withdraw regardless if the client is bypassed.
    private void buildSalvage()
    {
        int y = bodyTop;
        line(y, tr("gui.dmz_ragnarok.core.guild.salvage.intro"));
        y += 14;

        if (v.salvageTotal == 0)
        {
            label("§7" + tr("gui.dmz_ragnarok.core.guild.salvage.empty"), 14, y);
            return;
        }
        line(y, tr("gui.dmz_ragnarok.core.guild.salvage.count", String.valueOf(v.salvageTotal)));
        y += 14;

        // The tab only offers withdrawals to a member of sufficient rank; the server is the real gate. A member who
        // cannot withdraw still sees the pile size above, just no Take controls.
        if (!v.canWithdrawSalvage)
        {
            label("§7" + tr("gui.dmz_ragnarok.core.guild.salvage.no_rank"), 14, y);
            return;
        }

        int shown = 0;
        for (int i = 0; i < v.salvage.size(); i++)
        {
            if (shown++ >= 6)
                break;
            String[] row = v.salvage.get(i);
            final int index = i;
            label(fit(row[0] + " §7x" + row[1], 130), 14, y + 3);
            btn(150, y, 46, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.salvage.take"),
                    () -> action("guild salvage take " + index));
            y += 14;
        }
        // Take All clears the whole pile (including stacks past the listed rows), overflowing to the ground if the
        // player's inventory is full, so a large pile is retrievable without paging.
        btn(14, footerY(), 100, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.guild.salvage.take_all"),
                () -> action("guild salvage takeall"));
    }

    private String playerName()
    {
        return minecraft != null && minecraft.player != null ? minecraft.player.getGameProfile().getName() : "";
    }

    private void line(int y, String text)
    {
        label("§f" + text, 14, y);
    }
}
