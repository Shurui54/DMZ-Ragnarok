package net.shurui.shuruisutilities.ragnarok;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Server side of runtime rgnpc (ninjin) model delivery: the same arrangement the rank badges use, for the
 * GeckoLib models and skins the 386-entry {@link RgNpcModels} table names.
 *
 * <p>An admin drops the model tree in {@code <gamedir>/ShuruisUtilities/rgnpc/}, it is zipped once, and streamed
 * to each client on join. The files live only in that server folder and never in the mod jar, so the model pack
 * cannot be lifted by unzipping a downloaded jar, which is the whole point of moving them here.</p>
 *
 * <h2>What differs from the rank badges, and why</h2>
 *
 * <ul>
 *   <li><b>Paced by the client, and capped overall.</b> The badge set is a few dozen KB and goes out in one packet.
 *       This set is measured in megabytes, so it goes one 256 KB chunk at a time, and the next chunk leaves only
 *       once the client has acknowledged the last ({@link PacketRgNpcAssetsHave}, sent after every chunk). A timer
 *       was not enough: it released about 5 MB a second whatever the client could take, and on a thin connection
 *       the surplus did not vanish, it queued in the proxy in front of the KeepAlive. On 2026-09-13 that dropped
 *       the same few players every 30 seconds for over an hour, each failover onto the other shard pushed the pack
 *       at them again, and the proxy's direct memory filled until nobody at all could log in. Acknowledgement
 *       pacing bounds what is ever in flight to one chunk per player, so a stalled client costs 256 KB, not the
 *       pack. {@link #GLOBAL_CHUNKS_PER_TICK} still caps the total across all players, for the mass arrival after
 *       a shard restart.</li>
 *   <li><b>Not key gated.</b> The badges are withheld from a keyless server because a missing badge is merely a
 *       missing badge. A missing MODEL is a render-thread failure, so these go to everyone who joins. The key
 *       still gates which models can be SPAWNED, in {@link RgNpcModels#availableIds(boolean)}; that is a
 *       different question from whether a client can draw one it has been sent.</li>
 *   <li><b>Stored deflated.</b> Geo files are JSON and compress hard, which is worth the one-off cost here in a
 *       way it is not for a handful of PNGs.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class RgNpcAssetServer
{
    private RgNpcAssetServer() {}

    private static final int CHUNK = 256 * 1024;

    /**
     * Chunks released ACROSS ALL PLAYERS per tick. The ceiling that actually protects the server's uplink.
     *
     * <h2>Why a per player limit was not enough</h2>
     * Acknowledgement pacing bounds what any ONE client is sent, which is the wrong quantity here: the link everybody
     * shares is the server's, and the interesting case is many clients starting at once. A shard restart does
     * exactly that, because the proxy fails every player on the restarting shard onto another one within a second
     * or two. Measured on 2026-09-04, nine players were claimed inside two seconds, which under a purely per player
     * limit is nine queues each releasing 256 KB a tick: roughly 45 MB/s asked of one uplink, and every one of
     * those clients then fails to answer a KeepAlive and is dropped at thirty seconds.
     *
     * <p>Two at a time is deliberately modest. Nobody is waiting on this pack to play (a missing model draws the
     * fallback Steve, see {@link RgNpcFallback}), so the cost of it arriving slowly is nothing, while the cost of
     * it arriving too fast is players being unable to join at all. With the version handshake in front of it this
     * queue is usually empty anyway; this bounds the bad case, which is everybody arriving with a cold cache at
     * once, such as the first join after the pack changes.
     */
    private static final int GLOBAL_CHUNKS_PER_TICK = 2;

    /** The folder name under the SU directory, and the extensions worth streaming. */
    private static final String FOLDER = "rgnpc";
    private static final String[] EXTENSIONS = {".geo.json", ".png", ".animation.json"};

    /**
     * Server ticks a client is given to say what it already holds ({@link PacketRgNpcAssetsHave}) before we stop
     * waiting on it.
     *
     * <p>Not a guess at how long the handshake takes: a current client that is reading its connection answers within a
     * tick or two of joining. Past this the server sends that client NOTHING (see {@link #expireReportGrace}), because
     * a client that has not spoken is either older than the handshake or not draining its connection, and neither can
     * be helped by pushing bytes at it. If it speaks later the transfer still starts then.
     */
    private static final int REPORT_GRACE_TICKS = 200;

    /**
     * How long a player's recent offers of the pack are remembered, and how many are allowed inside that span before
     * the next one is refused.
     *
     * <h2>Sized against an observed failover loop</h2>
     * On 2026-09-21 two players sat in a 30 second cycle: time out on one shard, fail over to the other, be offered
     * the pack again, time out again, fail back. Each bounce restarted the whole dance, so the pack offer was part of
     * what kept the loop alive. A window measured in minutes covers many bounces of a 30 second cycle, while three
     * offers inside it is more than any honest join sequence needs: a player who relogs once, or even twice, is still
     * served normally, and only a player being offered the pack over and over is refused.
     *
     * <h2>The cross shard caveat</h2>
     * This lives in one JVM's memory and is deliberately NOT shared between shards (no database, no sync). The loop
     * alternates shards, so each shard sees roughly every OTHER bounce: at a 30 second cycle that is one offer per
     * minute here, and the cap is reached after about three minutes rather than ninety seconds. The window is set
     * long enough that this only delays the brake, it does not defeat it. Do not shorten it on the assumption that a
     * shard sees every join, because it does not.
     */
    private static final long OFFER_WINDOW_MS = 10L * 60L * 1000L;

    /** Offers of the pack allowed to one player inside {@link #OFFER_WINDOW_MS} before the rest are refused. */
    private static final int MAX_OFFERS_PER_WINDOW = 3;

    /** Ticks between sweeps of {@link #OFFERS} and {@link #SILENT}, which is only bookkeeping, so it can be lazy. */
    private static final int SWEEP_INTERVAL_TICKS = 200;

    /** Round robin cursor for {@link #GLOBAL_CHUNKS_PER_TICK}, so a bound budget is shared fairly. */
    private static int rotation;

    private static byte[] zip = null;
    private static int version;
    private static boolean loaded = false;

    /**
     * Per player: where their transfer stands. {@link #onServerTick} sends {@code next} when nothing is in flight;
     * the client's acknowledgement ({@link #reportFrom}) moves {@code next} to what it actually holds and clears
     * {@code inFlight}, which is what lets the following chunk go.
     */
    private static final Map<UUID, Transfer> TRANSFERS = new HashMap<>();

    private static final class Transfer
    {
        /** The chunk the client needs next: the length of the leading run it holds. */
        int next;
        /** A chunk has been sent and not yet acknowledged. Nothing more goes to this player until it is. */
        boolean inFlight;
        /** The final chunk has gone out at least once, so a report back at zero means the pack failed to apply. */
        boolean sentLast;

        Transfer(int next)
        {
            this.next = next;
        }
    }

    /** Per player: ticks left to answer the handshake before {@link #REPORT_GRACE_TICKS} expires. */
    private static final Map<UUID, Integer> AWAITING_REPORT = new HashMap<>();

    /**
     * Players whose grace ran out without a word, and who were therefore sent nothing. Kept so that a report which
     * turns up late is still honoured: the grace clock that would have identified it as a join report is gone by
     * then, and without this the transfer could never start for the rest of the session.
     *
     * <p>Swept in {@link #purgeBookkeeping}, so an entry lasts a session at most.
     */
    private static final Set<UUID> SILENT = new HashSet<>();

    /** Per player: how often the pack has been offered lately, for the cooldown described on {@link #OFFER_WINDOW_MS}. */
    private static final Map<UUID, OfferHistory> OFFERS = new HashMap<>();

    private static final class OfferHistory
    {
        /** When this window opened. The whole record is thrown away once it is {@link #OFFER_WINDOW_MS} old. */
        long windowStart;
        /** Offers made inside this window. */
        int offers;
        /** The refusal has been logged for this window, so a looping player does not fill the log with it. */
        boolean suppressionLogged;

        OfferHistory(long windowStart)
        {
            this.windowStart = windowStart;
        }
    }

    /** Ticks since the last bookkeeping sweep. */
    private static int sinceSweep;

    // <gamedir>/ShuruisUtilities/rgnpc/, created with a README if missing
    private static File dir()
    {
        File d = new File(ShuruisUtilities.getSUDirectory(), FOLDER);
        if (d.mkdirs())
        {
            try
            {
                Files.write(new File(d, "README.txt").toPath(),
                        ("Put the rgnpc (ninjin) model pack in this folder, keeping the layout the mod used to ship in\n"
                                + "the jar:\n"
                                + "\n"
                                + "  geo/entity/ragnarok/<model>.geo.json\n"
                                + "  textures/entity/ragnarok/<skin>.png\n"
                                + "\n"
                                + "They are streamed to each player on join and held only in memory on their side, so they\n"
                                + "are never written into the mod jar or left on a player's disk as openable files.\n"
                                + "Restart the server after changing them.\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException ignored) {}
        }
        return d;
    }

    private static boolean wanted(String name)
    {
        String lower = name.toLowerCase();
        for (String ext : EXTENSIONS)
        {
            if (lower.endsWith(ext))
                return true;
        }
        return false;
    }

    private static void collect(File root, File cur, Map<String, byte[]> out) throws IOException
    {
        File[] children = cur.listFiles();
        if (children == null)
            return;
        for (File f : children)
        {
            if (f.isDirectory())
                collect(root, f, out);
            else if (wanted(f.getName()))
            {
                String rel = root.toPath().relativize(f.toPath()).toString().replace('\\', '/');
                out.put(rel, Files.readAllBytes(f.toPath()));
            }
        }
    }

    public static synchronized void load()
    {
        loaded = true;
        zip = null;
        version = 0;
        Map<String, byte[]> files = new TreeMap<>();
        try
        {
            collect(dir(), dir(), files);
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.error("[rgnpc] Failed to read model files", e);
            return;
        }
        if (files.isEmpty())
        {
            LoggingHandler.sulog.warn("[rgnpc] No model files found in {} - rgnpc entities will fall back to the one "
                    + "model that still ships in the jar. Drop the model pack there.", dir());
            return;
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int h = 1;
        try (ZipOutputStream z = new ZipOutputStream(bos))
        {
            z.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> e : files.entrySet())
            {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
                h = 31 * h + e.getKey().hashCode();
                h = 31 * h + Arrays.hashCode(e.getValue());
            }
        }
        catch (IOException e)
        {
            LoggingHandler.sulog.error("[rgnpc] Failed to zip model files", e);
            return;
        }
        zip = bos.toByteArray();
        version = h;
        LoggingHandler.sulog.info("[rgnpc] Prepared {} model file(s) ({} KB compressed) for client sync", files.size(),
                zip.length / 1024);
    }

    /** Start the whole set for this player. Nothing goes out here: the tick handler releases it, a chunk per ack. */
    public static void sendTo(ServerPlayer player)
    {
        sendFrom(player, 0);
    }

    /**
     * Queue the set for this player from {@code startChunk} onwards, so an interrupted transfer picks up where it
     * stopped instead of starting again.
     *
     * <p>A prefix is all the resume needs to describe, because chunks are queued in order, TCP delivers them in
     * order, and the client only ever keeps a leading run of them. An out of range {@code startChunk} (a client
     * claiming more than exists, or a pack that has been rechunked since) falls back to sending everything, which
     * is correct rather than merely safe: the alternative is handing them a truncated zip.
     */
    public static void sendFrom(ServerPlayer player, int startChunk)
    {
        beginFor(player, startChunk);
    }

    /**
     * The single place a transfer is allowed to start, so the cooldown cannot be walked around by a second caller.
     *
     * @return {@code true} when the transfer was queued, {@code false} when there is nothing to send or the player is
     *         inside their offer cooldown.
     */
    private static boolean beginFor(ServerPlayer player, int startChunk)
    {
        if (!loaded)
            load();
        if (zip == null || zip.length == 0)
            return false;
        UUID id = player.getUUID();
        if (!offerAllowed(player, id))
            return false;
        int total = totalChunks();
        int from = startChunk < 0 || startChunk >= total ? 0 : startChunk;
        TRANSFERS.put(id, new Transfer(from));
        return true;
    }

    /**
     * Count this offer of the pack against the player's window, and say whether it may go ahead.
     *
     * <p>Counting offers rather than joins is what keeps an ordinary player out of this: a returning client with a
     * warm cache reports COMPLETE and is never offered anything, so it never registers here however often it relogs.
     * Only a player actually being handed the pack again and again, which is precisely the failover loop, reaches the
     * cap. See {@link #OFFER_WINDOW_MS} for the sizing and the cross shard caveat.
     *
     * <p>Refusing costs the player nothing they need to play: ragnarok NPCs draw as the fallback Steve
     * ({@link RgNpcFallback}) until the pack does arrive, and being in the world without the art beats bouncing
     * between shards for ever.
     */
    private static boolean offerAllowed(ServerPlayer player, UUID id)
    {
        long now = System.currentTimeMillis();
        OfferHistory history = OFFERS.get(id);
        if (history == null || now - history.windowStart >= OFFER_WINDOW_MS)
        {
            history = new OfferHistory(now);
            OFFERS.put(id, history);
        }
        if (history.offers >= MAX_OFFERS_PER_WINDOW)
        {
            if (!history.suppressionLogged)
            {
                history.suppressionLogged = true;
                long seconds = Math.max(0L, (history.windowStart + OFFER_WINDOW_MS - now + 999L) / 1000L);
                LoggingHandler.sulog.info("[rgnpc] {} has been offered the model pack {} times in the last {} minute(s);"
                                + " sending nothing this join, eligible again in about {}s. They will see fallback"
                                + " models until then.",
                        player.getGameProfile().getName(), history.offers, OFFER_WINDOW_MS / 60000L, seconds);
            }
            return false;
        }
        history.offers++;
        return true;
    }

    /** How many chunks the current pack is, or 0 when there is nothing to send. */
    private static int totalChunks()
    {
        return zip == null || zip.length == 0 ? 0 : (zip.length + CHUNK - 1) / CHUNK;
    }

    /**
     * Start the grace clock for a joining player. Nothing is queued yet: the client is about to say what it holds,
     * and the whole point is to send nothing at all when the answer is "the same version you have".
     */
    public static void expectReport(ServerPlayer player)
    {
        AWAITING_REPORT.put(player.getUUID(), REPORT_GRACE_TICKS);
    }

    /**
     * A client has said what it holds: either its join report, or its acknowledgement of a chunk. Both carry the
     * same thing, so they are handled as one: the next chunk to send is whatever the client says it is missing.
     *
     * <p>The common case by far is a returning player with a warm cache reporting the current version COMPLETE, and
     * it costs zero bytes. A report that arrives after the grace ran out is still honoured: the grace expiring sends
     * nothing, it does not decide anything, so a client that speaks late is served exactly as one that spoke on time.
     */
    public static void reportFrom(ServerPlayer player, int clientVersion, int haveChunks)
    {
        UUID id = player.getUUID();
        boolean joinReport = AWAITING_REPORT.remove(id) != null;
        // A client whose grace expired was sent nothing at all, so its clock is gone and it has no transfer. A report
        // that turns up afterwards is still the first thing we have heard from it, so treat it as the join report:
        // without this, speaking late would lock the client out of the pack for the rest of the session.
        boolean lateReport = SILENT.remove(id);
        Transfer transfer = TRANSFERS.get(id);
        if (!joinReport && !lateReport && transfer == null)
            return;   // nothing expected from this player: a stray or duplicate report must not start a transfer
        if (!loaded)
            load();
        if (zip == null || zip.length == 0)
        {
            TRANSFERS.remove(id);
            return;
        }
        String name = player.getGameProfile().getName();
        int total = totalChunks();
        if (clientVersion == version && haveChunks == PacketRgNpcAssetsHave.COMPLETE)
        {
            TRANSFERS.remove(id);
            // This client wants nothing more, so its offer history is dead weight: clearing it means a player who
            // finished the transfer and then relogs is treated as the ordinary case it is, never as a loop.
            OFFERS.remove(id);
            if (transfer == null)
                LoggingHandler.sulog.debug("[rgnpc] {} already has v{}; sending nothing.", name, version);
            else
                LoggingHandler.sulog.info("[rgnpc] {} now holds v{} in full.", name, version);
            return;
        }
        int from = clientVersion == version && haveChunks > 0 && haveChunks < total ? haveChunks : 0;
        if (transfer == null)
        {
            // A fresh start, so it goes through the same gate as every other one. An acknowledgement of a chunk
            // already in flight takes the path below instead and is never counted as a new offer.
            if (beginFor(player, from) && from > 0)
                LoggingHandler.sulog.info("[rgnpc] Resuming v{} for {} at chunk {} of {}.", version, name, from, total);
            return;
        }
        if (from == 0 && transfer.sentLast)
        {
            // Every chunk went out and the client is back at nothing, so the pack did not apply on its side. Sending
            // it again would only repeat that, at the cost of the whole pack each time.
            TRANSFERS.remove(id);
            LoggingHandler.sulog.warn("[rgnpc] {} received all of v{} but reports holding none of it; not resending.",
                    name, version);
            return;
        }
        transfer.next = from;
        transfer.inFlight = false;
    }


    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
        {
            TRANSFERS.clear();
            AWAITING_REPORT.clear();
            SILENT.clear();
            OFFERS.clear();
            return;
        }
        expireReportGrace(server);
        // Before the TRANSFERS shortcut below, because the bookkeeping outlives any transfer.
        if (++sinceSweep >= SWEEP_INTERVAL_TICKS)
        {
            sinceSweep = 0;
            purgeBookkeeping(server);
        }
        if (TRANSFERS.isEmpty())
            return;
        byte[] payload = zip;
        if (payload == null)
        {
            TRANSFERS.clear();
            return;
        }
        int total = (payload.length + CHUNK - 1) / CHUNK;

        // Drop anyone who left mid transfer FIRST, in its own pass, and anyone whose transfer has run past the last
        // chunk (their final acknowledgement normally removes them first). Doing it here rather than inside the
        // budgeted loop below means a departed player cannot be left behind simply because the budget ran out
        // before their turn came round.
        TRANSFERS.entrySet().removeIf(e ->
        {
            ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
            return p == null || p.hasDisconnected() || e.getValue().next >= total;
        });
        if (TRANSFERS.isEmpty())
            return;

        // Serve round robin from a rotating start, so that when the global budget binds it is shared out rather
        // than always spent on whoever happens to come first in the map. Without this a mass arrival would see the
        // first player or two transfer at full speed while the rest sat at zero and timed out waiting.
        List<UUID> ids = new ArrayList<>(TRANSFERS.keySet());
        int start = Math.floorMod(rotation++, ids.size());
        int budget = GLOBAL_CHUNKS_PER_TICK;
        for (int k = 0; k < ids.size() && budget > 0; k++)
        {
            UUID id = ids.get((start + k) % ids.size());
            Transfer transfer = TRANSFERS.get(id);
            if (transfer == null || transfer.inFlight)
                continue;   // still waiting on the client to take the last chunk
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            if (player == null)
                continue;
            int i = transfer.next;
            int off = i * CHUNK;
            byte[] part = Arrays.copyOfRange(payload, off, Math.min(off + CHUNK, payload.length));
            NetworkUtils.sendTo(new PacketRgNpcAssets(version, i, total, part), player);
            transfer.inFlight = true;
            if (i == total - 1)
                transfer.sentLast = true;
            budget--;
        }
    }

    /**
     * Stop waiting on anyone who never answered the handshake, and drop the clock for anyone who left. Nothing is
     * sent to a client that has not spoken.
     *
     * <p>A client silent for ten seconds is either older than the handshake or not reading its connection at all, and
     * for both kinds a chunk is pure cost: the old client will never acknowledge it, so it buys one chunk of a pack
     * it can never finish, and the stalled client is stalled precisely because there are already bytes it cannot
     * drain. This path used to push the whole pack, then (from 1.1.359) a single 256 KB chunk. On 2026-09-21 the two
     * players who never completed the handshake were also the only two timing out every thirty seconds, so even the
     * single chunk is now withheld until the client asks.
     *
     * <p>That is safe because a client without the pack is not a broken client: every ragnarok model location goes
     * through {@link RgNpcFallback} first, which draws a plain Steve from a model that is always in the jar. Silent
     * clients see fallback NPCs, which is a cosmetic loss, instead of being unable to stay connected at all. Speaking
     * late still works: {@link #SILENT} makes a report that arrives after the grace behave exactly like a join report.
     *
     * <p>Counting down per tick rather than comparing wall clocks keeps this in the same units as everything else
     * here and means a paused server does not burn the grace of a player who is not being ticked either.
     */
    private static void expireReportGrace(net.minecraft.server.MinecraftServer server)
    {
        if (AWAITING_REPORT.isEmpty())
            return;
        for (Iterator<Map.Entry<UUID, Integer>> it = AWAITING_REPORT.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<UUID, Integer> entry = it.next();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null || player.hasDisconnected())
            {
                it.remove();
                continue;
            }
            int left = entry.getValue() - 1;
            if (left > 0)
            {
                entry.setValue(left);
                continue;
            }
            it.remove();
            // Once per player per session: the clock is only ever restarted by a new login.
            SILENT.add(player.getUUID());
            LoggingHandler.sulog.info("[rgnpc] {} never reported what model pack they hold within {} ticks, so nothing"
                            + " was sent. Ragnarok NPCs draw the fallback for them until their client reports.",
                    player.getGameProfile().getName(), REPORT_GRACE_TICKS);
        }
    }

    /**
     * Throw away bookkeeping that can no longer matter: offer windows that have run out, and silent markers for
     * players who are no longer here. Neither map is allowed to grow with the number of players ever seen.
     */
    private static void purgeBookkeeping(net.minecraft.server.MinecraftServer server)
    {
        if (!OFFERS.isEmpty())
        {
            long now = System.currentTimeMillis();
            OFFERS.entrySet().removeIf(e -> now - e.getValue().windowStart >= OFFER_WINDOW_MS);
        }
        if (!SILENT.isEmpty())
        {
            SILENT.removeIf(id ->
            {
                ServerPlayer p = server.getPlayerList().getPlayer(id);
                return p == null || p.hasDisconnected();
            });
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event)
    {
        AWAITING_REPORT.clear();
        TRANSFERS.clear();
        SILENT.clear();
        OFFERS.clear();
        loaded = false;
        zip = null;
        version = 0;
    }

    /**
     * Re-read the folder and push it to everyone online. Clients reload their resources once the set lands.
     *
     * <p>The offer cooldown is reset here on purpose: a reload means the pack has genuinely changed under everyone,
     * so earlier offers were for a different version and must not hold the new one back. It is also a deliberate
     * admin action rather than a login loop.
     */
    public static void reload()
    {
        OFFERS.clear();
        load();
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        for (ServerPlayer player : server.getPlayerList().getPlayers())
            sendTo(player);
    }
}
