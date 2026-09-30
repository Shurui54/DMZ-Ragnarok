package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;

/**
 * The single agreement point between the SERVER and the CLIENT about where space bodies are. Every space body (generated
 * planet, star, black hole, asteroid) is a pure function of the world position, the layout config numbers (sector sizes,
 * densities, the fixed ring radius/jitter) and the set of FIXED planet bodies it must not overlap. The config numbers
 * are already config-baked into the derivation classes; the fixed-body set is the one thing the client cannot compute on
 * its own (it comes from DMZ's server-side destination list and the loaded dimensions), so it is synced here.
 *
 * <p>USAGE. Every derivation that used to iterate {@code PlanetRegistry.bodies(server)} now calls
 * {@link #fixedBodies(MinecraftServer)} instead:
 * <ul>
 *   <li>SERVER (server != null): returns the live list straight from {@link PlanetRegistry#bodies}, so the server is
 *       always authoritative and never depends on the sync.</li>
 *   <li>CLIENT (server == null): returns the last snapshot pushed by {@link PacketSpaceLayoutSync}, so the client runs
 *       the identical rejection maths against the identical fixed bodies and derives the identical layout. Until the
 *       first snapshot arrives the list is empty, which only means a client that has not yet been told the fixed bodies
 *       draws nothing rather than drawing bodies where the server does not think they are.</li>
 * </ul>
 *
 * <p>KEEPING BOTH SIDES IN STEP. The config numbers are pushed into the derivation classes on the config bake (server)
 * and by {@link PacketSpaceLayoutSync} on receipt (client), and the fixed-body list is pushed here by the same packet.
 * The packet is sent to a player on login and to everyone on a config reload / datapack reload, which are exactly the two
 * moments the layout can change (see {@link PacketSpaceLayoutSync}). So after any change the client re-derives against the
 * same inputs the server does, which is what keeps landing and the hazards agreeing with what is drawn.
 */
public final class SpaceLayout
{
    private SpaceLayout()
    {
    }

    // Default and cap for the client body draw distance (blocks), see PlanetSpawnModule's bodyDrawDistance config. The
    // default is generous on purpose: these are a handful of textured cubes, trivial next to terrain, and the whole point
    // is that a star or planet reads as a distant landmark. It must comfortably exceed every hazard field so a hazard is
    // always visible before it can affect a player. The largest hazard field is a black hole's PULL field, which was
    // widened from the old 6x to radius * PlanetSpawnModule.blackHoleInfluenceFactor (config, default 30, capped at 50):
    // at the cap that is max black hole visual radius 80 x 50 = 4000 blocks; a star's burn field outer edge is max radius
    // 82 x 2.5 = 205 blocks. The default 8000 still comfortably exceeds even the 4000 worst case, so a hazard is drawn
    // long before it can touch a player at the default. The cap bounds the per-frame cell walk on the client.
    public static final double DEFAULT_DRAW_DISTANCE = 8000.0;
    public static final double MAX_DRAW_DISTANCE = 16000.0;
    // The lowest an operator may set the draw distance to. NOTE: with the widened black-hole influence (up to 4000 blocks
    // at the config cap) this 1024 floor NO LONGER guarantees a hole is drawn before its pull can reach a player: an
    // operator who both maxes blackHoleInfluenceFactor and minimises this draw distance can be tugged by an undrawn hole.
    // The default draw distance (8000) is clear of it; only that extreme combination is not. Raising this floor is a
    // hazard-tuning decision that belongs with the gravity config, so it is flagged rather than changed here.
    public static final double MIN_DRAW_DISTANCE = 1024.0;

    // How near, in blocks to a body's SURFACE (centre distance minus the body's radius, NOT the centre), a player must be
    // for BOTH of two things: the body's nameplate to draw (SpaceBodyRenderer) AND that body to be a legal planet-buster
    // target (PlanetBusterModule). These are deliberately the SAME field, because the user's rule is defined in terms of
    // the label: "you may only destroy a planet from the same distance its name shows from." So the label gate and the
    // destruction range MUST stay identical, and the safe way to guarantee that is to read one constant, not two equal
    // literals. Do NOT reintroduce a second copy of this number anywhere. This workspace has already been bitten twice by
    // two sides deriving the same geometry independently (the destroyed-planet client/server derivation, and the surface
    // rim vs boundary bug fixed earlier today), so keep the two rules physically the same constant. Measured to the
    // SURFACE, not the centre, because bodies vary from about 24 to 82 half-extent: a big body and a small one both show
    // their label, and both become targetable, at the same visual closeness rather than the bigger one popping in later.
    public static final double LABEL_DISTANCE = 300.0;

