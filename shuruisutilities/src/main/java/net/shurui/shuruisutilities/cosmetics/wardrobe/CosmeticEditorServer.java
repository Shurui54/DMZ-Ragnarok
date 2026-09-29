package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hub.PacketEditorData;

/**
 * Server side of the cosmetic editors, in the SU hub editor framework.
 *
 * <h2>Why this rides the generic editor packets</h2>
 * The suite has two editor mechanisms. sdu's content editors (race, form, saga, wish, NPC) each ship their own
 * open, save and delete packets with a JSON bundle on the wire. SU's hub editors share ONE server to client
 * packet ({@code PacketEditorData}) and ONE client to server packet ({@code PacketEditorAction}), whose own
 * JavaDoc says "Every new editor reuses this one packet instead of adding its own, keeping the network surface
 * small". The cosmetics entry point already lives in the hub framework ({@code CosmeticsScreen} is routed by
 * {@code EditorScreens} as the {@code "cosmetics"} editor), so building this on the hub framework REUSES a path
 * and adds zero editor packets, while building it on sdu's would be a second parallel mechanism for the same job.
 *
 * <p>The closest existing shape is the crate editor: a named record with a typed header and a list of typed
 * sub-records, edited as list screen plus record screen plus sub-record add, edit and delete, where an edit
 * REPLACES at an index. The task editor supplies the rest (an id that is deliberately not editable, a scalar
 * save whose argument order is a contract with one screen). This copies both.
 *
 * <p>Kept out of {@code EditorServer} itself and reached by delegation, exactly as the auction, trade and staff
 * board already are, so the cosmetics knowledge lives with the cosmetics code and that shared 1500-line router
 * grows by two lines rather than three hundred.
 *
 * <h2>What stays here (S17a)</h2>
 * The player WARDROBE ({@link #openWardrobe}, {@link #handleWardrobe}) stays in core because the public Patreon
 * wardrobe (S17p) needs it without the Ragnarok Key, and so does every wire constant the client screens read. The
 * admin catalogue editor and the mounts and animations screens moved into the Ragnarok Key
 * (its {@code CosmeticAdminEditor}), which reuses {@link #copyRows} and these
 * constants so each wire contract keeps one definition.
 *
 * <h2>The three wire contracts</h2>
 * Each is an ORDER, agreed with one client screen, and each is documented where it is built. Append, never
 * reorder: an older client reads defensively past the end, but a reordered field is silently the wrong value.
 */
public final class CosmeticEditorServer
{
    private CosmeticEditorServer()
    {
    }

    /** The admin list editor key, routed by {@code EditorScreens}. */
    public static final String EDITOR_LIST = "cosmetics_admin";

    /** One definition's editor key. */
    public static final String EDITOR_ONE = "cosmetic";

    /** One tracker's editor key (a drill-down from a definition's tracker rows). */
    public static final String EDITOR_TRACKER = "cosmetictracker";

    /** The player-facing wardrobe key. */
    public static final String EDITOR_WARDROBE = "wardrobe";

    /**
     * How many arguments the edit screen's SAVE carries before anything variable.
     *
     * <p>A contract with one screen, and an ORDER: append, never reorder. An older client reads defensively past
     * the end, but a reordered field is silently the wrong value.
     */
    public static final int META_FIXED = 25;

    /**
     * Where the fixed part of the OUTGOING {@code meta} ends, and the other cosmetics' ids begin.
     *
     * <p>Two less than {@link #META_FIXED}, and they are genuinely different numbers rather than a duplicate:
     * the outgoing meta carries the record's 23 fields, while the returning save carries those 23 plus the
     * default effect pool AND the event, and the params tail starts after the 23. Reading the tail from the save
     * constant used to drop the FIRST id out of the copy-from dropdown, silently. The pool and the event are
     * appended at the END of the save so they never move this tail, exactly as the pool did before the event.
     */
    public static final int META_OTHERS = 23;

    /** Row marker: this row is a tracker. */
    public static final String ROW_TRACKER = "t";

