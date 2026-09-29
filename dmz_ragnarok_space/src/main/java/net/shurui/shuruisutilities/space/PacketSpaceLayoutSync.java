package net.shurui.shuruisutilities.space;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client sync of the SPACE LAYOUT inputs, so the client derives every space body (generated planet, star,
 * black hole, asteroid) at the exact coordinates the server does. Space bodies are no longer entities: the client draws
 * them by running the identical pure derivations the server runs, and those derivations read two things the client cannot
 * compute on its own:
 *
 * <ul>
 *   <li>the LAYOUT NUMBERS (generated/star/black-hole sector sizes and densities, the fixed ring radius and jitter),
 *       which live in the server's SpacePlanets config; and</li>
 *   <li>the FIXED planet BODIES (key, position, radius) that every other body must avoid overlapping, which come from
 *       DMZ's server-side destination list and the loaded dimensions.</li>
 * </ul>
 *
 * <p>Both are pushed here into the same derivation classes the server uses (the numbers into their volatile config
 * fields, the fixed bodies into {@link SpaceLayout}), so after this packet the client's {@code generatedNear} /
 * {@code starsNear} / {@code blackHolesNear} / {@code asteroidsNear} return the same sets as the server's. That is what
 * keeps what the client draws in agreement with server-side landing and the star/black-hole hazards.
 *
 * <p>WHEN IT IS SENT: on player login (so a joining client is correct immediately) and to everyone on a config reload or
 * datapack reload (the only two moments the layout can change). See {@link SpaceLayoutSync}.
 */
public class PacketSpaceLayoutSync implements ISUPacket
{
    // layout numbers, mirrored from the derivation classes' config-baked fields.
    private int generatedSectorSize;
    private double generatedDensity;
    private int starSectorSize;
    private double starDensity;
    private int blackHoleSectorSize;
    private double blackHoleDensity;
    private double ringRadius;
    private double radiusJitter;
    private double drawDistance;

    // the fixed planet bodies to avoid overlapping (key + position + radius).
    private List<FixedBody> fixed = new ArrayList<>();

    // generated-planet owners (planet id -> owning guild NAME), so the client can draw the correct owner on a claimed
    // planet's nameplate. Tiny: one entry per claimed planet. Resolved to guild names server-side so the client needs no
    // guild data.
    private java.util.Map<String, String> owners = new java.util.HashMap<>();

    // destroyed generated-planet ids: a client must suppress these everywhere (no draw, and its own re-derivation drops
    // them). Tiny: one entry per rubble cell.
    private java.util.Set<String> destroyed = new java.util.HashSet<>();

    // per-cell generation counters (cell key -> generation). Only bumped cells appear; an absent cell is generation 0.
    // This is what lets a client derive the SAME respawned planet the server does for a cell that has been bumped.
    private java.util.Map<String, Integer> generations = new java.util.HashMap<>();

    // currently-DESTROYED cells (cell key -> the generation the destroyed planet is on). This is the field the client
    // could not otherwise recover: a destroyed planet id is a one-way fold of its cell, so without the cell key the
    // client cannot place the wreck the destroyed planet left. Carried as cell->generation (NOT as ids) because that is
    // exactly what GeneratedPlanets.formerBody needs to re-derive the former planet's position and size. Tiny: one entry
    // per rubble cell. Built from the SAME destroyed store the `destroyed` id set above is, in current(), so the two can
    // never disagree about what is rubble.
    private java.util.Map<String, Integer> destroyedCells = new java.util.HashMap<>();

    // stamped sizes (planet id -> size in blocks per side the planet was stamped at). The client cannot re-derive these
    // for an already-stamped planet once the surface range changes, so without them the drawn body radius would not match
    // the server's landing/collision size. Tiny: one entry per stamped planet. Absent id -> the client falls back to the
    // derived size, which both sides compute identically.
    private java.util.Map<String, Integer> stampedSizes = new java.util.HashMap<>();

    // the seven SUPER bodies: id, current position and claimed flag. Positions are authoritative server state now (a body
    // relocates when its ball is lost), so the client can no longer derive them; it draws exactly these. A CLAIMED body
    // draws as a dragon ball and is not landable, an unclaimed one draws stone-grey. Built from the same SuperPlanetData
    // the landing/claim/relocate paths write, so the two sides never disagree.
    private java.util.List<SuperBody> superBodies = new java.util.ArrayList<>();