    // The client's copy of the body draw distance, pushed by PacketSpaceLayoutSync. Read on the client render thread.
    // Defaults to the generous default so a client that has not yet been synced still draws bodies at a sane range.
    private static volatile double clientDrawDistance = DEFAULT_DRAW_DISTANCE;

    public static void setClientDrawDistance(double distance)
    {
        clientDrawDistance = distance;
    }

    public static double clientDrawDistance()
    {
        return clientDrawDistance;
    }

    // The client's copy of the fixed-body set, pushed by PacketSpaceLayoutSync. volatile: written on the client network
    // thread (via the packet handler enqueued to the client thread) and read on the client render thread. Empty until the
    // first sync arrives. Never touched on a dedicated server, where fixedBodies() takes the live path instead.
    private static volatile List<FixedBody> clientFixed = Collections.emptyList();

    // The client's copy of the generated-planet OWNER map (planet id -> owning guild name), pushed by the same sync. Tiny
    // (one entry per CLAIMED planet). Used only by the renderer to draw a generated planet's nameplate owner; an id not
    // present here is unclaimed and draws the translated "Unclaimed" word. The planet NAME is derived client-side from the
    // id, so only the owner needs syncing.
    private static volatile java.util.Map<String, String> clientOwners = Collections.emptyMap();

    // Replace the client fixed-body snapshot. Called only by the sync packet handler on the client.
    public static void setClientFixed(List<FixedBody> bodies)
    {
        clientFixed = bodies == null ? Collections.emptyList() : bodies;
    }

    // The client's copy of the DESTROYED-planet id set, pushed by the same sync. An id present here is rubble and must
    // be suppressed everywhere on the client. Tiny (one entry per rubble cell). volatile, same threading as the others.
    private static volatile java.util.Set<String> clientDestroyed = Collections.emptySet();

    // The client's copy of the per-cell GENERATION counters (cell key -> generation), pushed by the same sync. Only
    // bumped cells appear; an absent cell key is generation 0. This is what lets the client derive the SAME (possibly
    // respawned) planet the server derives for a bumped cell. volatile, same threading.
    private static volatile java.util.Map<String, Integer> clientGenerations = Collections.emptyMap();

    // The client's copy of the currently-DESTROYED cells (cell key -> the generation the destroyed planet is on),
    // pushed by the same sync. This is the ONE thing the wreck derivation needs that the client cannot compute: a
    // destroyed planet id is a one-way fold of its cell, so the client cannot recover the cell (and thus the former
    // planet's position/size) from the id alone. Every currently-rubble cell appears here, INCLUDING generation 0 (unlike
    // clientGenerations, where 0 is the absent default), because a freshly destroyed original planet is on generation 0.
    // volatile, same threading as the others. Empty on a client until the first sync, which just means it derives no
    // wreck until told, never a wreck the server does not have.
    private static volatile java.util.Map<String, Integer> clientDestroyedCells = Collections.emptyMap();

    // The client's copy of the STAMPED-SIZE map (planet id -> the size, in blocks per side, the planet was actually
    // stamped at), pushed by the same sync. This is the one piece of surface geometry the client cannot re-derive once
    // the surface range changes: an already-stamped planet keeps the size its terrain was built at, which now differs
    // from what the client would derive, so the drawn body radius would not match the server's landing/collision size.
    // GeneratedPlanetClaims.stampedSizeForId reads this when server == null. Tiny (one entry per stamped planet), empty
    // until the first sync (an absent id just falls back to the derived size, which both sides compute identically).
    // volatile, same threading as the others.
    private static volatile java.util.Map<String, Integer> clientStampedSizes = Collections.emptyMap();

