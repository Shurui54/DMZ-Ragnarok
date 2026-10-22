package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.compat.client.NpcRegionCacheClient;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// popup after right-dragging a box (or closing a freeform outline) on Xaero's World Map: ADD it to an existing region
// (from the list) or CREATE a new one. runs /npcregion addbox or create for a box, or the npcregions "outline" editor
// action for an outline (server-authoritative either way), opening the region editor for new regions.
public class NpcRegionAddSelectionScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_ROW_H = 15;

    private final String dim;
    private final int x1, z1, x2, z2;
    // a freeform outline from the map's draw mode (interleaved x, z), or null for a right-dragged box
    private final int[] outline;
    private int scroll = 0;

    public NpcRegionAddSelectionScreen(String dim, int x1, int z1, int x2, int z2)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.npcsel.add_title"), UI_W, UI_H, null);
        this.dim = dim;
        this.x1 = x1;
        this.z1 = z1;
        this.x2 = x2;
        this.z2 = z2;
        this.outline = null;
    }

    /** The same popup for a freeform outline; sent as the npcregions "outline" action, which the server validates. */
    public NpcRegionAddSelectionScreen(String dim, int[] outline)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.npcsel.add_title"), UI_W, UI_H, null);
        this.dim = dim;
        int[] b = net.shurui.shuruisutilities.util.PolygonXZ.bounds(outline);
        this.x1 = b[0];
        this.z1 = b[1];
        this.x2 = b[2];
        this.z2 = b[3];
        this.outline = outline.clone();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        int w = Math.abs(x2 - x1) + 1, h = Math.abs(z2 - z1) + 1;
        headerSubtitle = outline != null
                ? tr("gui.dmz_ragnarok.core.npcdraw.popup_dims", outline.length / 2,
                        net.shurui.shuruisutilities.util.PolygonXZ.area(outline))
                : tr("gui.dmz_ragnarok.core.npcsel.dims", w, h);
        rowY = 30;
        boolean npc = NpcRegionCacheClient.canManage() && net.shurui.dev.sdu.api.ClientGate.key();
        // a right-dragged BOX can also become a server claim (op level, the private regions feature); a freeform
        // outline is NPC regions only
        boolean claim = outline == null && net.shurui.shuruisutilities.compat.client.NpcRegionMapSelect.claimsAllowed();

        if (npc)
            label("§e" + tr("gui.dmz_ragnarok.core.npcsel.add_to_existing"), 14, rowY);
        rowY += 12;

        List<String> names = npc ? NpcRegionCacheClient.namesIn(dim) : List.of();
        int listTop = rowY;
        int maxRows = rowsThatFit(listTop, LIST_ROW_H, claim ? CLAIM_ROWS_H : 0);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, names.size() - maxRows)));
        int end = Math.min(names.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = names.get(i);
            int ry = listTop + (i - scroll) * LIST_ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, LIST_ROW_H - 2, Component.literal("§f" + name),
                    () -> {
                        if (outline != null)
                            sendOutline("add", name);
                        else
                            run("npcregion addbox " + name + " " + x1 + " " + z1 + " " + x2 + " " + z2);
                    });
        }
        if (npc && names.isEmpty())
            label("§7" + tr("gui.dmz_ragnarok.core.npcsel.no_regions"), 14, listTop + 2);
        if (npc)
            scrollList(14, uiWidth, listTop, LIST_ROW_H, maxRows, names.size(), scroll,
                    v -> { scroll = v; rebuildWidgets(); });

        if (claim)
            claimRows(listTop + maxRows * LIST_ROW_H + 4);

        int by = footerY();
        if (npc)
            btn(UI_W / 2 - 104, by, 100, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.create_new")),
                    () -> {
                        if (outline != null)
                            sendOutline("create", "");
                        else
                            run("npcregion create " + x1 + " " + z1 + " " + x2 + " " + z2);
                    });
        btn(UI_W / 2 + 4, by, 100, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.npcsel.cancel"), this::onClose);
    }

    // height the server-claim rows take under the NPC region list
    private static final int CLAIM_ROWS_H = 40;
    // typed claim name, kept across rebuilds
    private String claimName = "";
    private String claimError = "";

    /**
     * The same box as a server claim: a name, then "make a new claim" (/serverclaim definexz) or "add as another
     * area of that claim" (/serverclaim addxz). Full height in the player's dimension, exactly like /serverclaim
     * define from a WorldEdit selection; the command keeps its own op-level permission and name checks.
     */
    private void claimRows(int y)
    {
        label("§b" + tr("gui.dmz_ragnarok.core.npcsel.claim_heading"), 14, y);
        y += 11;
        label(tr("gui.dmz_ragnarok.core.npcsel.claim_name"), 14, y + 3);
        rawField(80, y, 90, claimName, v -> claimName = v.trim());
        btn(176, y, 54, GuiTheme.BUTTON_HEIGHT, Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.claim_new")),
                () -> claimCommand("definexz"));
        btn(234, y, 54, GuiTheme.BUTTON_HEIGHT, Component.literal("§e").append(Component.translatable("gui.dmz_ragnarok.core.npcsel.claim_add")),
                () -> claimCommand("addxz"));
        tooltip(176, y, 112, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.core.npcsel.claim_tip"));
        if (!claimError.isEmpty())
            label("§c" + claimError, 14, y + 15);
    }

    private void claimCommand(String sub)
    {
        applyFields();
        // a claim name is one brigadier word; refuse anything else here rather than send a command that cannot parse
        if (!claimName.matches("[A-Za-z0-9_.+-]+"))
        {
            claimError = tr("gui.dmz_ragnarok.core.npcsel.claim_bad_name");
            rebuildWidgets();
            return;
        }
        run("serverclaim " + sub + " " + claimName + " " + x1 + " " + z1 + " " + x2 + " " + z2);
    }

    // the outline is too long for a chat command (128 points), so it rides the hub editor action instead
    private void sendOutline(String mode, String name)
    {
        java.util.List<String> args = new java.util.ArrayList<>(2 + outline.length);
        args.add(mode);
        args.add(name);
        for (int v : outline)
            args.add(Integer.toString(v));
        onClose();
        EditorScreens.act("npcregions", "outline", args);
    }

    private void run(String command)
    {
        onClose();
        EditorScreens.runCommand(command);
    }

    @Override
    public void onClose()
    {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
