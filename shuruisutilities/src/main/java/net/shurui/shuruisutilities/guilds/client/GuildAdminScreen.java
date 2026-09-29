package net.shurui.shuruisutilities.guilds.client;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.guilds.network.GuildSalvageConfigView;
import net.shurui.shuruisutilities.guilds.network.GuildSummary;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Admin guild GUI on the shared {@link SagaBaseScreen} toolkit: a scrollable list of every guild with
 * its size / power / claim usage and a per-row Disband action, plus a RAID LOSS RECOVERY salvage-rate panel
 * beneath it. Actions dispatch {@code /guild admin ...} commands, then re-request the list
 * ({@code /guild admin gui}), the same button-dispatches-a-command idiom the rest of the guild GUI uses.
 */
public class GuildAdminScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 20;
    // The salvage-rate panel below the list is a fixed block (title, rate + ticket controls, override editor and up
    // to two override rows), about 130px tall including its top gap. Reserve that so the list fills whatever is left
    // above it and the panel can never be pushed off the bottom of the screen.
    private static final int SALVAGE_PANEL_RESERVE = 130;

    // step a rate up/down by ten percentage points per click, clamped into 0..1.
    private static final double RATE_STEP = 0.1;
    // step the scan-height allowance by 16 blocks per click.
    private static final int SCAN_STEP = 16;
    // step the raid-win ticket reward by one ticket per click. Floored at 0 (0 disables the reward).
    private static final int TICKET_STEP = 1;

    private final List<GuildSummary> guilds;
    private final GuildSalvageConfigView salvage;
    private int scroll = 0;
    private EditBox overrideInput;

    public GuildAdminScreen(List<GuildSummary> guilds, GuildSalvageConfigView salvage)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.guild.admin.title"), UI_W, UI_H, null);
        this.guilds = guilds;
        this.salvage = salvage;
    }

    private void action(String command)
    {
        GuildGuiClient.run(command);
        GuildGuiClient.run("guild admin gui");
    }

    // Visible guild rows, derived so the list fills the space above the fixed salvage panel. Used both to build the
    // list and to place that panel directly beneath it, so the two can never overlap.
    private int maxRows()
    {
        return rowsThatFit(LIST_TOP, ROW_H, SALVAGE_PANEL_RESERVE);
    }

    // clamp a rate into 0..1 so a +/- click can never drive the config out of range.
    private static double clampRate(double r)
    {
        return Math.max(0.0, Math.min(1.0, r));
    }

    private static int pct(double rate)
    {
        return (int) Math.round(clampRate(rate) * 100.0);
    }

    // format a rate as a plain decimal for the command argument (LongArgument-free; the command parses a double).
    private static String rateArg(double rate)
    {
        return String.format(java.util.Locale.ROOT, "%.2f", clampRate(rate));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = "Guilds (" + guilds.size() + ")";
        int maxRows = maxRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, guilds.size() - maxRows)));
        int end = Math.min(guilds.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            GuildSummary s = guilds.get(i);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            label("§a" + s.name, 14, ry);
            label("§7" + s.members + " members  |  claims " + s.claims + "/" + s.maxClaims
                    + "  |  BP " + String.format("%,.0f", s.power), 14, ry + 9);
            btn(rowControlRight() - 60, ry, 60, GuiTheme.BUTTON_HEIGHT, Component.literal("Disband"), () -> action("guild admin disband " + s.name));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, guilds.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });

        buildSalvagePanel();

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                net.shurui.shuruisutilities.client.gui.EditorScreens::openAdminHub);
    }

    // The salvage-rate editor. Each control dispatches a "/guild admin salvage ..." command (the +/- buttons compute
    // the clamped next value and set it absolutely) then refreshes, so the server config file stays authoritative.
    private void buildSalvagePanel()
    {
        int y = LIST_TOP + maxRows() * ROW_H + 6;   // just below the guild list

        label("§e" + tr("gui.dmz_ragnarok.core.guild.admin.salvage.title"), 14, y);
        y += 12;
        label("§7" + tr("gui.dmz_ragnarok.core.guild.admin.salvage.rates",
                String.valueOf(pct(salvage.containerRate)),
                String.valueOf(pct(salvage.blockRate)),
                String.valueOf(salvage.scanHeightAbove)), 14, y);
        y += 12;

        // containers -/+, blocks -/+, scan height -/+
        btn(14, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("C -"),
                () -> action("guild admin salvage container " + rateArg(salvage.containerRate - RATE_STEP)));
        btn(50, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("C +"),
                () -> action("guild admin salvage container " + rateArg(salvage.containerRate + RATE_STEP)));
        btn(90, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("B -"),
                () -> action("guild admin salvage block " + rateArg(salvage.blockRate - RATE_STEP)));
        btn(126, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("B +"),
                () -> action("guild admin salvage block " + rateArg(salvage.blockRate + RATE_STEP)));
        btn(166, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("H -"),
                () -> action("guild admin salvage scanheight " + Math.max(0, salvage.scanHeightAbove - SCAN_STEP)));
        btn(202, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("H +"),
                () -> action("guild admin salvage scanheight " + (salvage.scanHeightAbove + SCAN_STEP)));
        y += 16;

        // raid WIN reward: tickets granted per participating raider on a win. 0 disables the reward. Same
        // button-dispatches-a-command idiom as the rates above; the value is stepped absolutely and floored at 0.
        label("§7" + tr("gui.dmz_ragnarok.core.guild.admin.tickets", String.valueOf(salvage.ticketReward)), 14, y);
        y += 12;
        btn(14, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("T -"),
                () -> action("guild admin salvage tickets " + Math.max(0, salvage.ticketReward - TICKET_STEP)));
        btn(50, y, 34, GuiTheme.BUTTON_HEIGHT, Component.literal("T +"),
                () -> action("guild admin salvage tickets " + (salvage.ticketReward + TICKET_STEP)));
        y += 16;

        // per-block override: type "<blockid> <rate>" and Add. A rate of 0 excludes that block from salvage entirely.
        overrideInput = field(14, y, 150, "");
        overrideInput.setHint(Component.translatable("gui.dmz_ragnarok.core.guild.admin.salvage.override_hint"));
        overrideInput.setMaxLength(64);
        btn(168, y, 44, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.admin.salvage.add"), this::addOverride);
        btn(216, y, 44, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.admin.salvage.clear"),
                () -> action("guild admin salvage clearoverrides"));
        y += 16;

        label("§7" + tr("gui.dmz_ragnarok.core.guild.admin.salvage.override_count",
                String.valueOf(salvage.overrides.size())), 14, y);
        y += 12;

        // show the first couple of overrides with a Del button each, for at-a-glance editing; the rest are managed by
        // command or config. Bounded so the panel never runs into the footer.
        int shown = 0;
        for (String[] o : salvage.overrides)
        {
            if (shown++ >= 2)
                break;
            final String id = o[0];
            label("§f" + id + " §7= " + o[1], 14, y + 2);
            btn(216, y, 44, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.guild.admin.salvage.del"),
                    () -> action("guild admin salvage removeoverride " + id));
            y += 14;
        }
    }

    // parse the "<blockid> <rate>" input and dispatch the override command. A missing rate defaults to 0 (exclude).
    private void addOverride()
    {
        if (overrideInput == null)
            return;
        String raw = overrideInput.getValue().trim();
        if (raw.isEmpty())
            return;
        String[] parts = raw.split("\\s+");
        String id = parts[0];
        String rate = parts.length > 1 ? parts[1] : "0";
        action("guild admin salvage override " + id + " " + rate);
    }
}