    // Replace the client owner map. Called only by the sync packet handler on the client.
    public static void setClientOwners(java.util.Map<String, String> owners)
    {
        clientOwners = owners == null ? Collections.emptyMap() : owners;
    }

    // Replace the client stamped-size map. Called only by the sync packet handler on the client.
    public static void setClientStampedSizes(java.util.Map<String, Integer> sizes)
    {
        clientStampedSizes = sizes == null ? Collections.emptyMap() : sizes;
    }

    // The client's copy of the seven SUPER bodies (id, current position, claimed), pushed by the same sync. Positions are
    // authoritative server state now (a body relocates when its ball is lost), so unlike the other space bodies the client
    // does NOT derive these; it renders exactly this snapshot. Empty until the first sync (so nothing draws until told).
    // volatile, same threading as the others.
    private static volatile java.util.List<PacketSpaceLayoutSync.SuperBody> clientSuperBodies = Collections.emptyList();

    // The client's copy of the super-body render cull distance (blocks). A super body only draws within this of the
    // camera; its own knob, independent of the general bodyDrawDistance. Defaulted so a pre-sync client still culls sanely.
    private static volatile double clientSuperRenderDistance = 1000.0;

    // Replace the client super-body snapshot. Called only by the sync packet handler on the client.
    public static void setClientSuperBodies(java.util.List<PacketSpaceLayoutSync.SuperBody> superBodies)
    {
        clientSuperBodies = superBodies == null ? Collections.emptyList() : superBodies;
    }

    // The client's copy of the SURFACE-BALL body anchors: a packed surface cell (see SurfaceDimension, gx in the high 32
    // bits and gz in the low 32) mapped to the SPACE-body position of the planet occupying that cell. Built server-side
    // from the Black Star scatter assignments (each carries the space cell key, which the server derives a live position
    // from), so a client radar HUD can turn a Black Star ball's planet_surface coordinate into the space body to point at
    // when the player is flying between planets. Super bodies are NOT in here: the client derives those from
    // clientSuperBodies (which already carries their positions), matching a super ball's surface cell to a super id.
    // Empty until the first sync; an absent cell just means that ball reads as an off-world marker rather than a bearing.
    // volatile, written on the client network thread (enqueued to the client thread), read on the render thread.
    private static volatile java.util.Map<Long, Vec3> clientSurfaceBallBodies = Collections.emptyMap();

    // Replace the client surface-ball body anchor map. Called only by the sync packet handler on the client.
    public static void setClientSurfaceBallBodies(java.util.Map<Long, Vec3> anchors)
    {
        clientSurfaceBallBodies = anchors == null ? Collections.emptyMap() : anchors;
    }

    // The space-body position of the planet occupying a packed surface cell, or null if none is known. Client render
    // lookup only.
    public static Vec3 clientSurfaceBallBody(long packedCell)
    {
        return clientSurfaceBallBodies.get(packedCell);
    }

    // Replace the client super-body render cull distance. Called only by the sync packet handler on the client.
    public static void setClientSuperRenderDistance(double distance)
    {
        clientSuperRenderDistance = distance > 0.0 ? distance : 1000.0;
    }

    // The client's synced super bodies, for the renderer. Never null.
    public static java.util.List<PacketSpaceLayoutSync.SuperBody> clientSuperBodies()
    {
        return clientSuperBodies;
    }

    // The client's synced super-body render cull distance (blocks).
    public static double clientSuperRenderDistance()
    {
        return clientSuperRenderDistance;
    }

    // The stamped size (blocks per side) synced for a planet id, or 0 if none was synced (an as-yet-unstamped planet, or
    // before the first sync). Client-side lookup only; the server reads its own SavedData instead.
    public static int clientStampedSize(String planetId)
    {
        return clientStampedSizes.getOrDefault(planetId, 0);
    }

    // Replace the client destroyed set. Called only by the sync packet handler on the client.
    public static void setClientDestroyed(java.util.Set<String> destroyed)
    {
        clientDestroyed = destroyed == null ? Collections.emptySet() : destroyed;
    }

