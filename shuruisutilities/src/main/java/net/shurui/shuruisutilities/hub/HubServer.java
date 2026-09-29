package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.server.level.ServerPlayer;

/**
 * Authoritative router + permission gate for both hub menus. Admin hub (/rggui admin, /rginfo edit) exposes the
 * config editors; player hub (/rggui, keybind) exposes player tools. The SERVER decides which entries the
 * player may see (by permission) and sends only those; every open request is re-checked here before pushing.
 *
 * <p>The rows a feature owns are not handled here but in that feature's {@code HubRow<Feature>}, looked up by
 * key through {@link HubRows}. This class keeps the row lists, their order and labels, the keyless allow-list and
 * the public rows.
 */
public final class HubServer
{
    private HubServer() {}

    // hub-level gates
    public static final String GUI_ADMIN = "su.gui.admin";
    public static final String GUI_MENU = "su.gui.menu";
    // shadow-dragon boss/arena editor (registered in CommandSUGui). neutral label; feature is deliberately low-key.
    public static final String GUI_SHADOWDRAGONS = "su.gui.shadowdragons";

    // admin editors: {which, perm node, label key}. Shown when the player is op or has the node.
    private static final String[][] ADMIN = {
            {"permissions", "su.gui.permissions", "gui.dmz_ragnarok.core.hub.permissions"},
            {"guilds", "su.gui.guilds", "gui.dmz_ragnarok.core.hub.guilds"},
            {"economy", "su.gui.economy", "gui.dmz_ragnarok.core.hub.economy"},
            {"holograms", "su.gui.holograms", "gui.dmz_ragnarok.core.hub.holograms"},
            {"crates", "su.gui.crates", "gui.dmz_ragnarok.core.hub.crates"},
            {"events", "su.gui.events", "gui.dmz_ragnarok.core.hub.events"},
            {"portals", "su.gui.portals", "gui.dmz_ragnarok.core.hub.portals"},
            {"worldborder", "su.gui.worldborder", "gui.dmz_ragnarok.core.hub.worldborder"},
            {"chat", "su.gui.chat", "gui.dmz_ragnarok.core.hub.chat"},
            {"regions", "su.gui.regions", "gui.dmz_ragnarok.core.hub.regions"},
            {"npcregions", "su.gui.npcregions", "gui.dmz_ragnarok.core.hub.npcregions"},
            {"banitem", "su.gui.banitem", "gui.dmz_ragnarok.core.hub.banitem"},
            {"prestige", "su.gui.prestige", "gui.dmz_ragnarok.core.hub.prestige"},
            {"shrines", "su.gui.shrines", "gui.dmz_ragnarok.core.hub.shrines"},
            {"tasks", "su.gui.tasks", "gui.dmz_ragnarok.core.hub.tasks"},
            {"hoverbikes", "su.gui.hoverbikes", "gui.dmz_ragnarok.core.hub.hoverbikes"},
            {"saibamanpets", "su.gui.saibamanpets", "gui.dmz_ragnarok.core.hub.saibamanpets"},
            {"sparringsettings", "su.gui.sparringsettings", "gui.dmz_ragnarok.core.hub.sparringsettings"},
            {"shadowdragons", GUI_SHADOWDRAGONS, "gui.dmz_ragnarok.core.hub.shadowdragons"},
            {"cosmetics_admin", "su.gui.cosmetics", "gui.dmz_ragnarok.core.hub.cosmetics_admin"},
            {"cosmetic_crates", "su.gui.cosmetics", "gui.dmz_ragnarok.core.hub.cosmetic_crates"},
            {"cosmetic_shop", "su.gui.cosmetics", "gui.dmz_ragnarok.core.hub.cosmetic_shop"},
    };