    // how near (blocks) the CLIENT must be to a super body before it draws at all. Its own knob (default 1000), synced so
    // the render cull matches the server config; the general bodyDrawDistance does not govern super bodies.
    private double superRenderDistance;

    // surface-ball body anchors: a packed surface cell (SurfaceDimension gx in the high 32 bits, gz in the low 32) mapped
    // to the SPACE-body position of the planet on that cell. Built from the Black Star scatter assignments so the client
    // radar HUD can point at a scattered ball's PLANET (its space body) instead of its raw planet_surface coordinate. Tiny
    // (up to seven entries). Super bodies are excluded: the client derives those from the superBodies list above.
    private java.util.Map<Long, Vec3> surfaceBallBodies = new java.util.HashMap<>();

    /** One super body's synced snapshot: its id, current world position and whether its ball has been claimed. */
    public record SuperBody(String id, Vec3 pos, boolean claimed)
    {
    }

    public PacketSpaceLayoutSync()
    {
    }

    // build the packet from the current server-side layout state. Reads exactly the fields the client needs to match.
    public static PacketSpaceLayoutSync current(net.minecraft.server.MinecraftServer server)
    {
        PacketSpaceLayoutSync p = new PacketSpaceLayoutSync();
        p.generatedSectorSize = GeneratedPlanets.sectorSize;
        p.generatedDensity = GeneratedPlanets.density;
        p.starSectorSize = StarPositions.sectorSize;
        p.starDensity = StarPositions.density;
        p.blackHoleSectorSize = BlackHolePositions.sectorSize;
        p.blackHoleDensity = BlackHolePositions.density;
        p.ringRadius = PlanetPositions.ringRadius;
        p.radiusJitter = PlanetPositions.radiusJitter;
        p.drawDistance = PlanetSpawnModule.bodyDrawDistance();
        p.superRenderDistance = PlanetSpawnModule.superRenderDistance();
        p.fixed = SpaceLayout.fixedBodies(server);
        p.owners = resolveOwners(server);
        if (server != null)
        {
            GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
            p.destroyed = claims.destroyedPlanetIds();
            p.generations = new java.util.HashMap<>(claims.generationsView());
            // the size each stamped planet was actually built at, so the client draws the exact body radius the server
            // uses even for planets stamped before the surface range changed.
            p.stampedSizes = new java.util.HashMap<>(claims.stampedSizes());
            // the seven super bodies (id, position, claimed).
            p.superBodies = SuperPlanetData.get(server).snapshot();
            // the Black Star scatter's planet anchors, so the client radar HUD can map each scattered ball's surface cell
            // to its planet's space body while a player flies between planets.
            p.surfaceBallBodies = buildSurfaceBallBodies(server);
            // the rubble cells and their destruction generation, the wreck derivation's input. Built from the same
            // destroyed store as p.destroyed just above, so the id set and the cell map are always in step.
            for (java.util.Map.Entry<String, GeneratedPlanetClaims.DestroyedCell> e : claims.destroyedCells().entrySet())
            {
                p.destroyedCells.put(e.getKey(), e.getValue().generation);
            }
        }
        return p;
    }

    // build the surface-ball body anchors from the Black Star scatter assignments. Each assignment carries the planet's
    // SPACE cell key, from which the server derives the live body position (no anchor needed, generationFor is read
    // server-side); the planet's SURFACE cell is a pure function of its id. A destroyed / regenerated cell (generatedFor
    // returns null or a different id) is skipped, so a stale assignment never anchors a body that no longer exists.
    private static java.util.Map<Long, Vec3> buildSurfaceBallBodies(net.minecraft.server.MinecraftServer server)
    {
        java.util.Map<Long, Vec3> out = new java.util.HashMap<>();
        if (server == null)
        {
            return out;
        }
        for (ApophisPlanetData.Assignment a : ApophisPlanetData.get(server).assignments().values())
        {
            int[] cell = GeneratedPlanets.parseCellKey(a.cellKey);
            if (cell == null)
            {
                continue;
            }
            GeneratedPlanets.Generated body = GeneratedPlanets.generatedFor(server, cell[0], cell[1], cell[2]);
            if (body == null || !body.id.equals(a.planetId))
            {
                continue;
            }
            long packed = SurfaceDimension.packCell(SurfaceDimension.cellX(a.planetId),
                    SurfaceDimension.cellZ(a.planetId));
            out.put(packed, body.position);
        }
        return out;
    }

