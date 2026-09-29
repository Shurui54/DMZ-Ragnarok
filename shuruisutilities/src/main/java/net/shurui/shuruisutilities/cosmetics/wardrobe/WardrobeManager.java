package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticCatalogSync;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketWardrobeSync;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Server-side authority for the wardrobe: who owns what, who is wearing what, and who may change it.
 *
 * <p>Nothing a client sends is trusted. An equip request names a slot and a catalogue id and is re-checked here
 * against {@link CosmeticLedgerData} and {@link CosmeticCatalog} before it is stored, so a client that forges its
 * owned set gets a refusal rather than a cosmetic. Same posture as {@code FormCosmeticManager}, which decides
 * entitlement server side and never reads a client claim.
 *
 * <h2>Two gates, and they are different questions</h2>
 * {@link #active()} asks whether the module is switched on at all: whether the Ragnarok Key installed
 * the cosmetics hook ({@code CosmeticHooks.available()}). It is
 * NOT an entitlement question about a particular player. Ownership is the per-player question and lives in the
 * ledger. A server with the module off keeps every ownership row untouched and simply stops dressing anybody,
 * which is what makes turning it back on a no-op rather than a restoration.
 */
public final class WardrobeManager
{
    private WardrobeManager()
    {
    }

    /** The {@code @SUModule} name and the {@code PublicContent} allow-list key. */
    public static final String MODULE = "Cosmetics";

    /** The switchboard key, as it appears in {@code config/dmz_ragnarok/modules.cfg}. */
    public static final String MODULE_KEY = "Content.Cosmetics";

    private static final String PLAYER_TOKEN = "%player%";

    /**
     * Whether the wardrobe may do anything on this server: the Ragnarok Key installed the cosmetics logic
     * ({@code CosmeticHooks}). Without the key this is false, which leaves the wardrobe in Patreon-only mode (the
     * Patreon wardrobe is public and gated separately).
     *
     * <p>See the class note on why a false here never destroys data.
     */
    public static boolean active()
    {
        return net.shurui.shuruisutilities.api.key.CosmeticHooks.available();
    }

    // ---------------------------------------------------------------- the public Patreon wardrobe (S17p)

    /**
     * Whether the wardrobe may dress anybody here: the full wardrobe ({@link #active()}), or without it the public
     * Patreon wardrobe, which only ever lists, wears, syncs and renders Patreon-granted copies. Every player-facing
     * wear path asks this; the shop, crates, editor, tokens, mounts, pets and animations keep asking
     * {@link #active()}. With the key this is exactly {@link #active()} whenever the module is up.
     */
    public static boolean wearActive()
    {
        return active() || net.shurui.shuruisutilities.patreon.PatreonWardrobe.active();
    }

    /**
     * Patreon-only mode: the full wardrobe is not entitled here (no Ragnarok Key) but the public Patreon module is.
     * Never true with the key, so every branch that tests it leaves the keyed wardrobe exactly as it was.
     */
    public static boolean patreonOnly()
    {
        return !active() && net.shurui.shuruisutilities.patreon.PatreonWardrobe.active();
    }

    /** Whether this ledger row may be listed and worn here: any row with the full wardrobe, Patreon rows otherwise. */
    public static boolean wearableRow(CosmeticOwnership row)
    {
        return !patreonOnly() || net.shurui.shuruisutilities.patreon.PatreonWardrobe.isPatreonRow(row);
    }

    /** Whether a slot may be worn here: any usable slot with the full wardrobe, the worn body slots otherwise. */
    private static boolean wearableSlot(CosmeticSlot slot)
    {
        return !patreonOnly() || net.shurui.shuruisutilities.patreon.PatreonWardrobe.publicSlot(slot);
    }

    /**
     * Whether the cosmetics menu offers this player the wardrobe. With the full wardrobe: whenever it is up, as
     * before. Patreon-only: when they hold at least one Patreon cosmetic, so a keyless server does not show every
     * player an empty wardrobe.
     */
    public static boolean offeredTo(ServerPlayer player)
    {
        if (active())
            return true;
        return patreonOnly() && !wearableOwned(player).isEmpty();
    }

    /**
     * What everybody is told this player wears. The whole outfit with the full wardrobe; Patreon-only, a COPY with
     * every slot that does not hold a live Patreon copy left out, so a purchased cosmetic stored from a keyed era is
     * neither rendered nor revealed, and the stored outfit itself is never touched.
     */
    public static PlayerWardrobe visible(MinecraftServer server, PlayerWardrobe w)
    {
        if (w == null || server == null || !patreonOnly())
            return w;
        PlayerWardrobe out = w.copy();
        CosmeticLedgerData ledger = ledger(server);
        out.equipped.entrySet().removeIf(e -> {
            EquippedCosmetic worn = e.getValue();
            CosmeticOwnership row = worn == null ? null : ledger.row(worn.instanceId);
            return row == null || !row.live() || !net.shurui.shuruisutilities.patreon.PatreonWardrobe.isPatreonRow(row)
                    || !net.shurui.shuruisutilities.patreon.PatreonWardrobe.publicSlot(CosmeticSlot.byKey(e.getKey()));
        });
        return out;
    }

    /**
     * A Patreon grant, restore or revoke landed for this player (see {@code PatreonWardrobe.reconcile}): re-sync them
     * and everybody who can see them. Patreon-only, the catalogue is re-sent first, because it carries only the
     * Patreon-granted definitions and a new grant may have added one.
     */
    public static void onPatreonRowsChanged(MinecraftServer server, UUID player)
    {
        if (server == null || player == null || !wearActive())
            return;
        if (patreonOnly())
            broadcastCatalog(server);
        syncOwner(server, player);
        broadcastOne(server, player);
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null && active())
            net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetManager.onEquipChanged(online);
    }

    // ---------------------------------------------------------------- reads

    public static CosmeticLedgerData ledger(MinecraftServer server)
    {
        return CosmeticLedgerData.get(server);
    }

    public static CosmeticWardrobeData wardrobes(MinecraftServer server)
    {
        return CosmeticWardrobeData.get(server);
    }

    /** What this player is wearing, as a copy. */
    public static PlayerWardrobe current(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return new PlayerWardrobe();
        return wardrobes(server).get(player.getUUID());
    }

    /** The catalogue ids this player owns AND could actually wear right now. */
    public static Set<String> wearableOwned(ServerPlayer player)
    {
        Set<String> out = new LinkedHashSet<>();
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return out;
        if (patreonOnly())
        {
            for (CosmeticOwnership row : ledger(server).rowsOf(player.getUUID()))
            {
                if (!net.shurui.shuruisutilities.patreon.PatreonWardrobe.isPatreonRow(row))
                    continue;
                CosmeticDef def = CosmeticCatalog.get(row.catalogId);
                if (def != null && def.wearable() && wearableSlot(def.slot))
                    out.add(def.id);
            }
            return out;
        }
        for (String id : ledger(server).ownedIds(player.getUUID()))
        {
            CosmeticDef def = CosmeticCatalog.get(id);
            if (def != null && def.wearable())
                out.add(id);
        }
        return out;
    }

    /**
     * Every id this player owns, wearable or not.
     *
     * <p>Kept separate from {@link #wearableOwned} on purpose: a cosmetic whose definition was deleted or
     * disabled is still OWNED, and telling a paying player they own nothing because an admin parked the entry
     * would be a support ticket. The wardrobe screen shows the wearable set; the ledger answers the other.
     */
    public static Set<String> allOwned(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return new LinkedHashSet<>();
        return ledger(server).ownedIds(player.getUUID());
    }

    public static boolean owns(ServerPlayer player, String catalogId)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        return server != null && ledger(server).owns(player.getUUID(), catalogId);
    }

    // ---------------------------------------------------------------- grants

    /**
     * Grant a cosmetic to a player, minting a fresh instance id.
     *
     * <p>The ONE grant path. Crates, tasks, the shop and the admin command all come through here so there is one
     * place that mints an instance, runs the definition's grant commands and syncs. Returns the instance id, or
     * null when the catalogue has no such entry.
     *
     * <p>Not idempotent by design at THIS level: calling it twice deliberately gives two copies, because that is
     * what opening two crates means. Idempotence lives one level down, on the instance id, which is what makes a
     * replayed row or a doubled merge safe. If a caller needs "grant at most one", it must check
     * {@link #owns} first, as the admin command does.
     */
    public static UUID grant(MinecraftServer server, UUID player, String catalogId, String source)
    {
        if (server == null || player == null)
            return null;
        CosmeticDef def = CosmeticCatalog.get(catalogId);
        if (def == null)
            return null;
        UUID instance = UUID.randomUUID();
        CosmeticOwnership row = ledger(server).grant(instance, player, def.id, source);
        if (row == null)
            return null;
        runGrantCommands(server, player, def);
        syncOwner(server, player);
        return instance;
    }

    /**
     * Grant a ROLLED copy of a cosmetic, carrying its quality, effect and the pool the effect came from.
     *
     * <p>The path a crate roll comes through. Like {@link #grant} it mints a fresh instance and is NOT idempotent
     * at this level: opening two crates is two grants and two copies, and idempotence lives on the instance id one
     * level down. The quality, effect and pool are decided once by the roll and are not editable afterwards by an
     * ordinary path. Bound is left false, so a rolled tradeable cosmetic can still be traded; only a shop purchase
     * sets bound.
     *
     * @param quality      what this copy is, never what the definition allows
     * @param effectId     the rolled effect, blank unless MAGIC
     * @param effectPoolId which pool the effect was rolled from, HISTORY, blank when nothing rolled it
     */
    public static UUID grantRolled(MinecraftServer server, UUID player, String catalogId, String source,
            CosmeticQuality quality, String effectId, String effectPoolId)
    {
        if (server == null || player == null)
            return null;
        CosmeticDef def = CosmeticCatalog.get(catalogId);
        if (def == null)
            return null;
        UUID instance = UUID.randomUUID();
        CosmeticOwnership row = ledger(server).grant(instance, player, def.id, source,
                quality == null ? CosmeticQuality.NORMAL : quality, effectId, effectPoolId, false);
        if (row == null)
            return null;
        runGrantCommands(server, player, def);
        syncOwner(server, player);
        return instance;
    }

    /**
     * Grant a BOUND Normal copy: the shop's grant path.
     *
     * <p>Bound is set true even for a tradeable cosmetic, which is what stops the shop being an infinite supply
     * into the trade economy while a crate-won copy of the same cosmetic stays tradeable. Always Normal, because a
     * shop only ever sells Normal.
     */
    public static UUID grantBound(MinecraftServer server, UUID player, String catalogId, String source)
    {
        if (server == null || player == null)
            return null;
        CosmeticDef def = CosmeticCatalog.get(catalogId);
        if (def == null)
            return null;
        UUID instance = UUID.randomUUID();
        CosmeticOwnership row = ledger(server).grant(instance, player, def.id, source, CosmeticQuality.NORMAL, "", "",
                true);
        if (row == null)
            return null;
        runGrantCommands(server, player, def);
        syncOwner(server, player);
        return instance;
    }

    /** How many copies of this cosmetic this player already holds, counting every live row. For per-player limits. */
    public static int ownedCount(MinecraftServer server, UUID player, String catalogId)
    {
        if (server == null || player == null || catalogId == null)
            return 0;
        int n = 0;
        for (CosmeticOwnership row : ledger(server).rowsOf(player))
            if (row != null && catalogId.equals(row.catalogId))
                n++;
        return n;
    }

    /** Revoke every copy this player holds of one cosmetic, and take it off them if they were wearing it. */
    public static int revoke(MinecraftServer server, UUID player, String catalogId)
    {
        if (server == null || player == null || catalogId == null)
            return 0;
        int n = ledger(server).revokeAll(player, catalogId);
        if (n > 0)
        {
            // Taking it off is part of revoking it: leaving a revoked cosmetic on somebody would mean the only
            // way to see whether a revoke worked is to look at the ledger, and everyone else would still see it.
            PlayerWardrobe w = wardrobes(server).get(player);
            if (w.removeEverywhere(catalogId))
                wardrobes(server).set(player, w);
            syncOwner(server, player);
            broadcastOne(server, player);
        }
        return n;
    }

    /**
     * Run a definition's grant commands from the SERVER's own source.
     *
     * <p>From the server rather than the player, copied from {@code TaskRewards}: a grant must not be limited to
     * what the player could have typed, and running it as them would hand every cosmetic author a way to make a
     * player execute something at their own permission level.
     */
    private static void runGrantCommands(MinecraftServer server, UUID player, CosmeticDef def)
    {
        if (def.grantCommands.isEmpty())
            return;
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        // %player% needs a NAME, and a name needs somebody online. An offline grant still records the ownership
        // row (the part that matters) and skips the commands rather than substituting a raw UUID into a command
        // that expects a name, which would fail in a way nobody would notice.
        if (online == null)
            return;
        String name = online.getGameProfile().getName();
        for (String raw : def.grantCommands)
        {
            if (raw == null || raw.isBlank())
                continue;
            String command = raw.replace(PLAYER_TOKEN, name);
            try
            {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[Cosmetics] Grant command failed for '{}': {} ({})", def.id, command,
                        t.toString());
            }
        }
    }

    // ---------------------------------------------------------------- equipping

    /**
     * Put a cosmetic on, or take the slot's contents off when {@code catalogId} is blank.
     *
     * <p>Every refusal is silent-but-unchanged rather than an exception: this is driven by a GUI button and a
     * client whose catalogue is one edit out of date is a normal thing to be, not an error. Returns whether
     * anything changed.
     */
    public static boolean equip(ServerPlayer player, CosmeticSlot slot, String catalogId)
    {
        return equip(player, slot, catalogId, null);
    }

    /**
     * Put a specific COPY on.
     *
     * <p>The slot records which instance is worn, not just which cosmetic, because quality lives on the copy. A
     * null {@code instanceId} means "the caller did not say", and the ledger then picks the owner's best copy
     * ({@link CosmeticLedgerData#bestInstance}). A named instance is re-checked against the ledger like
     * everything else: it has to be live, it has to be theirs, and it has to be a copy of the cosmetic they
     * asked for, or the whole request is refused rather than silently falling back to another copy.
     */
    public static boolean equip(ServerPlayer player, CosmeticSlot slot, String catalogId, UUID instanceId)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || slot == null || !wearActive() || !wearableSlot(slot))
            return false;
        String id = catalogId == null ? "" : catalogId.trim();
        PlayerWardrobe w = wardrobes(server).get(player.getUUID());
        // Patreon-only: a slot holding anything but a live Patreon copy (a purchase stored from a keyed era) is not
        // this mode's to change, so it is neither replaced nor cleared.
        if (patreonOnly() && w.worn(slot) != null && visible(server, w).worn(slot) == null)
            return false;
        EquippedCosmetic value = null;
        if (!id.isEmpty())
        {
            CosmeticDef def = CosmeticCatalog.get(id);
            // Re-checked here, not only where the button was drawn: the client sends an id by name, so hiding an
            // entry on screen alone would leave it reachable by a crafted packet.
            //
            // A TRIGGERED animation may go into ANY usable triggered slot (JOIN_LEAVE or TELEPORT), not only the one
            // named as its home slot: one record carries both directions, so the same animation is equippable for
            // both the join/leave pair and the teleport pair. A WORN cosmetic still has to match its exact slot.
            boolean triggeredEquip = def != null && def.slot != null && def.slot.triggered() && slot.triggered();
            if (def == null || !def.wearable() || !slot.usable() || (!triggeredEquip && def.slot != slot))
                return false;
            CosmeticLedgerData ledger = ledger(server);
            UUID instance = instanceId != null ? instanceId
                    : patreonOnly() ? net.shurui.shuruisutilities.patreon.PatreonWardrobe.instanceFor(player.getUUID(), def.id)
                    : ledger.bestInstance(player.getUUID(), def.id);
            CosmeticOwnership row = ledger.row(instance);
            if (row == null || !row.live() || row.escrowed || !player.getUUID().equals(row.player)
                    || !def.id.equals(row.catalogId) || !wearableRow(row))
                return false;
            value = describe(def, row, w.tracker(def.id));
        }
        if (!w.set(slot, value))
            return false;
        wardrobes(server).set(player.getUUID(), w);
        broadcastOne(server, player.getUUID());
        // Equipping or unequipping a PET reconciles the following pet entity: spawn the new one, replace a wrong
        // one, or remove it. Kept HERE, at the one authority for a slot change, so the GUI, the command and the
        // editor all trigger it without each having to remember to. A no-op for a non-PET slot.
        if (slot == CosmeticSlot.PET)
            net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetManager.onEquipChanged(player);
        return true;
    }

    /**
     * Build the slot record from the ownership row: quality, effect and the count for the chosen tracker.
     *
     * <p>The ONE place a slot record is built, so there is one answer to "what does the client get told about a
     * worn cosmetic". Everything in the record is a DENORMALISED copy of the row, which is why it is rebuilt
     * whenever the choice or the row changes rather than being edited in place somewhere else.
     */
    private static EquippedCosmetic describe(CosmeticDef def, CosmeticOwnership row, String chosenTracker)
    {
        EquippedCosmetic value = new EquippedCosmetic(def.id, row.instanceId, row.quality, row.effectId);
        // A tracker the definition no longer carries shows nothing rather than a stale number: an admin deleting
        // a tracker must not leave a count on screen that nothing will ever update again.
        String trackerId = chosenTracker == null ? "" : chosenTracker;
        if (!trackerId.isBlank() && def.tracker(trackerId) != null)
        {
            value.displayedTrackerId = trackerId;
            value.displayedCount = row.count(trackerId);
        }
        return value;
    }

    /** Take whatever is in this slot off. */
    public static boolean unequip(ServerPlayer player, CosmeticSlot slot)
    {
        return equip(player, slot, "");
    }

    /**
     * Choose which of a cosmetic's trackers is displayed.
     *
     * <p>Stored even though nothing counts yet, so an admin can build a tracking cosmetic and a player can pick
     * their tracker in milestone 1 and have that choice still be there when counting arrives.
     */
    public static boolean setTracker(ServerPlayer player, String catalogId, String trackerId)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !wearActive())
            return false;
        CosmeticDef def = CosmeticCatalog.get(catalogId);
        if (def == null || !ledger(server).owns(player.getUUID(), def.id))
            return false;
        if (patreonOnly() && !wearableOwned(player).contains(def.id))
            return false;
        String chosen = trackerId == null ? "" : trackerId.trim();
        // A blank clears the choice; anything else has to name a tracker this definition actually carries, or a
        // stale client would pin a choice that can never render.
        if (!chosen.isEmpty() && def.tracker(chosen) == null)
            return false;
        PlayerWardrobe w = wardrobes(server).get(player.getUUID());
        if (!w.setTracker(def.id, chosen))
            return false;
        // The choice lives per cosmetic, but the number shown to everybody rides in the slot record, so a slot
        // wearing this cosmetic has to be rebuilt or the label would keep showing the old counter until the next
        // equip. See PlayerWardrobe's note on why the two are not one map.
        for (CosmeticSlot slot : CosmeticSlot.values())
        {
            EquippedCosmetic worn = w.worn(slot);
            if (worn == null || !def.id.equals(worn.catalogId))
                continue;
            CosmeticOwnership row = ledger(server).row(worn.instanceId);
            if (row != null && row.live())
                w.set(slot, describe(def, row, chosen));
        }
        wardrobes(server).set(player.getUUID(), w);
        broadcastOne(server, player.getUUID());
        return true;
    }

    // ---------------------------------------------------------------- sync

    /**
     * On login: catalogue first, then the whole outfit table, then announce the joiner to everyone else.
     *
     * <p>The order is load-bearing. The wardrobe packet is a list of catalogue ids, so a client that got it
     * first would be holding ids it could not resolve. Same ordering {@code FormCosmeticManager.onLogin} uses,
     * for the same reason.
     */
    public static void onLogin(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !wearActive())
            return;
        sendCatalog(player);
        Map<UUID, PlayerWardrobe> table = wardrobes(server).all();
        if (patreonOnly())
        {
            Map<UUID, PlayerWardrobe> shown = new java.util.LinkedHashMap<>();
            for (Map.Entry<UUID, PlayerWardrobe> e : table.entrySet())
            {
                PlayerWardrobe v = visible(server, e.getValue());
                if (v != null && !v.equipped.isEmpty())
                    shown.put(e.getKey(), v);
            }
            table = shown;
        }
        NetworkUtils.sendTo(PacketWardrobeSync.fullTable(table, wearableOwned(player)), player);
        broadcastOne(server, player.getUUID(), player);
    }

    /** Push the current catalogue to one player. */
    public static void sendCatalog(ServerPlayer player)
    {
        if (player == null)
            return;
        if (patreonOnly())
        {
            // Only the Patreon-granted definitions, and no effects (a Patreon copy is always Normal): the rest of
            // the catalogue is the private shop's and is not sent without the key.
            MinecraftServer server = player.getServer();
            java.util.List<CosmeticDef> defs = new java.util.ArrayList<>();
            if (server != null)
                for (String id : ledger(server).liveCatalogIdsFromSource(
                        net.shurui.shuruisutilities.patreon.PatreonWardrobe.SOURCE))
                {
                    CosmeticDef def = CosmeticCatalog.get(id);
                    if (def != null)
                        defs.add(def);
                }
            NetworkUtils.sendTo(PacketCosmeticCatalogSync.of(defs, java.util.List.of()), player);
            return;
        }
        NetworkUtils.sendTo(PacketCosmeticCatalogSync.of(CosmeticCatalog.all(), CosmeticCatalog.allEffects()), player);
    }

    /**
     * Push the catalogue to everyone. Called after any admin edit, so a change is live without a relog.
     *
     * <p>The catalogue is a few dozen small records, so a full resend to everybody on an edit is cheaper than the
     * bookkeeping a delta would need and cannot drift.
     */
    public static void broadcastCatalog(MinecraftServer server)
    {
        if (server == null)
            return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            sendCatalog(p);
    }

    /** Tell everyone what this player is wearing now. */
    public static void broadcastOne(MinecraftServer server, UUID player)
    {
        broadcastOne(server, player, null);
    }

    /**
     * @param skip a player to leave out, used on login where they have just had the full table
     */
    private static void broadcastOne(MinecraftServer server, UUID player, ServerPlayer skip)
    {
        if (server == null || player == null)
            return;
        PlayerWardrobe w = visible(server, wardrobes(server).get(player));
        PacketWardrobeSync others = PacketWardrobeSync.single(player, w);
        for (ServerPlayer p : server.getPlayerList().getPlayers())
        {
            if (skip != null && p == skip)
                continue;
            // The subject of the update gets their own owned set with it, everybody else gets only the outfit.
            // See the packet's note on why an owned set is never broadcast.
            if (p.getUUID().equals(player))
                NetworkUtils.sendTo(PacketWardrobeSync.selfUpdate(player, w, wearableOwned(p)), p);
            else
                NetworkUtils.sendTo(others, p);
        }
    }

    /** Re-send one player their own outfit and owned set, after a grant or a revoke. */
    public static void syncOwner(MinecraftServer server, UUID player)
    {
        if (server == null || player == null)
            return;
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online == null)
            return;
        NetworkUtils.sendTo(PacketWardrobeSync.selfUpdate(player, visible(server, wardrobes(server).get(player)),
                wearableOwned(online)), online);
    }
}