    // player tools: {which, label key}, in menu order. Whether each is listed is its feature row's decision
    // (HubRows.listed), except the public cosmetics menu, which is always listed.
    private static final String[][] PLAYER = {
            {"guild", "gui.dmz_ragnarok.core.menu.guild"},
            {"warps", "gui.dmz_ragnarok.core.menu.warps"},
            {"homes", "gui.dmz_ragnarok.core.menu.homes"},
            {"taskboard", "gui.dmz_ragnarok.tasks.title"},
            {"balance", "gui.dmz_ragnarok.core.menu.balance"},
            {"pvptoggle", "gui.dmz_ragnarok.core.menu.pvptoggle"},
            {"cosmetics", "gui.dmz_ragnarok.core.menu.cosmetics"},
            {"bounty", "gui.dmz_ragnarok.core.menu.bounty"},
            {"auction", "gui.dmz_ragnarok.core.menu.auction"},
            {"stafftasks", "gui.dmz_ragnarok.core.menu.stafftasks"},
    };

    /**
     * The hub keys a server WITHOUT the key still offers (OWNER-SPECS section 4: /rggui shows only public rows
     * keyless). An allow-list, like PublicContent: a row added tomorrow is private until somebody lists it here.
     * The cosmetics menu (Patreon) and its sub screens stay public; each of those sub screens keeps its own gate
     * below, which is what decides whether it actually opens. Hoverbikes is public since 2.0. With the key this set
     * is never consulted, so a keyed server's menus are exactly as before.
     */
    private static final java.util.Set<String> KEYLESS_PUBLIC = java.util.Set.of(
            "menu", "menu:admin",
            "cosmetics", "formcosmetics", "wardrobe", "cosmetic_mounts", "cosmetic_animations", "shop",
            "hoverbikes");

    /**
     * Admin editors this class opens itself (see {@link #tryOpen}) rather than through a registered {@link HubRows}
     * row. Every other admin row is listed only when its feature registered a row.
     */
    private static final java.util.Set<String> SELF_OPENED = java.util.Set.of("events", "hoverbikes");

    /** Whether this hub key may be listed or opened here: always with the key, the public set without it. */
    private static boolean offered(String which)
    {
        return net.shurui.shuruisutilities.KeyGate.unlocked() || KEYLESS_PUBLIC.contains(which);
    }

    public static boolean has(ServerPlayer p, String node)
    {
        try
        {
            return APIRegistry.perms != null && APIRegistry.perms.checkPermission(p, node);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    // may open the admin hub at all (op, or GUI_ADMIN)
    public static boolean isAdmin(ServerPlayer p)
    {
        return p.hasPermissions(2) || has(p, GUI_ADMIN);
    }

    /** May open the admin editor gated on {@code node}: op, or holding the node. */
    public static boolean adminEditor(ServerPlayer p, String node)
    {
        return p.hasPermissions(2) || has(p, node);
    }

    public static void openAdmin(ServerPlayer p)
    {
        if (!isAdmin(p))
            return;
        List<String> which = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String[] e : ADMIN)
        {
            // keyless: only the public editors are listed (see KEYLESS_PUBLIC)
            if (!offered(e[0]))
                continue;
            // only rows something will actually open: a feature row the key (or core) registered, or an editor this
            // class opens itself. A jar that merely claims to be the key registers no rows, so it lists nothing extra.
            if (!SELF_OPENED.contains(e[0]) && !HubRows.has(e[0]))
                continue;
            // hide prestige when the prestige feature is disabled
            if (e[0].equals("prestige") && !net.shurui.shuruisutilities.core.config.Features.enabled(
                    net.shurui.shuruisutilities.core.config.Features.PRESTIGE))
                continue;
            // hide the event editor unless the private event engine (Ragnarok Key) is installed: a row that opens
            // a screen the keyless server never populates is worse than no row.
            if (e[0].equals("events") && !net.shurui.shuruisutilities.api.key.EventWorldHooks.available())
                continue;
            // hide the cosmetic catalogue, crate and shop editors when the module is off or the key tier is not
            // entitled to it: a row that opens a screen refusing to do anything is worse than no row.
            if ((e[0].equals("cosmetics_admin") || e[0].equals("cosmetic_crates") || e[0].equals("cosmetic_shop"))
                    && !HubRows.available(e[0]))
                continue;
            if (adminEditor(p, e[1]))
            {
                which.add(e[0]);
                labels.add(e[2]);
            }
        }
        NetworkUtils.sendTo(new PacketOpenHub(true, which, labels), p);
    }

    public static void openPlayer(ServerPlayer p)
    {
        // Keyless, every private row is dropped as it is built (see KEYLESS_PUBLIC); the lists are filtered once at
        // the end rather than at each of the rows below, so the keyed path is untouched.
        List<String> which = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String[] e : PLAYER)
        {
            // cosmetics is core, always available; currently just the Patreon account-link entry. Every other row
            // is listed by its feature's registered row (HubRows), which holds the gate that used to sit here.
            boolean listed = e[0].equals("cosmetics") || HubRows.listed(p, e[0]);
            if (listed)
            {
                which.add(e[0]);
                labels.add(e[1]);
            }
        }
        for (int i = which.size() - 1; i >= 0; i--)
        {
            if (!offered(which.get(i)))
            {
                which.remove(i);
                labels.remove(i);
            }
        }
        NetworkUtils.sendTo(new PacketOpenHub(false, which, labels), p);
    }

