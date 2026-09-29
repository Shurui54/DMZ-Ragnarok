package net.shurui.shuruisutilities.client.gui;

import java.util.Arrays;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.editor.BalanceScreen;
import net.shurui.shuruisutilities.client.gui.editor.BanItemScreen;
import net.shurui.shuruisutilities.client.gui.editor.ChatEditScreen;
import net.shurui.shuruisutilities.client.gui.editor.CrateEditScreen;
import net.shurui.shuruisutilities.client.gui.editor.TaskBoardScreen;
import net.shurui.shuruisutilities.client.gui.editor.TaskEditScreen;
import net.shurui.shuruisutilities.client.gui.editor.TaskListScreen;
import net.shurui.shuruisutilities.client.gui.editor.CrateListScreen;
import net.shurui.shuruisutilities.client.gui.editor.EconomyListScreen;
import net.shurui.shuruisutilities.client.gui.editor.HologramListScreen;
import net.shurui.shuruisutilities.client.gui.editor.HomesScreen;
import net.shurui.shuruisutilities.client.gui.editor.PortalEditScreen;
import net.shurui.shuruisutilities.client.gui.editor.PortalListScreen;
import net.shurui.shuruisutilities.client.gui.editor.ProtectionFlagsScreen;
import net.shurui.shuruisutilities.client.gui.editor.ProtectionListScreen;
import net.shurui.shuruisutilities.client.gui.editor.RegionEditScreen;
import net.shurui.shuruisutilities.client.gui.editor.RegionListScreen;
import net.shurui.shuruisutilities.client.gui.editor.WarpsScreen;
import net.shurui.shuruisutilities.client.gui.editor.WorldBorderScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hub.PacketEditorAction;
import net.shurui.shuruisutilities.hub.PacketEditorData;
import net.shurui.shuruisutilities.hub.PacketOpenEditor;

import net.minecraft.client.Minecraft;

// client dispatcher for the hub editors: turns a PacketEditorData into the matching list screen. edit
// sub-screens are opened from the list rows (drill-down), not routed here.
public final class EditorScreens
{
    private EditorScreens() {}

