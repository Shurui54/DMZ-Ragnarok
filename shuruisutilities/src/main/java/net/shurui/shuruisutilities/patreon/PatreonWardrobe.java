package net.shurui.shuruisutilities.patreon;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticLedgerData;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticOwnership;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticWardrobeData;
import net.shurui.shuruisutilities.cosmetics.wardrobe.PlayerWardrobe;
import net.shurui.shuruisutilities.cosmetics.wardrobe.WardrobeManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The Patreon wardrobe: the wardrobe cosmetics a supporter is entitled to through Patreon, and the PUBLIC path that
 * lets them wear those on every server running the mods, keyless included.
 *
 * <h2>Where an entitlement comes from</h2>
 * Two sources, unioned:
 * <ul>
 *   <li>ACTIVE PATRON STATUS (owner decision, S17q): every catalogue cosmetic whose {@link CosmeticDef#patreonTier}
 *       is set, while {@link PatreonAPI#hasTierAtLeast(UUID, String)} holds for that tier. That reads the effective
 *       tier, so a backend outage keeps the cosmetic for the grace window and a lapse takes it away once the tier
 *       does. A permanent grant carries no tier, so it never unlocks these on its own.</li>
 *   <li>A reward key {@code wardrobe.<catalogId>} ({@link PatreonAPI#REWARD_WARDROBE_PREFIX}) held through the
 *       player's Patreon tier or a permanent grant ({@code permanent_grants.json}, synced across shards by
 *       {@code ShardPatreonGrants}). Only EXPLICIT keys count: the owners' {@code "*"} grant expands to the fixed
 *       ladder's keys (see {@code PatreonManager.permanentRewards}), so it never dresses anyone in the whole shop
 *       catalogue.</li>
 * </ul>
 *
 * <h2>How it reaches the wardrobe</h2>
 * {@link #reconcile} turns the entitlement into an ordinary ledger row with source {@link #SOURCE}, a deterministic
 * instance id per (player, cosmetic) and {@code bound} set, so it can never be minted into a token or traded. Losing
 * the entitlement tombstones that one row (and takes it off); regaining it restores the same row. Rows from any
 * other source are never touched here. With the Ragnarok Key the whole wardrobe works as before and simply sees
 * these rows as owned copies; without it {@link WardrobeManager} runs in Patreon-only mode, where ONLY these rows
 * can be listed, worn, synced and rendered, and the shop, crates, editor, tokens, mounts, pets, animations and
 * purchased cosmetics stay private.
 *
 * <p>Gated on the public "Patreon" module being loaded. Nothing here depends on the private Cosmetics module
 * being up: the catalogue and the two SavedData stores are core.
 */
public final class PatreonWardrobe
{
    private PatreonWardrobe()
    {
    }

    /** The ledger row source for a Patreon-granted copy. PERSISTED on the row, so never rename it. */
    public static final String SOURCE = "patreon";

    /** The {@code @SUModule} name of {@link ModulePatreon}. */
    private static final String PATREON_MODULE = "Patreon";

    /** Whether the Patreon wardrobe runs here: the public Patreon module is entitled and loaded. */
    public static boolean active()
    {
        try
        {
            return net.shurui.shuruisutilities.core.config.PublicContent.moduleEntitled(PATREON_MODULE)
                    && net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher
                            .getModuleContainer(PATREON_MODULE) != null;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** True for a ledger row that came from a Patreon entitlement. */
    public static boolean isPatreonRow(CosmeticOwnership row)
    {
        return row != null && SOURCE.equals(row.source);
    }

    /**
     * The slots a Patreon cosmetic may be worn in without the key: the worn body slots only. The mount, the pet and
     * the triggered animations need the private managers that spawn and play them, so they stay keyed.
     */
    public static boolean publicSlot(CosmeticSlot slot)
    {
        return slot != null && slot.usable() && !slot.triggered() && slot != CosmeticSlot.MOUNT
                && slot != CosmeticSlot.PET;
    }

    /**
     * The catalogue ids this player is entitled to wear through Patreon right now: every cosmetic gated on a tier
     * their effective tier reaches, plus the explicit {@code wardrobe.<id>} reward keys they hold.
     */
    public static Set<String> entitledIds(UUID player)
    {
        Set<String> out = new LinkedHashSet<>();
        if (player == null)
            return out;
        for (CosmeticDef def : CosmeticCatalog.all())
            if (def != null && def.patreonGated() && PatreonAPI.hasTierAtLeast(player, def.patreonTier))
                out.add(def.id);
        for (String key : PatreonAPI.getRewards(player))
        {
            if (key == null || !key.startsWith(PatreonAPI.REWARD_WARDROBE_PREFIX))
                continue;
            String id = key.substring(PatreonAPI.REWARD_WARDROBE_PREFIX.length()).trim();
            if (!id.isEmpty())
                out.add(id);
        }
        return out;
    }

    /** The one instance id a Patreon copy of this cosmetic has for this player, the same on every shard. */
    public static UUID instanceFor(UUID player, String catalogId)
    {
        return UUID.nameUUIDFromBytes(("dmzr:patreon-wardrobe:" + player + ":" + catalogId)
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Bring this player's Patreon rows in line with what they are entitled to now. Grants (or restores) a row for
     * every entitled cosmetic the catalogue knows, and tombstones every live Patreon row they are no longer entitled
     * to, taking it off if worn. A cosmetic missing from the catalogue is left alone either way, the same way the
     * ledger keeps a row whose definition was deleted. Returns whether anything changed; syncs when it did.
     */
    public static boolean reconcile(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || !active())
            return false;
        // The definitions (and their tier gates) are needed on every server, keyless included, so do not rely on the
        // private Cosmetics module having loaded them.
        CosmeticCatalog.ensureLoaded();
        UUID uuid = player.getUUID();
        Set<String> entitled = entitledIds(uuid);
        CosmeticLedgerData ledger = CosmeticLedgerData.get(server);
        boolean changed = false;
        for (String id : entitled)
        {
            CosmeticDef def = CosmeticCatalog.get(id);
            if (def == null)
                continue;
            UUID instance = instanceFor(uuid, def.id);
            CosmeticOwnership row = ledger.row(instance);
            if (row == null)
            {
                changed |= ledger.grant(instance, uuid, def.id, SOURCE, CosmeticQuality.NORMAL, "", "", true) != null;
            }
            else if (!row.live())
            {
                changed |= ledger.restore(instance);
            }
        }
        CosmeticWardrobeData store = CosmeticWardrobeData.get(server);
        PlayerWardrobe worn = null;
        for (CosmeticOwnership row : ledger.rowsOf(uuid))
        {
            if (!isPatreonRow(row) || entitled.contains(row.catalogId) || CosmeticCatalog.get(row.catalogId) == null)
                continue;
            if (!ledger.revoke(row.instanceId))
                continue;
            changed = true;
            if (worn == null)
                worn = store.get(uuid);
            // This copy only: a purchased copy of the same cosmetic worn elsewhere stays on.
            UUID revoked = row.instanceId;
            worn.equipped.values().removeIf(e -> e != null && revoked.equals(e.instanceId));
        }
        if (worn != null)
            store.set(uuid, worn);
        if (changed)
        {
            LoggingHandler.sulog.info("[Patreon] Wardrobe entitlement for {}: {}", player.getGameProfile().getName(),
                    entitled);
            WardrobeManager.onPatreonRowsChanged(server, uuid);
        }
        return changed;
    }

    /**
     * SU server start (from {@link ModulePatreon}): without the Ragnarok Key nothing else loads the cosmetic catalogue
     * any more (the private "Cosmetics" module that did lives in the key since S17a), so load it here, at the same
     * point in the start sequence the module used. The public Patreon wardrobe, the token names and the catalogue
     * sync all read it keyless. With the key the module loads it itself, exactly as before, so this does nothing.
     */
    public static void onServerStarting()
    {
        if (net.shurui.shuruisutilities.api.key.CosmeticHooks.available())
            return;
        CosmeticCatalog.ensureLoaded();
    }

    /** Login: reconcile, and without the key do the wardrobe's own login sync, which the keyed module does itself. */
    public static void onLogin(ServerPlayer player)
    {
        if (player == null || !active())
            return;
        reconcile(player);
        if (WardrobeManager.patreonOnly())
            WardrobeManager.onLogin(player);
    }

    /** The periodic pass, so a tier change or a reloaded grant lands without a relog. */
    public static void reconcileOnline(MinecraftServer server)
    {
        if (server == null || !active())
            return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            reconcile(p);
    }
}