    public static void tryOpen(ServerPlayer p, String which)
    {
        // Re-checked here, not only where the rows are built: the client sends the key by name, so a crafted packet
        // must not reach a private editor on a server without the key.
        if (!offered(which))
            return;
        switch (which)
        {
            case "menu" -> openPlayer(p);
            case "menu:admin" -> openAdmin(p);
            // admin editors
            // Re-checked here, not just where the row is built: the client sends this key by name, so hiding the
            // row alone would leave the screen reachable by a crafted packet.
            case "events" -> {
                if (net.shurui.shuruisutilities.api.key.EventWorldHooks.available()
                        && adminEditor(p, "su.gui.events"))
                    EditorServer.open(p, "events");
            }
            case "hoverbikes" -> { if (adminEditor(p, "su.gui.hoverbikes")) EditorServer.open(p, "hoverbikes"); }
            // player tools
            case "cosmetics" -> openCosmetics(p);
            case "formcosmetics" -> openFormCosmetics(p);
            // every feature row (admin editors and player tools alike): its registered HubRows handler does the
            // permission re-check that used to sit here, then opens
            default -> HubRows.hubOpen(p, which);
        }
    }

    // cosmetics menu. meta = [patreonConfigured, currentTierDisplayName, hasFormCosmeticReward]. The screen surfaces
    // the EXISTING Patreon link flow as a button (the button drives the same server-side start-link path
    // /patreon link uses); the tier is sent only so the button can read "Relink" once a player is linked. The form
    // cosmetic flag is the server's grace-aware entitlement decision (never a client claim), so the client only ever
    // ENABLES the form-cosmetics entry when the server says the player qualifies. No API key or backend detail
    // crosses the wire: only the booleans/display name below.
    private static void openCosmetics(ServerPlayer p)
    {
        boolean configured = net.shurui.shuruisutilities.patreon.PatreonManager.isConfigured();
        String tier = net.shurui.shuruisutilities.patreon.PatreonManager.effectiveTier(p.getGameProfile().getId());
        String tierDisplay = (tier == null || tier.isEmpty())
                ? "" : net.shurui.shuruisutilities.patreon.PatreonAPI.displayName(tier);
        boolean formCosmetic = net.shurui.shuruisutilities.patreon.PatreonAPI.hasReward(
                p.getGameProfile().getId(), net.shurui.shuruisutilities.patreon.PatreonAPI.REWARD_FORM_COSMETIC);
        // meta[3] is the backend BASE url. Safe to send: it is a plain URL with no secret in it. The client uses it
        // to run the one-click flow itself (prove the account to Mojang, trade that for a personal link, open the
        // browser), which is why the menu works the same on every server whether or not it holds the API key.
        String baseUrl = net.shurui.shuruisutilities.patreon.PatreonManager.backendBaseUrl();
        // meta[4], APPENDED not inserted: whether the wardrobe is available here, so the menu can offer it. The
        // screen reads defensively past the end of meta, so an older client simply does not see the button.
        // Append only, never reorder: indices 0 to 3 are a contract with every client already in the wild.
        // meta[5..7] are the mounts, animations and shop availability, appended the same way and for the same
        // reason: an older client that stops reading at meta[4] simply lacks those three buttons.
        List<String> meta = List.of(Boolean.toString(configured), tierDisplay, Boolean.toString(formCosmetic),
                baseUrl,
                // S17p: without the key the wardrobe is the public Patreon wardrobe, offered only to a player who
                // holds a Patreon cosmetic; with the key offeredTo is always true, so this is the row flag as before.
                Boolean.toString(HubRows.available("wardrobe")
                        && net.shurui.shuruisutilities.cosmetics.wardrobe.WardrobeManager.offeredTo(p)),
                Boolean.toString(HubRows.available("cosmetic_mounts")),
                Boolean.toString(HubRows.available("cosmetic_animations")),
                Boolean.toString(HubRows.available("shop")));
        NetworkUtils.sendTo(new PacketEditorData("cosmetics", meta, new ArrayList<>()), p);
    }

