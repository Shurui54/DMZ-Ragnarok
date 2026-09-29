package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.network.chat.Component;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The player event hub: the active event's info, its quests with live progress, and its token shop. Fed by the
 * {@code "eventhub"} {@link net.shurui.shuruisutilities.hub.PacketEditorData} the Ragnarok Key builds ({@code EventHub}
 * server side), it drives the two player actions back through {@code PacketEditorAction}: {@code claim <questId>}
 * and {@code buy <index>}. It is a pure core screen with no key dependency, gated in {@code EditorScreens} on
 * {@code ClientGate.feature("events")}, so a keyless server never opens it.
 *
 * <p>Up to three tabs, Quests, Shop and (when the event runs a board) Leaderboard, all delivered in one payload so
 * switching needs no round trip; only an action goes to the server, which re-sends the whole hub. No subtitle or
 * counter chrome, per the house rule: the header is the event name and the token balance, and that is all.
 *
 * <p>Meta layout (positional, appended never inserted): [0] eventId, [1] name, [2] themeKey, [3] primary hex,
 * [4] token balance, [5] banner text, [6] remaining ms, [7] token display name, [8] leaderboard enabled,
 * [9] leaderboard metric label. Rows are tagged in column 0: a quest is
 * {@code [Q, id, title, progress, threshold, state, rewardLabel]}, a shop offer is
 * {@code [S, index, giveLabel, cost, affordable]}, a leaderboard row is {@code [L, rank, playerName, value]}.
 */