    // build the planet id -> owning guild NAME map from the authoritative claim store, resolving each guild id to its
    // current name so the client draws the name directly. A stale claim whose guild has disbanded is skipped (reads as
    // unclaimed on the client), matching how the command paths treat a dead owner.
    private static java.util.Map<String, String> resolveOwners(net.minecraft.server.MinecraftServer server)
    {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        if (server == null)
        {
            return out;
        }
        java.util.Map<String, String> claims = GeneratedPlanetClaims.get(server).claims();
        for (java.util.Map.Entry<String, String> e : claims.entrySet())
        {
            net.shurui.shuruisutilities.guilds.model.Guild guild =
                    net.shurui.shuruisutilities.guilds.GuildManager.byId(e.getValue());
            if (guild != null)
            {
                out.put(e.getKey(), guild.name);
            }
        }
        return out;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeInt(generatedSectorSize);
        buf.writeDouble(generatedDensity);
        buf.writeInt(starSectorSize);
        buf.writeDouble(starDensity);
        buf.writeInt(blackHoleSectorSize);
        buf.writeDouble(blackHoleDensity);
        buf.writeDouble(ringRadius);
        buf.writeDouble(radiusJitter);
        buf.writeDouble(drawDistance);
        buf.writeDouble(superRenderDistance);
        buf.writeVarInt(fixed.size());
        for (FixedBody b : fixed)
        {
            buf.writeUtf(b.key);
            buf.writeDouble(b.position.x);
            buf.writeDouble(b.position.y);
            buf.writeDouble(b.position.z);
            buf.writeFloat(b.radius);
        }
        buf.writeVarInt(owners.size());
        for (java.util.Map.Entry<String, String> e : owners.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeUtf(e.getValue());
        }
        buf.writeVarInt(destroyed.size());
        for (String id : destroyed)
        {
            buf.writeUtf(id);
        }
        buf.writeVarInt(generations.size());
        for (java.util.Map.Entry<String, Integer> e : generations.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }
        buf.writeVarInt(destroyedCells.size());
        for (java.util.Map.Entry<String, Integer> e : destroyedCells.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }
        buf.writeVarInt(stampedSizes.size());
        for (java.util.Map.Entry<String, Integer> e : stampedSizes.entrySet())
        {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }
        buf.writeVarInt(superBodies.size());
        for (SuperBody b : superBodies)
        {
            buf.writeUtf(b.id());
            buf.writeDouble(b.pos().x);
            buf.writeDouble(b.pos().y);
            buf.writeDouble(b.pos().z);
            buf.writeBoolean(b.claimed());
        }
        buf.writeVarInt(surfaceBallBodies.size());
        for (java.util.Map.Entry<Long, Vec3> e : surfaceBallBodies.entrySet())
        {
            buf.writeLong(e.getKey());
            buf.writeDouble(e.getValue().x);
            buf.writeDouble(e.getValue().y);
            buf.writeDouble(e.getValue().z);
        }
    }