    /** Row marker: this row is a grant command. */
    public static final String ROW_COMMAND = "c";

    /** Row marker: this row is the definition's default effect pool id. Exactly one row, possibly blank. */
    public static final String ROW_POOL = "p";

    /** Row marker: this row is the definition's current event (collection) name. Exactly one row, possibly blank. */
    public static final String ROW_EVENT = "ev";

    /**
     * Row marker: {@code [hh, trueOrFalse]}, whether an enclosing HEAD cosmetic hides DragonMineZ's hair. Sent as
     * a ROW rather than in {@code meta} so adding it did not move the copy-from tail the edit screen reads from
     * {@link #META_OTHERS}, exactly as the pool and event did. Absent on an older server, which reads as false.
     */
    public static final String ROW_HIDEHAIR = "hh";

    /** Row marker: one existing event name, for the event dropdown. One row per distinct event in the catalogue. */
    public static final String ROW_EVENTOPT = "evo";

    /**
     * Row marker: the accessory render style, {@code [as, styleKey]}. Exactly one row. Sent for every definition
     * (the edit screen only shows it on an accessory-slot cosmetic) carrying the def's own style key. A row rather
     * than a meta field so adding it did not move the copy-from tail the edit screen reads from {@link #META_OTHERS},
     * the same reasoning as the mount movement and the hide-hair flag. Absent on an older server, which reads as
     * carried.
     */
    public static final String ROW_ACCSTYLE = "as";

    /**
     * Row marker: the Patreon tier gate, {@code [pt, tierIdOrBlank]}. Exactly one row; blank means not a Patreon
     * cosmetic (see {@link CosmeticDef#patreonTier}). A row for the same tail-preserving reason as the accessory
     * style. Absent on an older server, which reads as blank.
     */
    public static final String ROW_PATREON = "pt";

    /** Row marker: one Patreon tier option, {@code [pto, tierId, displayName]}, lowest tier first. */
    public static final String ROW_PATREONOPT = "pto";

    /**
     * Row marker: the mount movement, {@code [mo, flyingBool, speed]}. Exactly one row. Sent for every definition
     * (the edit screen only shows it on a mount-slot cosmetic) carrying the EFFECTIVE movement: the def's own
     * sub-record if it has one, else the seeded code-table default for the id, else a ground default. A row rather
     * than a meta field so adding it did not move the copy-from tail the edit screen reads from {@link #META_OTHERS},
     * the same reasoning as the default pool and the event.
     */
    public static final String ROW_MOUNT = "mo";

    /**
     * Row marker: one item id that may be picked as a cosmetic model. One row per entry of the item tag
     * {@code dmz_ragnarok:cosmetic_models}. Carries the ITEM ID only; the client resolves the display name and
     * icon from its own registry, so the option list is properly localised and never shows a raw id when the item
     * is present. The tag is the single, data-driven source: adding an item to it is how a new model appears here,
     * with no code change and no client edit.
     */
    public static final String ROW_MODEL = "m";

    /** Row marker: one effect pool option, {@code [po, poolId, displayName]}, for the default-pool dropdown. */
    public static final String ROW_POOLOPT = "po";


    // ------------------------------------------------------------- player: the wardrobe