    // Replace the client generation map. Called only by the sync packet handler on the client.
    public static void setClientGenerations(java.util.Map<String, Integer> generations)
    {
        clientGenerations = generations == null ? Collections.emptyMap() : generations;
    }

    // Replace the client destroyed-cells map. Called only by the sync packet handler on the client.
    public static void setClientDestroyedCells(java.util.Map<String, Integer> cells)
    {
        clientDestroyedCells = cells == null ? Collections.emptyMap() : cells;
    }

    // The owning guild name for a generated planet id, or "" if unclaimed. Client-side render lookup only.
    public static String ownerOf(String planetId)
    {
        String name = clientOwners.get(planetId);
        return name == null ? "" : name;
    }

    // Whether a generated planet id is DESTROYED. This is the single seam through which every consumer (the pure
    // GeneratedPlanets derivation, the renderer, the landing check, the compass) reads destroyed state, so the client
    // and the server can never derive it independently. It branches EXACTLY like fixedBodies(server):
    //   server != null: read the authoritative SavedData on the server thread the caller is already on.
    //   server == null (the client render thread): read the last synced snapshot.
    // Threading the server through the caller (rather than looking it up here) keeps this on the same thread as the
    // caller, so a client-thread derivation never touches the integrated server's SavedData off-thread. This feature
    // shipped bugs before from the two sides deriving separately; routing every read here is the fix.
    public static boolean isDestroyed(MinecraftServer server, String planetId)
    {
        if (server != null)
        {
            return GeneratedPlanetClaims.get(server).isDestroyed(planetId);
        }
        return clientDestroyed.contains(planetId);
    }

    // Whether a generated planet id has PERSISTED state: it is claimed by a guild, or its surface has been stamped (its
    // size recorded). This is the single seam the inner-system bias (GeneratedPlanets, PlanetPositions.insideInnerSystem)
    // reads to EXEMPT such a planet, so the sun-centred rework never orphans a planet a player has already invested in: a
    // never-visited inner planet (purely derived, no saved data) is biased out of the inner system, but a claimed or
    // stamped one stays exactly where it is. Branches like isDestroyed:
    //   server != null: the authoritative claim / stamped-size store on the caller's own thread.
    //   server == null (client render thread): the synced owners and stamped-size snapshots, the SAME maps the renderer
    //       already reads, so client and server agree on which cells are exempt and never disagree on "draw it but cannot
    //       land" for an inner planet.
    public static boolean isClaimedOrStamped(MinecraftServer server, String planetId)
    {
        if (server != null)
        {
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            // stampedSizes().containsKey is the AUTHORITATIVE "was this planet ever stamped" test (an entry exists only
            // once a stamp began); do NOT use stampedSizeForId, which falls back to the derived size for every planet and
            // would exempt them all. isSurfaceGenerated is the matching flag, checked too for a planet stamped by a build
            // that recorded the flag but not a size.
            return claims.claims().containsKey(planetId)
                    || claims.stampedSizes().containsKey(planetId)
                    || claims.isSurfaceGenerated(planetId);
        }
        // client: the synced owners and stamped-size snapshots (the stamped-size map is the only stamped signal synced,
        // and it carries every stamped planet, so it is the client's authoritative stamped test).
        return !ownerOf(planetId).isEmpty() || clientStampedSize(planetId) > 0;
    }

    // The generation of a cell key. Same server/client branch and same rationale as isDestroyed above: 0 is the
    // original planet, a bumped value derives a wholly different planet in that slot, and both sides read it here.
    public static int generationFor(MinecraftServer server, String cellKey)
    {
        if (server != null)
        {
            return GeneratedPlanetClaims.get(server).generationFor(cellKey);
        }
        return clientGenerations.getOrDefault(cellKey, 0);
    }

