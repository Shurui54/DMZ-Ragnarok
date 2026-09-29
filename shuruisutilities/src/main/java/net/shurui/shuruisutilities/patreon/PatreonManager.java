package net.shurui.shuruisutilities.patreon;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Server-side driver for Patreon entitlement fetching and caching. All backend HTTP runs on a single daemon worker
 * thread; the network result is applied back on the server main thread via {@link MinecraftServer#execute}. Nothing
 * here ever blocks a game tick.
 *
 * <p>The cache fails TOWARD keeping benefits: a last-known-good tier keeps applying for the configured grace period
 * while the backend is unreachable, and only falls back to "no tier" once that window elapses with no successful
 * refresh. See {@link #effectiveTier(UUID)}.
 *
 * <p>Reward gating by other features should go through {@link PatreonAPI}, not this class directly.
 */
public final class PatreonManager
{
    private PatreonManager() {}

    /** One persisted cache row. Public fields so Gson maps it directly. */
    public static final class Entry
    {
        // last tier the backend successfully reported (may be empty = not entitled)
        public String tier = "";
        // epoch millis of that last SUCCESSFUL fetch (0 = never succeeded). A failed fetch never touches this, so
        // the value ages out and the grace window can expire.
        public long lastGoodMs = 0L;
        // whether the backend ever reported this player as linked, for status display
        public boolean everLinked = false;
    }

    /** Root of entitlements.json. */
    public static final class CacheFile
    {
        public Map<String, Entry> entries = new LinkedHashMap<>();
    }

    // live cache, keyed by player UUID
    private static final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    // last time (epoch ms) a fetch was enqueued per player, to rate-limit refreshes; transient (not persisted)
    private static final Map<UUID, Long> lastAttemptMs = new ConcurrentHashMap<>();

    // single background thread; created in init(), torn down in shutdown()
    private static volatile ExecutorService worker;

    /** Called from the module on server start: spin up the worker and load the persisted cache + tier mapping. */
    public static void init()
    {
        PatreonTiers.load();
        PatreonGrants.load();
        loadCache();
        if (worker == null || worker.isShutdown())
        {
            worker = Executors.newSingleThreadExecutor(r ->
            {
                Thread t = new Thread(r, "SU-Patreon-Worker");
                t.setDaemon(true);
                return t;
            });
        }
        if (!SUConfig.patreonConfigured())
            LoggingHandler.sulog.info("[Patreon] Not configured (no backend URL / API key); linking is inert.");
    }

    /** Called from the module on server stop: persist the cache and stop the worker. */
    public static void shutdown()
    {
        saveCache();
        ExecutorService w = worker;
        worker = null;
        if (w != null)
        {
            w.shutdownNow();
            try
            {
                w.awaitTermination(2, TimeUnit.SECONDS);
            }
            catch (InterruptedException ie)
            {
                Thread.currentThread().interrupt();
            }
        }
        lastAttemptMs.clear();
    }

    /** True when the backend URL and key are both set. Commands and handlers no-op cleanly when this is false. */
    public static boolean isConfigured()
    {
        return SUConfig.patreonConfigured();
    }

    /**
     * The tier that currently applies to this player. Within the grace window of the last successful fetch it is
     * that fetched tier; once the grace window elapses with no fresh success it falls back to "" (no tier). Reads
     * are cheap and side-effect free; call from the server thread.
     */
    public static String effectiveTier(UUID uuid)
    {
        Entry e = cache.get(uuid);
        if (e == null || e.lastGoodMs == 0L || e.tier == null || e.tier.isBlank())
            return "";
        long graceMs = Math.max(0L, (long) SUConfig.patreonGraceHours) * 3600_000L;
        long age = System.currentTimeMillis() - e.lastGoodMs;
        if (age <= graceMs)
            return e.tier;
        return "";
    }

    /** True when the last successful fetch is older than the grace window (so the tier is now being withheld). */
    public static boolean isInGraceHold(UUID uuid)
    {
        Entry e = cache.get(uuid);
        if (e == null || e.lastGoodMs == 0L)
            return false;
        long graceMs = Math.max(0L, (long) SUConfig.patreonGraceHours) * 3600_000L;
        return (System.currentTimeMillis() - e.lastGoodMs) > graceMs && e.tier != null && !e.tier.isBlank();
    }

    /** Whether the backend has ever reported this player as linked (independent of the grace window). */
    public static boolean everLinked(UUID uuid)
    {
        Entry e = cache.get(uuid);
        return e != null && e.everLinked;
    }

    /**
     * True when this player holds the given reward PERMANENTLY, regardless of Patreon status (see
     * {@link PatreonGrants}). Checked before any tier logic so these grants stand even when the backend is
     * unconfigured or unreachable. Matches an explicit uuid grant first, then falls back to the player's current
     * username resolved from the server.
     */
    public static boolean hasPermanentReward(UUID uuid, String rewardKey)
    {
        if (uuid == null || rewardKey == null || rewardKey.isBlank())
            return false;
        if (setGrants(PatreonGrants.rewardsForUuid(uuid), rewardKey))
            return true;
        String name = resolveName(uuid);
        return name != null && setGrants(PatreonGrants.rewardsForName(name), rewardKey);
    }

    /** The concrete reward keys permanently granted to this player, with {@link PatreonGrants#ALL_REWARDS} expanded. */
    public static Set<String> permanentRewards(UUID uuid)
    {
        if (uuid == null)
            return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        collectGrants(out, PatreonGrants.rewardsForUuid(uuid));
        String name = resolveName(uuid);
        if (name != null)
            collectGrants(out, PatreonGrants.rewardsForName(name));
        return out;
    }

    private static boolean setGrants(Set<String> granted, String rewardKey)
    {
        return granted.contains(PatreonGrants.ALL_REWARDS) || granted.contains(rewardKey);
    }

    private static void collectGrants(Set<String> out, Set<String> granted)
    {
        if (granted.isEmpty())
            return;
        if (granted.contains(PatreonGrants.ALL_REWARDS))
            out.addAll(allKnownRewardKeys());
        for (String k : granted)
            if (!PatreonGrants.ALL_REWARDS.equals(k))
                out.add(k);
    }

    // Every reward key SU can currently grant: the two well-known constants plus every key on the fixed ladder. Used
    // to expand a "*" (all rewards) permanent grant into concrete keys for getRewards listings.
    private static Set<String> allKnownRewardKeys()
    {
        Set<String> s = new LinkedHashSet<>();
        s.add(PatreonAPI.REWARD_PLAYER_LIMIT_BYPASS);
        s.add(PatreonAPI.REWARD_FORM_COSMETIC);
        s.addAll(PatreonTiers.allRewardKeys());
        return s;
    }

    /** Resolve a player's current username from the online list or the server profile cache, or null if unknown. */
    private static String resolveName(UUID uuid)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return null;
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null)
            return online.getGameProfile().getName();
        if (server.getProfileCache() == null)
            return null;
        return server.getProfileCache().get(uuid).map(p -> p.getName()).orElse(null);
    }

    /**
     * Enqueue an entitlement refresh for this player if the feature is configured and the last attempt is older than
     * the refresh interval. Returns immediately; the HTTP call runs on the worker thread and the result is applied
     * back on the main thread.
     */
    public static void refreshIfDue(UUID uuid)
    {
        if (!isConfigured())
            return;
        long now = System.currentTimeMillis();
        long refreshMs = Math.max(1L, (long) SUConfig.patreonRefreshMinutes) * 60_000L;
        Long last = lastAttemptMs.get(uuid);
        if (last != null && (now - last) < refreshMs)
            return;
        enqueueFetch(uuid);
    }

    /** Force a refresh now (used on join and by /patreon status), bypassing the refresh interval. */
    public static void refreshNow(UUID uuid)
    {
        if (!isConfigured())
            return;
        enqueueFetch(uuid);
    }

    private static void enqueueFetch(UUID uuid)
    {
        ExecutorService w = worker;
        if (w == null || w.isShutdown())
            return;
        lastAttemptMs.put(uuid, System.currentTimeMillis());
        w.submit(() ->
        {
            PatreonBackend.Entitlement result = PatreonBackend.fetchEntitlement(uuid);
            runOnMain(() -> applyEntitlement(uuid, result));
        });
    }

    // applied on the server main thread
    private static void applyEntitlement(UUID uuid, PatreonBackend.Entitlement result)
    {
        if (result == null)
            return;
        if (!result.ok)
            return; // unreachable: keep the last-known-good tier, let the grace window govern the read side
        Entry e = cache.computeIfAbsent(uuid, k -> new Entry());
        e.tier = result.tier == null ? "" : result.tier;
        e.lastGoodMs = System.currentTimeMillis();
        if (result.linked)
            e.everLinked = true;
        saveCache();
    }

    /**
     * Start the link flow for a player: request a single-use code + URL from the backend off-thread, then deliver a
     * clickable chat link back on the main thread. No-op with a chat notice if the feature is unconfigured.
     */
    /** The browser URL a player opens to link without this server holding any secret. Empty when unconfigured. */
    public static String startUrl()
    {
        return PatreonBackend.startUrl();
    }

    /** Backend base URL, sent to the client so it can run the one-click link itself. Empty when unconfigured. */
    public static String backendBaseUrl()
    {
        return PatreonBackend.baseUrl();
    }

    /** True when this server can mint link codes itself (it holds the shared key). Perks do not require this. */
    public static boolean isServerKeyed()
    {
        return SUConfig.patreonServerKeyed();
    }

    /**
     * The path used on any server that does not hold the shared key, which is every server except the project's own:
     * hand the player a clickable link to start the flow in their browser, then tell them to bring the code back with
     * {@code /patreon claim} (or the Cosmetics menu). No backend call is needed to say this, so it never fails.
     */
    public static void sendStartInstructions(ServerPlayer player)
    {
        String url = startUrl();
        if (url.isEmpty())
        {
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.link.failed")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        MutableComponent link = Component.translatable("message.dmz_ragnarok.core.patreon.link.click")
                .withStyle(Style.EMPTY
                        .withColor(net.minecraft.ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.dmz_ragnarok.core.patreon.link.hover"))));
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.link.prompt")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        player.sendSystemMessage(link);
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.hint")
                .withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    /**
     * Finish a browser-started link: hand the claim code the player was shown to the backend along with their UUID.
     * Runs off the server thread, then refreshes their entitlement and re-broadcasts crowns so the result is visible
     * immediately rather than at the next poll.
     */
    public static void claim(ServerPlayer player, String claimCode)
    {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        ExecutorService w = worker;
        if (w == null || w.isShutdown() || claimCode == null || claimCode.isBlank())
            return;
        w.submit(() ->
        {
            PatreonBackend.ClaimResult result = PatreonBackend.claim(uuid, name, claimCode);
            // On success re-read the entitlement on this same worker thread, so the local cache and the grace window
            // agree with the backend before the player is told anything.
            PatreonBackend.Entitlement fresh = result.ok ? PatreonBackend.fetchEntitlement(uuid) : null;
            if (fresh != null)
                lastAttemptMs.put(uuid, System.currentTimeMillis());
            runOnMain(() ->
            {
                if (fresh != null)
                    applyEntitlement(uuid, fresh);
                deliverClaim(uuid, result);
            });
        });
    }

    private static void deliverClaim(UUID uuid, PatreonBackend.ClaimResult result)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null)
            return;
        if (result.ok)
        {
            String tier = effectiveTier(uuid);
            if (tier.isEmpty())
                player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.nopatron")
                        .withStyle(net.minecraft.ChatFormatting.YELLOW));
            else
                player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.ok",
                        Component.literal(PatreonAPI.displayName(tier))
                                .withStyle(net.minecraft.ChatFormatting.GOLD))
                        .withStyle(net.minecraft.ChatFormatting.GREEN));
            ModulePatreon.broadcastCrowns();
        }
        else if (result.rejected)
        {
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.bad")
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
        else
        {
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.claim.failed")
                    .withStyle(net.minecraft.ChatFormatting.RED));
        }
    }

    public static void startLink(ServerPlayer player)
    {
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().getName();
        ExecutorService w = worker;
        if (w == null || w.isShutdown())
            return;
        w.submit(() ->
        {
            PatreonBackend.CodeResult result = PatreonBackend.requestCode(uuid, name);
            runOnMain(() -> deliverLink(uuid, result));
        });
    }

    private static void deliverLink(UUID uuid, PatreonBackend.CodeResult result)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
        if (player == null)
            return; // logged off before the code came back; they can just run /patreon link again
        if (result == null || result.url == null || result.url.isBlank())
        {
            player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.link.failed")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return;
        }
        MutableComponent link = Component.translatable("message.dmz_ragnarok.core.patreon.link.click")
                .withStyle(Style.EMPTY
                        .withColor(net.minecraft.ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, result.url))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("message.dmz_ragnarok.core.patreon.link.hover"))));
        player.sendSystemMessage(Component.translatable("message.dmz_ragnarok.core.patreon.link.prompt")
                .withStyle(net.minecraft.ChatFormatting.GOLD));
        player.sendSystemMessage(link);
    }

    /**
     * Clear a player's cached entitlement so they immediately drop to "no tier" on this server, and forget the link
     * attempt timer. The backend-side unlink (revoking the OAuth grant) is the operator's job on Patreon; this is the
     * in-game side. Returns true if there was a cached entry to clear.
     */
    public static boolean clearLocal(UUID uuid)
    {
        lastAttemptMs.remove(uuid);
        return cache.remove(uuid) != null;
    }

    private static File cacheFile()
    {
        File dir = new File(ShuruisUtilities.getSUDirectory(), "patreon");
        if (!dir.exists())
            dir.mkdirs();
        return new File(dir, "entitlements.json");
    }

    private static void loadCache()
    {
        cache.clear();
        CacheFile data = DataManager.load(CacheFile.class, cacheFile());
        if (data != null && data.entries != null)
        {
            for (Map.Entry<String, Entry> en : data.entries.entrySet())
            {
                try
                {
                    Entry v = en.getValue() == null ? new Entry() : en.getValue();
                    if (v.tier == null)
                        v.tier = "";
                    cache.put(UUID.fromString(en.getKey()), v);
                }
                catch (IllegalArgumentException ignored)
                {
                    // skip malformed UUID keys
                }
            }
        }
        LoggingHandler.sulog.info("[Patreon] Loaded {} cached entitlement(s)", cache.size());
    }

    private static void saveCache()
    {
        CacheFile data = new CacheFile();
        for (Map.Entry<UUID, Entry> e : cache.entrySet())
            data.entries.put(e.getKey().toString(), e.getValue());
        DataManager.save(data, cacheFile());
    }

    private static void runOnMain(Runnable r)
    {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        server.execute(r); // queued onto the server main thread; never runs on the worker
    }
}