public class EventHubScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int HEADER_Y = 20;
    private static final int TAB_Y = 54;
    private static final int LIST_TOP = 72;
    private static final int ROW_H = 16;

    private final List<String> meta;
    private final List<List<String>> rows;
    private int tab; // 0 quests, 1 shop, 2 leaderboard
    private int scroll;

    public EventHubScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.events.hub.title"), UI_W, UI_H, null);
        this.meta = meta == null ? new ArrayList<>() : meta;
        this.rows = rows == null ? new ArrayList<>() : rows;
    }

    private String metaAt(int i, String fallback)
    {
        return meta.size() > i && meta.get(i) != null ? meta.get(i) : fallback;
    }

    private List<List<String>> rowsOfType(String type)
    {
        List<List<String>> out = new ArrayList<>();
        for (List<String> r : rows)
            if (!r.isEmpty() && type.equals(r.get(0)))
                out.add(r);
        return out;
    }

    @Override
    protected void init()
    {
        super.init();

        String name = metaAt(1, "Event").replace('&', '§');
        labelCentered("§f" + name, uiWidth / 2, HEADER_Y, 0xFFFFFFFF);
        String token = metaAt(7, "").replace('&', '§');
        String tokenName = token.isBlank() ? tr("gui.dmz_ragnarok.events.hub.tokens") : token;
        label("§e" + tokenName + ": §f" + metaAt(4, "0"), 14, HEADER_Y + 12, 0xFFFFE066);
        String remaining = remainingText(metaAt(6, "0"));
        if (!remaining.isEmpty())
            label("§7" + tr("gui.dmz_ragnarok.events.hub.ends_in") + " §f" + remaining,
                    14, HEADER_Y + 22, 0xFFB0B0B0);

        // Tabs. The active one is captioned bold. Quests and Shop are always present; Leaderboard shows only when
        // the event enables a board (meta[8]). Guard the selected tab so a hidden Leaderboard cannot stay selected.
        boolean hasLeaderboard = Boolean.parseBoolean(metaAt(8, "false"));
        if (tab == 2 && !hasLeaderboard)
            tab = 0;
        int tabW = 70;
        btn(14, TAB_Y, tabW, footerBtnHeight(),
                Component.literal((tab == 0 ? "§f" : "§7") + tr("gui.dmz_ragnarok.events.hub.tab.quests")),
                () -> { tab = 0; scroll = 0; rebuildWidgets(); });
        btn(14 + tabW + 6, TAB_Y, tabW, footerBtnHeight(),
                Component.literal((tab == 1 ? "§f" : "§7") + tr("gui.dmz_ragnarok.events.hub.tab.shop")),
                () -> { tab = 1; scroll = 0; rebuildWidgets(); });
        if (hasLeaderboard)
            btn(14 + 2 * (tabW + 6), TAB_Y, tabW, footerBtnHeight(),
                    Component.literal((tab == 2 ? "§f" : "§7") + tr("gui.dmz_ragnarok.events.hub.tab.leaderboard")),
                    () -> { tab = 2; scroll = 0; rebuildWidgets(); });

        if (tab == 0)
            drawQuests();
        else if (tab == 1)
            drawShop();
        else
            drawLeaderboard();

        btn(UI_W - 62, footerY(), 48, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.btn.menu"), this::onClose);
    }

    private void drawQuests()
    {
        List<List<String>> quests = rowsOfType("Q");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, quests.size() - maxRows)));
        int end = Math.min(quests.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = quests.get(i);
            final String questId = col(r, 1);
            String title = col(r, 2).replace('&', '§');
            String progress = col(r, 3);
            String threshold = col(r, 4);
            String state = col(r, 5);
            String rewardLabel = col(r, 6);
            int ry = LIST_TOP + (i - scroll) * ROW_H;

            int actW = 52;
            int actX = rowControlRight() - actW;
            String left = "§f" + title + "  §7(" + progress + "/" + threshold + ")";
            String rightText = rewardLabel.isBlank() ? "" : "§6-> " + rewardLabel;
            rowBtn(14, ry, actX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal(left), () -> { })
                    .right(Component.literal(rightText), 0xFFF6C453);

            switch (state)
            {
                case "CLAIMABLE" -> btn(actX, ry, actW, GuiTheme.ROW_HEIGHT,
                        Component.literal("§a" + tr("gui.dmz_ragnarok.events.hub.claim")),
                        () -> EditorScreens.act("eventhub", "claim", questId));
                case "CLAIMED" -> label("§8" + tr("gui.dmz_ragnarok.events.hub.claimed"), actX, ry + 2, 0xFF808080);
                default -> { }
            }
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, quests.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });
        if (quests.isEmpty())
            label("§8" + tr("gui.dmz_ragnarok.events.hub.no_quests"), 14, LIST_TOP + 4, 0xFF808080);
    }

    private void drawShop()
    {
        List<List<String>> offers = rowsOfType("S");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, offers.size() - maxRows)));
        int end = Math.min(offers.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = offers.get(i);
            final String index = col(r, 1);
            String give = col(r, 2).replace('&', '§');
            String cost = col(r, 3);
            boolean affordable = Boolean.parseBoolean(col(r, 4));
            int ry = LIST_TOP + (i - scroll) * ROW_H;

            int actW = 52;
            int actX = rowControlRight() - actW;
            rowBtn(14, ry, actX - 4 - 14, GuiTheme.ROW_HEIGHT, Component.literal("§f" + give), () -> { })
                    .right(Component.literal("§e" + cost), 0xFFFFE066);
            btn(actX, ry, actW, GuiTheme.ROW_HEIGHT,
                    Component.literal((affordable ? "§a" : "§7") + tr("gui.dmz_ragnarok.events.hub.buy")),
                    () -> EditorScreens.act("eventhub", "buy", index));
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, offers.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });
        if (offers.isEmpty())
            label("§8" + tr("gui.dmz_ragnarok.events.hub.no_shop"), 14, LIST_TOP + 4, 0xFF808080);
    }

    private void drawLeaderboard()
    {
        // A read-only ranking: rank, player name, score. The score is labelled by the metric name (meta[9]), so a
        // token board reads "Candy". No actions, no chrome.
        String metricLabel = metaAt(9, "").replace('&', '§');
        List<List<String>> board = rowsOfType("L");
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, board.size() - maxRows)));
        int end = Math.min(board.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            List<String> r = board.get(i);
            String rank = col(r, 1);
            String name = col(r, 2);
            String value = col(r, 3);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            String left = "§e#" + rank + "  §f" + name;
            String right = "§6" + value + (metricLabel.isBlank() ? "" : " §7" + metricLabel);
            rowBtn(14, ry, rowControlRight() - 14, GuiTheme.ROW_HEIGHT, Component.literal(left), () -> { })
                    .right(Component.literal(right), 0xFFF6C453);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, board.size(), scroll,
                v -> { scroll = v; rebuildWidgets(); });
        if (board.isEmpty())
            label("§8" + tr("gui.dmz_ragnarok.events.hub.no_leaderboard"), 14, LIST_TOP + 4, 0xFF808080);
    }

    private static String col(List<String> row, int i)
    {
        return row.size() > i && row.get(i) != null ? row.get(i) : "";
    }

    /** Turn a remaining-milliseconds string into "Xd Yh" / "Yh Zm" / "Zm", or empty when unknown or non-positive. */
    private static String remainingText(String raw)
    {
        long ms;
        try
        {
            ms = Long.parseLong(raw.trim());
        }
        catch (NumberFormatException e)
        {
            return "";
        }
        if (ms <= 0)
            return "";
        long totalMinutes = ms / 60000L;
        long days = totalMinutes / (60 * 24);
        long hours = (totalMinutes / 60) % 24;
        long minutes = totalMinutes % 60;
        if (days > 0)
            return days + "d " + hours + "h";
        if (hours > 0)
            return hours + "h " + minutes + "m";
        return minutes + "m";
    }
}