    // The currently-DESTROYED cells (cell key -> the generation the destroyed planet is on), the input the wreck
    // derivation needs. Same server/client branch and same rationale as isDestroyed/generationFor above: the wreck is
    // part of the asteroid field, and the client walks that field to reject hazards, so both sides MUST read the rubble
    // set from this one seam and never derive it independently.
    //   server != null: read the authoritative SavedData, mapping each rubble cell to its recorded destruction generation.
    //   server == null (client render thread): read the last synced snapshot.
    // The returned map is a fresh copy the caller may keep; it is tiny (one entry per rubble cell, usually empty), so the
    // per-call allocation is negligible against the cell walk asteroidsNear already does.
    public static java.util.Map<String, Integer> destroyedCells(MinecraftServer server)
    {
        if (server != null)
        {
            java.util.Map<String, GeneratedPlanetClaims.DestroyedCell> live =
                    GeneratedPlanetClaims.get(server).destroyedCells();
            java.util.Map<String, Integer> out = new java.util.HashMap<>(live.size());
            for (java.util.Map.Entry<String, GeneratedPlanetClaims.DestroyedCell> e : live.entrySet())
            {
                out.put(e.getKey(), e.getValue().generation);
            }
            return out;
        }
        return clientDestroyedCells;
    }

    // The fixed planet bodies to reject candidate space bodies against. Server: the live registry list. Client: the last
    // synced snapshot. This is the ONLY place the two sides diverge in how they obtain the set, and both obtain the same
    // VALUES (key/position/radius), so the derivations built on top of it agree.
    // Same one-tick memo as PlanetRegistry.bodies, and needed for the same reason: this is called from inside the
    // cell walks, so without it every rejection test rebuilt the whole wrapper list. Caching the FixedBody objects as
    // well as the Planet list is what removes the last per-cell allocation from the hot path.
    private static long cachedTick = Long.MIN_VALUE;
    private static List<FixedBody> cachedFixed = Collections.emptyList();

    /** Drop the memo; see {@link PlanetRegistry#invalidateCache}, which is the one that calls this. */
    static void invalidateFixedCache()
    {
        cachedTick = Long.MIN_VALUE;
        cachedFixed = Collections.emptyList();
    }

    public static List<FixedBody> fixedBodies(MinecraftServer server)
    {
        if (server != null)
        {
            boolean cacheable = server.isSameThread();
            if (cacheable && server.getTickCount() == cachedTick)
            {
                return cachedFixed;
            }
            List<PlanetRegistry.Planet> live = PlanetRegistry.bodies(server);
            List<FixedBody> out = new ArrayList<>(live.size());
            for (PlanetRegistry.Planet p : live)
            {
                out.add(new FixedBody(p.key, p.position(), p.radius()));
            }
            List<FixedBody> shared = Collections.unmodifiableList(out);
            if (cacheable)
            {
                cachedFixed = shared;
                cachedTick = server.getTickCount();
            }
            return shared;
        }
        return clientFixedLive();
    }

    // CLIENT: the synced fixed bodies with their CURRENT orbital positions. The sync carries each body's key and radius
    // (its position at sync time), but a fixed body ORBITS, so its live position is recomputed from the key through
    // PlanetPositions.orbitPositionAt at the client's synced epoch, giving exactly what the server derives at the same
    // instant. Recomputed at most every CLIENT_LIVE_CACHE_MS so the per-frame cell walks (generatedNear on the render
    // thread call this per cell) do not rebuild the list hundreds of times a frame; a body moves well under a block in
    // that window, far inside every landing/overlap margin. Render thread only, so the plain fields need no lock.
    private static final long CLIENT_LIVE_CACHE_MS = 50L;
    private static long clientLiveStamp = Long.MIN_VALUE;
    private static List<FixedBody> clientLive = Collections.emptyList();

    private static List<FixedBody> clientFixedLive()
    {
        List<FixedBody> src = clientFixed;
        if (src.isEmpty())
        {
            return src;
        }
        long now = System.currentTimeMillis();
        List<FixedBody> cached = clientLive;
        if (cached.size() == src.size() && now - clientLiveStamp < CLIENT_LIVE_CACHE_MS)
        {
            return cached;
        }
        long epoch = OrbitClock.epochMillis();
        List<FixedBody> out = new ArrayList<>(src.size());
        for (FixedBody fb : src)
        {
            out.add(new FixedBody(fb.key, PlanetPositions.orbitPositionAt(fb.key, epoch), fb.radius));
        }
        List<FixedBody> shared = Collections.unmodifiableList(out);
        clientLive = shared;
        clientLiveStamp = now;
        return shared;
    }
}
