package net.shurui.shuruisutilities.character.client;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.character.PacketCharacterAction;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The character-slot picker: lists the player's slots (active one marked) with Switch / Rename / Delete, plus a
 * name field and a "New character" button. Every action goes to the server ({@link PacketCharacterAction}),
 * which re-sends the refreshed list, so the screen always mirrors authoritative server state.
 */
public class CharacterSelectScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 16;

    private List<String> names;
    private int active;
    private int max;
    // tournament character row state (see TournamentCharBridge): shown only when the tournament feature is on. The
    // tournament character is never a selectable slot; this row only creates it in advance or deletes it.
    private boolean tourFeature;
    private boolean tourPresent;
    private boolean tourActive;
    private int scroll;
    private EditBox nameBox;

    public CharacterSelectScreen(List<String> names, int active, int max,
                                 boolean tourFeature, boolean tourPresent, boolean tourActive)
    {
        super(Component.literal("Characters"), UI_W, UI_H, null);
        this.names = new ArrayList<>(names);
        this.active = active;
        this.max = max;
        this.tourFeature = tourFeature;
        this.tourPresent = tourPresent;
        this.tourActive = tourActive;
    }

    /** Open the picker, or refresh it in place if it's already showing. */
    public static void openOrUpdate(List<String> names, int active, int max,
                                    boolean tourFeature, boolean tourPresent, boolean tourActive)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof CharacterSelectScreen s)
        {
            s.names = new ArrayList<>(names);
            s.active = active;
            s.max = max;
            s.tourFeature = tourFeature;
            s.tourPresent = tourPresent;
            s.tourActive = tourActive;
            s.rebuildWidgets();
        }
        else
        {
            mc.setScreen(new CharacterSelectScreen(names, active, max, tourFeature, tourPresent, tourActive));
        }
    }

    private static void act(String action, int slot, String name)
    {
        NetworkUtils.INSTANCE.sendToServer(new PacketCharacterAction(action, slot, name));
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // No descriptive header text: the slot list is self-evidently the character picker, so per the suite rule
        // a generic label ("Characters") is dropped. The "Slots" tab (with its slot count) still shows what/how many.
        rowY = buildTabHeader(null, new String[] { "Slots (" + names.size() + "/" + max + ")" }, 0, t -> { });

        int top = rowY;
        // reserve the bottom band for the tournament-character row (UI_H - 66) + name field (UI_H - 46) + New button;
        // 60 keeps the list above the tournament row, matching the old UI_H - 84 list bottom.
        int cap = rowsThatFit(top, ROW_H, 60);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - cap)));
        int end = Math.min(names.size(), scroll + cap);
        for (int i = scroll; i < end; i++)
        {
            final int idx = i;
            int ry = top + (i - scroll) * ROW_H;
            boolean cur = i == active;
            label((cur ? "§a" : "§f") + "#" + (i + 1) + " " + names.get(i) + (cur ? " §a(active)" : ""), 14, ry + 4);
            // trailing Switch / Rename / delete-X right-aligned so nothing sits under the scrollbar column; the X
            // is a round icon pill at the standard icon size, matching the theme's icon-button rule.
            int xW = 14;
            int xBtn = rowControlRight() - xW;
            int renameW = 40;
            int renameX = xBtn - 4 - renameW;
            int switchW = 40;
            int switchX = renameX - 4 - switchW;
            if (!cur)
                btn(switchX, ry, switchW, 12, Component.literal("Switch"), () -> act("switch", idx, ""));
            btn(renameX, ry, renameW, 12, Component.literal("Rename"),
                    () -> act("rename", idx, nameBox == null ? "" : nameBox.getValue().trim()));
            if (!cur && names.size() > 1)
                btn(xBtn, ry, xW, 12, Component.literal("§cX"), () -> act("delete", idx, "")).asIcon();
        }
        scrollList(12, uiWidth, top, ROW_H, cap, names.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        // Tournament character row. Not a selectable slot: it can only be created in advance or deleted here, and it
        // is refused deletion by the server while its owner is actually in a match. Colour carries the state (bright
        // when it exists, dim when it does not); the buttons carry the action, so no status line is needed.
        if (tourFeature)
        {
            int ty = UI_H - 66;
            label((tourPresent ? "§b" : "§8") + I18n.get("gui.dmz_ragnarok.core.character.tournament"), 14, ty + 4);
            if (!tourPresent)
            {
                // Tracker #1061/#1074: a bare "+ Create" here read as "make a new character slot", and players who
                // pressed it thought the sandbox had overwritten their character. The label says it is not a slot and
                // the tooltip says what it builds and that their character comes back when they finish.
                Component createLabel = Component.translatable("gui.dmz_ragnarok.core.character.tournament_create");
                int createW = Math.max(90, font.width(createLabel) + 12);
                btn(rowControlRight() - createW, ty, createW, 12, createLabel, () -> act("tour_create", 0, ""))
                        .setTooltip(Tooltip.create(
                                Component.translatable("gui.dmz_ragnarok.core.character.tournament_create_tip")));
            }
            else if (!tourActive)
            {
                int xW = 14;
                btn(rowControlRight() - xW, ty, xW, 12, Component.literal("§cX"),
                        () -> act("tour_delete", 0, "")).asIcon();
            }
        }

        // Name field + New character button.
        int by = UI_H - 46;
        nameBox = field(14, by, 150, "");
        nameBox.setHint(Component.literal("name (New / Rename)"));
        nameBox.setMaxLength(32);
        boolean canNew = names.size() < max;
        btn(170, by - 1, 78, 14, Component.literal(canNew ? "§a+ New character" : "§7Slot limit reached"),
                () -> { if (canNew) act("new", 0, nameBox.getValue().trim()); });

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::onClose);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
