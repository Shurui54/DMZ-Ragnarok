package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;

/**
 * Server-side router for the generic hub editors ({@link PacketEditorData} / {@link PacketEditorAction}).
 * {@link #open} gathers a module's state into a PacketEditorData and pushes it; {@link #handle} applies an edit
 * through that module's manager and re-sends. Op-gating happens in the packet before this.
 *
 * <p>An editor that belongs to a feature is not built here but by that feature's {@code HubRow<Feature>}, looked
 * up by key through {@link HubRows}. This class keeps only the public editors (hoverbikes, the Patreon cosmetics
 * actions, form cosmetics) and the event engine's hooks.
 */
public final class EditorServer
{
    private EditorServer() {}

    // build + send the current state of editor (opens/refreshes the screen)
    public static void open(ServerPlayer player, String editor)
    {
        switch (editor)
        {
            case "hoverbikes" -> send(player, "hoverbikes", hoverbikeMeta(), new ArrayList<>());
            // The player-facing event hub. Delegated to the private event engine (Ragnarok Key) through its inert
            // core hook, so core carries no event logic; keyless the hook is a no-op and nothing opens.
            case "eventhub" -> net.shurui.shuruisutilities.api.key.EventWorldHooks.get().openHub(player);
            // The admin event editor (E11). Delegated to the private engine, which sends the packet-128 list (or a
            // single event) itself; keyless the hook is a no-op and nothing opens.
            case "events" -> net.shurui.shuruisutilities.api.key.EventWorldHooks.get().openEditor(player, "");
            // every feature editor: built by its registered HubRows handler
            default -> HubRows.editorOpen(player, editor);
        }
    }

    public static void handle(ServerPlayer player, String editor, String action, List<String> args)
    {
        switch (editor)
        {
            case "hoverbikes" -> handleHoverbikes(action, args);
            case "cosmetics" -> { handleCosmetics(player, action, args); return; } // player tool; drives the Patreon link flow
            // The event hub (claim a quest, buy a shop offer). The key handler self-refreshes the screen.
            case "eventhub" -> { net.shurui.shuruisutilities.api.key.EventWorldHooks.get()
                    .hubAction(player, action, args.isEmpty() ? "" : args.get(0)); return; }
            // The admin event editor list/edit actions (open, new, delete). The key sends the refreshed packet 128
            // itself, so this returns without the generic refresh below.
            case "events" -> { net.shurui.shuruisutilities.api.key.EventWorldHooks.get()
                    .editorAction(player, action, args.isEmpty() ? "" : args.get(0)); return; }
            // every feature editor: its registered HubRows handler applies the action and says whether it already
            // re-sent its own screen (return) or wants the generic refresh below
            default -> { if (HubRows.action(player, editor, action, args)) return; }
        }
        open(player, editor); // refresh
    }

    private static void send(ServerPlayer player, String editor, List<String> meta, List<List<String>> rows)
    {
        HubRows.send(player, editor, meta, rows);
    }

    /**
     * How many fixed fields a hologram row carries before its text lines start: name, dimension, X, Y, Z and the
     * fifteen style options the editor screen shows.
     *
     * <p>These are POSITIONAL, so a new field goes on the end of the fixed block and never in the middle: the
     * screen and {@code HubRowHologram.applyEditedStyle} read the same indices and the lines start wherever this
     * says they do.
     */
    public static final int HOLO_FIXED = 20;

    // cosmetics: the "patreonlink" action starts the sender's OWN Patreon link flow through the existing manager
    // (the same path /patreon link uses), which posts a clickable link to chat. Re-validated here: no-op unless the
    // Patreon backend is configured, so a client that spoofs the action on an unconfigured server does nothing. No
    // refresh push: the client already closed the screen so the chat link is visible.
    private static void handleCosmetics(ServerPlayer player, String action, List<String> args)
    {
        switch (action)
        {
            case "patreonlink":
                if (!net.shurui.shuruisutilities.patreon.PatreonManager.isConfigured())
                    return;
                // Only a keyed server can mint a code; anywhere else the client opened the browser itself using the
                // public start URL in the menu's meta, so this action is not even sent. Handle both anyway.
                if (net.shurui.shuruisutilities.patreon.PatreonManager.isServerKeyed())
                    net.shurui.shuruisutilities.patreon.PatreonManager.startLink(player);
                else
                    net.shurui.shuruisutilities.patreon.PatreonManager.sendStartInstructions(player);
                return;
            case "patreonclaim":
                // Claim code typed into the Cosmetics menu. The code is the player's own proof from the browser; the
                // backend validates it, so nothing here trusts the client beyond "this player typed this string".
                if (net.shurui.shuruisutilities.patreon.PatreonManager.isConfigured() && args != null && !args.isEmpty())
                    net.shurui.shuruisutilities.patreon.PatreonManager.claim(player, args.get(0));
                return;
            case "formsave":
                handleFormCosmeticSave(player, args);
                return;
            case "formclear":
                // entitlement not required to clear (a lapsed supporter may still remove their old styling)
                net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.clear(player);
                HubServer.tryOpen(player, "formcosmetics");
                return;
            default:
                return;
        }
    }