    // single-form cosmetic editor. Entitlement is re-checked server-side here (never trusted from the client): if the
    // player does not currently qualify, we bounce them back to the cosmetics menu instead of opening the editor. The
    // editor is a supporter styling their OWN one form, so it re-validates on save too (see EditorServer). meta carries
    // the current override; rows are the forms this player's race actually has, so they can only style a real form.
    // meta = [entitled, group, form, body1, body2, body3, hair, eye1, eye2, aura, extra, tint, tintIntensity,
    //         outlineEnabled, outlinePrimary, outlineSecondary, outlineThickness].
    private static void openFormCosmetics(ServerPlayer p)
    {
        if (!net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.entitled(p))
        {
            openCosmetics(p);
            return;
        }
        net.shurui.shuruisutilities.cosmetics.form.FormCosmetic c =
                net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.current(p);
        List<String> meta = new ArrayList<>();
        meta.add("true");
        meta.add(c.group);
        meta.add(c.form);
        meta.add(c.bodyColor1);
        meta.add(c.bodyColor2);
        meta.add(c.bodyColor3);
        meta.add(c.hairColor);
        meta.add(c.eye1Color);
        meta.add(c.eye2Color);
        meta.add(c.auraColor);
        meta.add(c.extraFormColor);
        meta.add(c.tintColor);
        meta.add(Double.toString(c.tintIntensity));
        meta.add(Boolean.toString(c.outlineEnabled));
        meta.add(c.outlinePrimary);
        meta.add(c.outlineSecondary);
        meta.add(Double.toString(c.outlineThickness));
        // current id-string values (17..21), then the legal options for each (22..26) as unit-separated lists. The
        // client only renders what it is sent; it never decides what is legal, and save() re-checks anyway.
        meta.add(c.hairType);
        meta.add(c.hairCode);
        meta.add(c.customModel);
        meta.add(c.extraFormLayer);
        meta.add(c.auraType);
        String race = net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.raceOf(p);
        for (List<String> opts : net.shurui.shuruisutilities.cosmetics.form.FormAppearanceOptions.allLists(race))
            meta.add(String.join("", opts));
        List<List<String>> rows = net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.availableForms(p);
        NetworkUtils.sendTo(new PacketEditorData("formcosmetics", meta, rows), p);
    }
}
