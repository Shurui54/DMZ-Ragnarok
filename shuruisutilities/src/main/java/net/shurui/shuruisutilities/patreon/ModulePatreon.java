package net.shurui.shuruisutilities.patreon;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandManager;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule;
import net.shurui.shuruisutilities.util.events.ConfigReloadEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStartingEvent;
import net.shurui.shuruisutilities.util.events.SUModuleEvent.SUModuleServerStoppingEvent;

/**
 * Patreon account linking. Registers {@code /patreon}, fetches each player's supporter tier from the backend on
 * join and on a periodic refresh, and caches it with a fail-toward-keeping-benefits grace window. Other SU features
 * gate on the result through {@link PatreonAPI}. Entirely server-side and cleanly inert until the backend URL and
 * API key are set in the COMMON config (see {@link net.shurui.shuruisutilities.core.SUConfig}).
 */
@SUModule(name = "Patreon", parentMod = ShuruisUtilities.class, canDisable = true, defaultModule = true, version = ShuruisUtilities.CURRENT_MODULE_VERSION)
public class ModulePatreon
{
    public static final String PERM = "su.patreon";
    public static final String PERM_USE = PERM + ".use";
    public static final String PERM_ADMIN = PERM + ".admin";

    // poll the online list this often (ticks); the actual per-player refresh cadence is config-driven and
    // self-gated inside PatreonManager.refreshIfDue, so this only needs to be finer than the smallest interval.
    private static final int TICK_POLL_INTERVAL = 1200; // 60 seconds

    // re-send the crown codepoints this often (ticks). Matches the ranks module's cadence: a client that missed
    // the login sync draws no crown above heads until the next one, so this stays short.
    private static final int CROWN_BROADCAST_INTERVAL = 200; // 10 seconds

    @SubscribeEvent
    public void registerCommands(RegisterCommandsEvent event)
    {
        // Shurui's-Key only. Patreon is on neither allowlist, so both the limited and the keyless tier tear this
        // module down at ServerStartedEvent, which is after RegisterCommandsEvent and therefore too late to take
        // /patreon back out of the dispatcher. Registering only under the full key is what actually gates it.
        // Asked through CoreGateHooks, which only the Ragnarok Key installs (keyless default: not registered), so a
        // jar that merely carries the key's mod id does not bring /patreon back.
        if (!net.shurui.dev.sdu.api.key.CoreGateHooks.get().patreonCommand())
            return;
        SUCommandManager.registerCommand(new CommandPatreon(true), event.getDispatcher());
    }

    @SubscribeEvent
    public void serverStarting(SUModuleServerStartingEvent event)
    {
        APIRegistry.perms.registerPermissionDescription(PERM, "Patreon account linking");
        APIRegistry.perms.registerPermission(PERM_USE, DefaultPermissionLevel.ALL,
                "Link your Patreon account and check your supporter tier");
        APIRegistry.perms.registerPermission(PERM_ADMIN, DefaultPermissionLevel.OP,
                "Clear a player's cached Patreon tier");
        PatreonManager.init();
        // Keyless, the cosmetic catalogue is loaded here for the public Patreon wardrobe (S17a moved the module
        // that used to load it into the Ragnarok Key). A no-op with the key.
        PatreonWardrobe.onServerStarting();
    }

    @SubscribeEvent
    public void serverStopping(SUModuleServerStoppingEvent event)
    {
        PatreonManager.shutdown();
    }

    /**
     * Rebuild the fixed tier ladder and reload permanent grants on /rgreload. The ladder cannot change, but the
     * permanent_grants.json and apply it without a server restart. Posted on the Forge bus (which the SU event bus
     * aliases), so this module receives it like its other events.
     */
    @SubscribeEvent
    public void onConfigReload(ConfigReloadEvent event)
    {
        PatreonTiers.load();
        PatreonGrants.load();
        // A reloaded grant can add or take away a Patreon wardrobe cosmetic: apply it to everyone online now.
        PatreonWardrobe.reconcileOnline(ServerLifecycleHooks.getCurrentServer());
    }

    /**
     * Send every online player's crown codepoint to every client, for the above-head and tab-list crowns. Only the
     * resolved glyph travels, never the tier: a client can neither read nor claim anyone's entitlement. Cheap
     * enough to re-send on a timer, which is also how a client that missed the login sync recovers.
     */
    public static void broadcastCrowns()
    {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        Map<UUID, Integer> map = buildCrownMap(server);
        NetworkUtils.INSTANCE.send(PacketDistributor.ALL.noArg(), new PacketCrownSync(map));
        lastSentCrowns = map;
    }

    /** The last crown map broadcast to everyone, so the periodic refresh only messages the network on a real change. */
    private static Map<UUID, Integer> lastSentCrowns = java.util.Collections.emptyMap();

    /** Build every online player's resolved crown codepoint. Kept in one place so each caller computes it once. */
    private static Map<UUID, Integer> buildCrownMap(net.minecraft.server.MinecraftServer server)
    {
        Map<UUID, Integer> map = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers())
        {
            int cp = PatreonCrowns.codepointFor(p.getUUID());
            if (cp > 0)
                map.put(p.getUUID(), cp);
        }
        return map;
    }

    /**
     * Send the whole crown map to ONE joining player. The packet replaces the client cache wholesale (no partial
     * variant), so the joiner gets everyone's crowns while no one else is re-sent to on this login: the others pick up
     * the newcomer's crown through {@link #broadcastCrownsIfChanged()} on the next refresh, which fires on the change.
     */
    private static void sendCrownsTo(ServerPlayer player)
    {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        NetworkUtils.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new PacketCrownSync(buildCrownMap(server)));
    }

    /** The periodic refresh: recompute the crowns, but only put them on the wire when they differ from the last. */
    private static void broadcastCrownsIfChanged()
    {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList().getPlayers().isEmpty())
            return;
        Map<UUID, Integer> map = buildCrownMap(server);
        if (map.equals(lastSentCrowns))
            return;
        NetworkUtils.INSTANCE.send(PacketDistributor.ALL.noArg(), new PacketCrownSync(map));
        lastSentCrowns = map;
    }

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer player)
        {
            PatreonManager.refreshNow(player.getUUID());
            // Send what we already know now (a permanent grant, or a cached tier from a previous session) so a
            // crown shows immediately, but only to the joiner; everyone else picks up this player's crown through the
            // changed-refresh below. The async refresh above lands and the tick re-broadcasts on the change.
            sendCrownsTo(player);
            // The public Patreon wardrobe (S17p): grant or take back this player's Patreon cosmetics, and without
            // the key do the wardrobe login sync the private Cosmetics module would otherwise do.
            PatreonWardrobe.onLogin(player);
        }
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || server.getPlayerList().getPlayers().isEmpty())
            return;
        // Re-broadcast crowns on a short cycle (independent of the much slower entitlement refresh) so a tier
        // change, a relog race or a missed login sync corrects itself within seconds.
        if (server.getTickCount() % CROWN_BROADCAST_INTERVAL == 0)
        {
            broadcastCrownsIfChanged();
            // Same cadence for the Patreon wardrobe, so an async tier result lands within seconds. Cheap: a reward
            // lookup and a ledger read per online player, and nothing is sent unless a row actually changed.
            PatreonWardrobe.reconcileOnline(server);
        }
        if (server.getTickCount() % TICK_POLL_INTERVAL != 0)
            return;
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            PatreonManager.refreshIfDue(p.getUUID());
    }
}