    // open/refresh the screen for the payload (client thread)
    public static void open(PacketEditorData p)
    {
        Minecraft mc = Minecraft.getInstance();
        switch (p.editor)
        {
            case "banitem" -> mc.setScreen(new BanItemScreen(p.rows));
            case "economy" -> mc.setScreen(new EconomyListScreen(
                    p.meta.isEmpty() ? "Zeni" : p.meta.get(0), p.rows));
            case "holograms" -> mc.setScreen(new HologramListScreen(p.meta, p.rows));
            case "warps" -> mc.setScreen(new WarpsScreen(p.meta, p.rows));
            case "homes" -> mc.setScreen(new HomesScreen(p.meta));
            case "balance" -> mc.setScreen(new BalanceScreen(p.meta));
            case "pvptoggle" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.PvpToggleScreen(p.meta));
            case "sparring" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.SparringScreen(p.meta));
            case "cosmetics" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticsScreen(p.meta));
            case "formcosmetics" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.FormCosmeticsScreen(p.meta, p.rows));
            case "bounty" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.BountyScreen(p.meta, p.rows));
            case "stafftasks" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.StaffTaskScreen(p.meta, p.rows));
            // cosmetics_admin is the catalogue list, cosmetic one definition, cosmetictracker the drill-down
            // from a definition's tracker row, wardrobe the player-facing screen.
            case "cosmetics_admin" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticListScreen(p.meta, p.rows));
            case "cosmetic" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticEditScreen(p.meta, p.rows));
            case "cosmetictracker" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticTrackerEditScreen(p.meta));
            case "wardrobe" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.WardrobeScreen(p.meta, p.rows));
            // The player mounts and animations screens, split out of the wardrobe into their own sections.
            case "cosmetic_mounts" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticMountsScreen(p.meta, p.rows));
            case "cosmetic_animations" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticAnimationsScreen(p.meta, p.rows));
            // cosmetic crate admin editor, its one-record screen, and the read-only keyless odds screen
            case "cosmetic_crates" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticCrateListScreen(p.meta, p.rows));
            case "cosmetic_crate" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.CosmeticCrateEditScreen(p.meta, p.rows));
            case "crate_odds" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.cosmetics.CrateOddsScreen(p.meta, p.rows));
            // shop listing admin editor, its one-record screen, and the player-facing shop
            case "cosmetic_shop" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.ShopListingListScreen(p.rows));
            case "cosmetic_shop_edit" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.ShopListingEditScreen(p.meta));
            case "shop" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.cosmetics.CosmeticShopScreen(p.meta, p.rows));
            case "crates" -> mc.setScreen(new CrateListScreen(p.rows));
            case "crate" -> mc.setScreen(new CrateEditScreen(p.meta, p.rows));
            case "tasks" -> mc.setScreen(new TaskListScreen(p.meta, p.rows));
            case "task" -> mc.setScreen(new TaskEditScreen(p.meta, p.rows));
            case "taskboard" -> mc.setScreen(new TaskBoardScreen(p.meta, p.rows));
            case "portals" -> mc.setScreen(new PortalListScreen(p.rows));
            case "portal" -> mc.setScreen(new PortalEditScreen(p.meta));
            case "worldborder" -> mc.setScreen(new WorldBorderScreen(p.meta));
            case "chat" -> mc.setScreen(new ChatEditScreen(p.meta, p.rows));
            case "protection" -> mc.setScreen(new ProtectionListScreen(p.rows));
            case "protectionflags" -> mc.setScreen(new ProtectionFlagsScreen(
                    p.meta.isEmpty() ? "" : p.meta.get(0), p.rows));
            case "regions" -> mc.setScreen(new RegionListScreen(p.rows));
            case "regionedit" -> mc.setScreen(new RegionEditScreen(p.meta, p.rows));
            case "npcregions" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.NpcRegionListScreen(p.rows));
            case "shrines" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.ShrineListScreen(p.rows));
            case "shrine" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.ShrineEditScreen(p.meta, p.rows));
            case "hoverbikes" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.HoverbikeScreen(p.meta));
            case "saibamanpets" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.SaibamanPetScreen(p.meta));
            case "sparringsettings" -> mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.SparringSettingsScreen(p.meta));
            // The player event hub. Gated on the private "events" feature: an unkeyed server never sends this, and a
            // client that somehow received it without the feature draws nothing rather than a half-wired screen.
            case "eventhub" -> { if (net.shurui.dev.sdu.api.ClientGate.feature("events"))
                    mc.setScreen(new net.shurui.shuruisutilities.client.gui.editor.EventHubScreen(p.meta, p.rows)); }
            default -> { }
        }
    }

    // re-open the admin editor hub (from an editor's Menu button); server re-checks perm
    public static void openAdminHub()
    {
        // with sdu present, Menu returns to the shared sdu hub instead of SU's (guarded compat)
        if (net.shurui.shuruisutilities.compat.sdu.SduHubCompat.openSduHub())
            return;
        NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor("menu:admin"));
    }

    // re-open the player tool hub (from a Menu button or the keybind)
    public static void openPlayerHub()
    {
        NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor("menu"));
    }

    // run a server command from a button (unsigned)
    public static void runCommand(String command)
    {
        if (Minecraft.getInstance().getConnection() != null)
            Minecraft.getInstance().getConnection().sendCommand(command);
    }

    // send an edit action to the server
    public static void act(String editor, String action, String... args)
    {
        NetworkUtils.sendToServer(new PacketEditorAction(editor, action, Arrays.asList(args)));
    }

    // same, args already a list
    public static void act(String editor, String action, List<String> args)
    {
        NetworkUtils.sendToServer(new PacketEditorAction(editor, action, args));
    }

    // ask the server to (re)send a list editor's data, e.g. from a Back button
    public static void reopen(String editor)
    {
        NetworkUtils.INSTANCE.sendToServer(new net.shurui.shuruisutilities.hub.PacketOpenEditor(editor));
    }
}

