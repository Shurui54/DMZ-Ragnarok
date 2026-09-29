package net.shurui.shuruisutilities.prestige.client;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.prestige.PacketPrestigeAction;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The prestige NPC screen: shows the character's current prestige, the TP-gain benefit for every prestige
 * level, and a Prestige button (enabled only when the server says the character can prestige). Pressing it
 * asks the server to prestige ({@link PacketPrestigeAction}), which then re-sends this screen refreshed.
 */
public class PrestigeScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private int level;
    private int max;
    private int perLevelPct;
    private int permPct;
    private boolean canPrestige;

    public PrestigeScreen(int level, int max, int perLevelPct, int permPct, boolean canPrestige)
    {
        super(Component.literal("Prestige"), UI_W, UI_H, null);
        set(level, max, perLevelPct, permPct, canPrestige);
    }

    private void set(int level, int max, int perLevelPct, int permPct, boolean canPrestige)
    {
        this.level = level;
        this.max = max;
        this.perLevelPct = perLevelPct;
        this.permPct = permPct;
        this.canPrestige = canPrestige;
    }

    /** Open the screen, or refresh it in place if it's already showing. */
    public static void open(int level, int max, int perLevelPct, int permPct, boolean canPrestige)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof PrestigeScreen s)
        {
            s.set(level, max, perLevelPct, permPct, canPrestige);
            s.rebuildWidgets();
        }
        else
        {
            mc.setScreen(new PrestigeScreen(level, max, perLevelPct, permPct, canPrestige));
        }
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        // The player's level ("N / max") lives in the single tab caption instead of a centred subtitle, which
        // collided with the tab row. Keeps the useful value, drops the redundant centred word.
        rowY = buildTabHeader(null, new String[] { "Prestige " + level + " / " + max }, 0, t -> { });

        int y = rowY + 2;
        label("§7TP gain per prestige level:", 14, y);
        y += 12;
        for (int i = 1; i <= max; i++)
        {
            boolean reached = i <= level;
            String mark = reached ? "§a> " : "§8- ";
            label(mark + "§fPrestige " + i + ": §e+" + (i * perLevelPct) + "%§f TP" + (reached ? " §a(earned)" : ""),
                    18, y);
            y += 11;
        }

        y += 4;
        if (permPct > 0)
        {
            label("§bPermission bonus: §e+" + permPct + "%§f TP §7(su.tpgain)", 14, y);
            y += 12;
        }
        label("§6Total TP bonus: §e+" + (level * perLevelPct + permPct) + "%", 14, y);
        y += 14;

        label("§c[!] Prestiging resets your stats, abilities,", 14, y);
        y += 10;
        label("§c    mastery, techniques and Z-Soul progress.", 14, y);
        y += 12;
        label(canPrestige ? "§aMax level reached - ready to prestige!"
                : (level >= max ? "§7Maximum prestige reached." : "§7Reach max level to prestige."), 14, y);

        // Prestige button.
        int by = UI_H - 42;
        boolean enabled = canPrestige;
        btn(14, by, 150, footerBtnHeight(),
                Component.literal(enabled ? "§a§lPRESTIGE" : (level >= max ? "§7Maxed" : "§7Not max level")),
                () -> { if (enabled) NetworkUtils.INSTANCE.sendToServer(new PacketPrestigeAction("prestige")); });

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"), this::onClose);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