    /**
     * The player's own wardrobe.
     *
     * <p>{@code meta} is flat pairs of {@code slotKey, equippedInstanceIdOrBlank}, one pair per ACTIVE slot, so
     * the screen never has to know which slots exist and a slot declared later simply appears. The INSTANCE is
     * sent rather than the catalogue id because the screen has to mark the exact copy being worn, and the
     * catalogue id is already on the matching row.
     *
     * <p>{@code rows} are ONE PER LIVE COPY the player owns and could wear:
     * {@code [catalogId, displayName, slotKey, qualityKey, trackerCount, chosenTrackerId, rarity, instanceId,
     * effectId, shownCount, effectName]}.
     *
     * <h2>Per copy, not per cosmetic</h2>
     * This used to be one row per cosmetic carrying the BEST copy's quality, which made a Super hat with 412
     * kills and a plain one of the same hat a single, unpickable line. A copy is the thing a player values, so
     * the copy is the row, and {@code equip} now carries the instance it was built from. A player who owns one
     * copy of everything sees exactly the list they saw before.
     *
     * <p>{@code shownCount} is the count of the tracker the player chose to display, or the highest count on the
     * copy when they have not chosen one, so a Super item always shows a number rather than showing nothing
     * until somebody discovers the counter control.
     *
     * <p>Rows come from the ledger, server side, and an equip request is re-checked against the same ledger, so
     * the screen is a view of the server's answer rather than a source of truth. A client that added a row to
     * its own copy would get a refusal on the button press.
     */
    public static void openWardrobe(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !WardrobeManager.wearActive())
            return;
        // Without the key (Patreon-only) the screen shows only what that mode may touch: the Patreon copies, in the
        // worn body slots, with anything else a stored outfit holds left out. With the key this is the outfit as is.
        PlayerWardrobe w = WardrobeManager.visible(server, WardrobeManager.current(player));
        // WORN slots only. The mount and the triggered animations are their own screens now, reached from the
        // cosmetics menu, so the wardrobe holds only what is drawn on the body. Derived from CosmeticSlot data
        // ({@link #isWornSlot}), never a hardcoded list, so a new worn slot appears and a restructured trigger set
        // drops out with no edit here.
        List<String> meta = new ArrayList<>();
        for (CosmeticSlot slot : CosmeticSlot.active())
        {
            if (!isWornSlot(slot) || (WardrobeManager.patreonOnly()
                    && !net.shurui.shuruisutilities.patreon.PatreonWardrobe.publicSlot(slot)))
                continue;
            meta.add(slot.key);
            EquippedCosmetic worn = w.worn(slot);
            meta.add(worn == null || worn.instanceId == null ? "" : worn.instanceId.toString());
        }
        send(player, EDITOR_WARDROBE, meta, copyRows(player, server, w, CosmeticEditorServer::isWornSlot));
    }

    /** A WORN wardrobe slot: enabled, drawn on the body, and not the mount, which has its own screen. */
    private static boolean isWornSlot(CosmeticSlot slot)
    {
        return slot != null && slot.enabled && !slot.triggered() && slot != CosmeticSlot.MOUNT;
    }

    /**
     * The player's owned copies, one row each in the wardrobe wire shape, for every slot whose slot passes
     * {@code slotFilter}. Shared by the wardrobe, the mounts screen and the animations screen so all three read
     * one row contract and {@code OwnedCopy.parse} on the client is enough for every one of them.
     *
     * <p>The ledger is walked ONCE and grouped, not once per cosmetic: it is every row on the server, and a player
     * with a full wardrobe would otherwise scan it a few hundred times to open a screen. Best copy first, so a
     * stale client that still equips by catalogue id lands on the copy {@code bestInstance} would have picked.
     */
    public static List<List<String>> copyRows(ServerPlayer player, MinecraftServer server, PlayerWardrobe w,
            java.util.function.Predicate<CosmeticSlot> slotFilter)
    {
        List<List<String>> rows = new ArrayList<>();
        Set<String> owned = WardrobeManager.wearableOwned(player);
        CosmeticLedgerData ledger = WardrobeManager.ledger(server);
        Map<String, List<CosmeticOwnership>> byCosmetic = new java.util.HashMap<>();
        for (CosmeticOwnership row : ledger.rowsOf(player.getUUID()))
            if (row != null && row.catalogId != null && WardrobeManager.wearableRow(row))
                byCosmetic.computeIfAbsent(row.catalogId, k -> new ArrayList<>()).add(row);
        for (String id : owned)
        {
            CosmeticDef d = CosmeticCatalog.get(id);
            if (d == null || !slotFilter.test(d.slot))
                continue;
            String chosen = w.tracker(d.id);
            List<CosmeticOwnership> copies = new ArrayList<>(byCosmetic.getOrDefault(d.id, List.of()));
            copies.sort((a, b) -> Integer.compare(rank(b), rank(a)));
            for (CosmeticOwnership row : copies)
                rows.add(List.of(d.id, nz(d.displayName), d.slot.key,
                        (row.quality == null ? CosmeticQuality.NORMAL : row.quality).key,
                        Integer.toString(d.trackers.size()), chosen, nz(d.rarity),
                        row.instanceId == null ? "" : row.instanceId.toString(), nz(row.effectId),
                        Long.toString(shownCount(row, d, chosen)), effectName(row.effectId)));
        }
        return rows;
    }

    // ------------------------------------------------------------- player: mounts and animations

    /** The player-facing mounts screen key. */
    public static final String EDITOR_MOUNTS = "cosmetic_mounts";

    /** The player-facing animations screen key. */
    public static final String EDITOR_ANIMATIONS = "cosmetic_animations";

    private static int rank(CosmeticOwnership row)
    {
        return row == null || row.quality == null ? 0 : row.quality.rank;
    }

    /**
     * The authored display name of a copy's rolled effect, resolved HERE because effects are not synced to
     * clients: {@code PacketCosmeticCatalogSync} carries definitions only, so a screen has nothing to look an
     * effect up in. Sending the name costs one short string per Magic copy and keeps the client out of the
     * business of knowing what an effect is.
     *
     * <p>Blank when the id is blank or names an effect that no longer exists, which the screen answers by
     * prettifying the id. An orphaned effect id is a real state: an admin may delete an effect that copies were
     * already rolled with.
     */
    private static String effectName(String effectId)
    {
        if (effectId == null || effectId.isBlank())
            return "";
        CosmeticEffect fx = CosmeticCatalog.effect(effectId);
        return fx == null || fx.displayName == null ? "" : fx.displayName.trim();
    }

    /**
     * The number to put on a copy's tile: the chosen tracker's count, or the highest count the copy carries when
     * nothing is chosen. Zero when the copy counts nothing, which the screen draws as no number at all.
     */
    private static long shownCount(CosmeticOwnership row, CosmeticDef def, String chosen)
    {
        if (row == null)
            return 0L;
        if (chosen != null && !chosen.isBlank() && def != null && def.tracker(chosen) != null)
            return row.count(chosen);
        long best = 0L;
        for (Map.Entry<String, CosmeticCounter> e : row.counts.entrySet())
            if (e.getValue() != null && (def == null || def.tracker(e.getKey()) != null))
                best = Math.max(best, e.getValue().n);
        return best;
    }

    /** Apply a player action. Every branch re-validates server side; see {@code WardrobeManager}. */
    public static void handleWardrobe(ServerPlayer player, String action, List<String> args)
    {
        switch (action)
        {
        case "equip":
            // args: slotKey, catalogId, [instanceId]. A blank or unparseable instance means "the caller did not
            // say", which WardrobeManager answers with the best copy. A named instance is re-checked there
            // against the ledger, so a crafted id buys nothing.
            if (args.size() >= 2)
                WardrobeManager.equip(player, CosmeticSlot.byKey(args.get(0)), args.get(1),
                        args.size() >= 3 ? parseUuid(args.get(2)) : null);
            break;
        case "unequip":
            if (!args.isEmpty())
                WardrobeManager.unequip(player, CosmeticSlot.byKey(args.get(0)));
            break;
        case "settracker":
            if (args.size() >= 2)
                WardrobeManager.setTracker(player, args.get(0), args.get(1));
            break;
        default:
            break;
        }
        openWardrobe(player); // always self-refreshes, so a refused action shows the unchanged truth
    }

    /** Null rather than an exception: an unreadable instance id is "unspecified", not a failed action. */
    private static java.util.UUID parseUuid(String raw)
    {
        if (raw == null || raw.isBlank())
            return null;
        try
        {
            return java.util.UUID.fromString(raw.trim());
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private static void send(ServerPlayer player, String editor, List<String> meta, List<List<String>> rows)
    {
        NetworkUtils.sendTo(new PacketEditorData(editor, new ArrayList<>(meta), rows), player);
    }

    private static String nz(String s)
    {
        return s == null ? "" : s;
    }
}