    // formsave args: [group, form, body1, body2, body3, hair, eye1, eye2, aura, extra, tint, tintIntensity,
    //                 outlineEnabled, outlinePrimary, outlineSecondary, outlineThickness]. The server rebuilds the
    // appearance-only record, re-checks entitlement and the target form, sanitizes and stores it, then re-sends the
    // editor with fresh data. A client that spoofs this on an unentitled account is rejected by the manager.
    /** Optional positional arg, or "" when the client did not send one (older client, shorter list). */
    private static String arg(List<String> args, int i)
    {
        return args != null && i < args.size() && args.get(i) != null ? args.get(i) : "";
    }

    private static void handleFormCosmeticSave(ServerPlayer player, List<String> args)
    {
        if (args == null || args.size() < 16)
            return;
        net.shurui.shuruisutilities.cosmetics.form.FormCosmetic c =
                new net.shurui.shuruisutilities.cosmetics.form.FormCosmetic();
        c.group = args.get(0);
        c.form = args.get(1);
        c.bodyColor1 = args.get(2);
        c.bodyColor2 = args.get(3);
        c.bodyColor3 = args.get(4);
        c.hairColor = args.get(5);
        c.eye1Color = args.get(6);
        c.eye2Color = args.get(7);
        c.auraColor = args.get(8);
        c.extraFormColor = args.get(9);
        c.tintColor = args.get(10);
        c.tintIntensity = parseDoubleSafe(args.get(11));
        c.outlineEnabled = Boolean.parseBoolean(args.get(12));
        c.outlinePrimary = args.get(13);
        c.outlineSecondary = args.get(14);
        c.outlineThickness = parseDoubleSafe(args.get(15));
        // id-string channels, optional so an older client that sends 16 args still saves its colours. Whatever
        // arrives here is only a request: FormCosmeticManager.save re-checks each one against the values DMZ
        // actually ships and drops anything else, so a spoofed packet cannot put a broken id on a model.
        c.hairType = arg(args, 16);
        c.hairCode = arg(args, 17);
        c.customModel = arg(args, 18);
        c.extraFormLayer = arg(args, 19);
        c.auraType = arg(args, 20);
        net.shurui.shuruisutilities.cosmetics.form.FormCosmeticManager.save(player, c);
        HubServer.tryOpen(player, "formcosmetics");
    }

    private static double parseDoubleSafe(String s)
    {
        try
        {
            return Double.parseDouble(s);
        }
        catch (Exception e)
        {
            return 0.0;
        }
    }

    // meta = [speed1..4, sprintMultiplier, soundEnabled] (current baked values)
    private static List<String> hoverbikeMeta()
    {
        return List.of(
                Double.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.speed[1]),
                Double.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.speed[2]),
                Double.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.speed[3]),
                Double.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.speed[4]),
                Double.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.sprintMultiplier),
                Boolean.toString(net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.soundEnabled));
    }

    // save speed1..4 sprintMultiplier soundEnabled; clamps + persists + re-bakes live
    private static void handleHoverbikes(String action, List<String> args)
    {
        if (!"save".equals(action) || args.size() < 6)
            return;
        double[] speed = new double[5];
        for (int v = 1; v <= 4; v++)
            speed[v] = parseD(args.get(v - 1));
        double sprint = parseD(args.get(4));
        boolean sound = Boolean.parseBoolean(args.get(5));
        net.shurui.shuruisutilities.hoverbike.ConfigHoverbikes.applyAndSave(speed, sprint, sound);
    }

    private static double parseD(String s)
    {
        return HubRows.parseD(s);
    }
}