    public static PacketSpaceLayoutSync decode(FriendlyByteBuf buf)
    {
        PacketSpaceLayoutSync p = new PacketSpaceLayoutSync();
        p.generatedSectorSize = buf.readInt();
        p.generatedDensity = buf.readDouble();
        p.starSectorSize = buf.readInt();
        p.starDensity = buf.readDouble();
        p.blackHoleSectorSize = buf.readInt();
        p.blackHoleDensity = buf.readDouble();
        p.ringRadius = buf.readDouble();
        p.radiusJitter = buf.readDouble();
        p.drawDistance = buf.readDouble();
        p.superRenderDistance = buf.readDouble();
        int n = buf.readVarInt();
        List<FixedBody> bodies = new ArrayList<>(n);
        for (int i = 0; i < n; ++i)
        {
            String key = buf.readUtf();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            float radius = buf.readFloat();
            bodies.add(new FixedBody(key, new Vec3(x, y, z), radius));
        }
        p.fixed = bodies;
        int m = buf.readVarInt();
        java.util.Map<String, String> owners = new java.util.HashMap<>(m);
        for (int i = 0; i < m; ++i)
        {
            String id = buf.readUtf();
            String name = buf.readUtf();
            owners.put(id, name);
        }
        p.owners = owners;
        int d = buf.readVarInt();
        java.util.Set<String> destroyed = new java.util.HashSet<>(d);
        for (int i = 0; i < d; ++i)
        {
            destroyed.add(buf.readUtf());
        }
        p.destroyed = destroyed;
        int g = buf.readVarInt();
        java.util.Map<String, Integer> generations = new java.util.HashMap<>(g);
        for (int i = 0; i < g; ++i)
        {
            String cellKey = buf.readUtf();
            generations.put(cellKey, buf.readVarInt());
        }
        p.generations = generations;
        int r = buf.readVarInt();
        java.util.Map<String, Integer> destroyedCells = new java.util.HashMap<>(r);
        for (int i = 0; i < r; ++i)
        {
            String cellKey = buf.readUtf();
            destroyedCells.put(cellKey, buf.readVarInt());
        }
        p.destroyedCells = destroyedCells;
        int s = buf.readVarInt();
        java.util.Map<String, Integer> stampedSizes = new java.util.HashMap<>(s);
        for (int i = 0; i < s; ++i)
        {
            String id = buf.readUtf();
            stampedSizes.put(id, buf.readVarInt());
        }
        p.stampedSizes = stampedSizes;
        int sb = buf.readVarInt();
        java.util.List<SuperBody> superBodies = new java.util.ArrayList<>(sb);
        for (int i = 0; i < sb; ++i)
        {
            String id = buf.readUtf();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            boolean claimed = buf.readBoolean();
            superBodies.add(new SuperBody(id, new Vec3(x, y, z), claimed));
        }
        p.superBodies = superBodies;
        int ab = buf.readVarInt();
        java.util.Map<Long, Vec3> anchors = new java.util.HashMap<>(ab);
        for (int i = 0; i < ab; ++i)
        {
            long packed = buf.readLong();
            double x = buf.readDouble();
            double y = buf.readDouble();
            double z = buf.readDouble();
            anchors.put(packed, new Vec3(x, y, z));
        }
        p.surfaceBallBodies = anchors;
        return p;
    }

    // CLIENT: push the received numbers into the same volatile derivation fields the server bakes into, and the fixed
    // bodies into SpaceLayout, so the client's derivations now match the server's exactly. Running on the client thread
    // (enqueued below) means the render code reads a fully-applied snapshot, never a half-updated one.
    @Override
    public void handle(NetworkEvent.Context context)
    {
        GeneratedPlanets.sectorSize = generatedSectorSize;
        GeneratedPlanets.density = generatedDensity;
        StarPositions.sectorSize = starSectorSize;
        StarPositions.density = starDensity;
        BlackHolePositions.sectorSize = blackHoleSectorSize;
        BlackHolePositions.density = blackHoleDensity;
        PlanetPositions.ringRadius = ringRadius;
        PlanetPositions.radiusJitter = radiusJitter;
        SpaceLayout.setClientDrawDistance(drawDistance);
        SpaceLayout.setClientFixed(fixed);
        // mirror the server's authoritative fixed-body positions into the coordinated layout snapshot so the client's
        // PlanetPositions.position() (read by earth(), the moon-parent landing and the generated-planet rejection)
        // returns exactly what the server placed, never a re-derived value that could drift from it.
        PlanetPositions.setClientLayout(fixed);
        SpaceLayout.setClientOwners(owners);
        SpaceLayout.setClientDestroyed(destroyed);
        SpaceLayout.setClientGenerations(generations);
        SpaceLayout.setClientDestroyedCells(destroyedCells);
        SpaceLayout.setClientStampedSizes(stampedSizes);
        SpaceLayout.setClientSuperBodies(superBodies);
        SpaceLayout.setClientSuperRenderDistance(superRenderDistance);
        SpaceLayout.setClientSurfaceBallBodies(surfaceBallBodies);
    }

    public static void handler(final PacketSpaceLayoutSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
