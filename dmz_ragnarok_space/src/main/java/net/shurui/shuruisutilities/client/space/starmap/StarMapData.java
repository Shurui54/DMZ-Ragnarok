package net.shurui.shuruisutilities.client.space.starmap;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.space.FixedBody;
import net.shurui.shuruisutilities.space.GeneratedPlanets;
import net.shurui.shuruisutilities.space.GeneratedSystems;
import net.shurui.shuruisutilities.space.PacketSpaceLayoutSync;
import net.shurui.shuruisutilities.space.PlanetInfoLabels;
import net.shurui.shuruisutilities.space.PlanetPositions;
import net.shurui.shuruisutilities.space.SpaceLayout;
import net.shurui.shuruisutilities.space.SurfaceStamp;

/**
 * Builds the SPACE STAR MAP's body list, and the labels it shows, entirely from the SYNCED client space layout. Every
 * value is one the client already derives to DRAW the sky ({@link net.shurui.shuruisutilities.client.space.SpaceBodyRenderer}),
 * so the map never asks the server for anything and cannot drift from what is drawn: the sun at the layout centre, the
 * fixed main planets on their rings ({@link SpaceLayout#fixedBodies}), the seven super dragon ball bodies
 * ({@link SpaceLayout#clientSuperBodies}), and the generated planets within visitable range around the player
 * ({@link GeneratedPlanets#generatedNear} with a null server, which reads the synced generation / destroyed / claim
 * snapshots). Destroyed planets are suppressed at the source by that derivation, so a wreck never appears as visitable.
 *
 * <p>Client-only: loaded solely from the star map screen and its keybind, never on a dedicated server.
 */
public final class StarMapData
{
    private StarMapData()
    {
    }

    // A nominal disc half-extent for a super body: the sync carries the seven super bodies' positions and claim state
    // but not a per-body radius (the renderer draws them at a fixed dragon-ball size), so the map uses one nominal size.
    private static final float SUPER_NOMINAL_RADIUS = 64.0F;

    // Hard cap on generated planets gathered for the map, so a dense region can never make the one-shot cell walk or the
    // draw loop unbounded. The nearest ones win; beyond this the region is simply denser than the atlas shows at once.
    private static final int MAX_GENERATED = 400;

    // The friendly names of the known fixed main planets, keyed by dimension id. A fixed body is a real DMZ dimension,
    // so the client has no server destination name for it; these lang keys give the four solar-system landmarks a proper
    // name, and any other fixed body falls back to a prettified dimension path (prettify). Reliable, not invented: these
    // ids are exactly the ones PlanetPositions' orbit and radius tables name.
    private static Component fixedBodyName(String key)
    {
        switch (key)
        {
            case "minecraft:overworld":
                return Component.translatable("gui.dmz_ragnarok.core.starmap.body.earth");
            case "dragonminez:namek":
                return Component.translatable("gui.dmz_ragnarok.core.starmap.body.namek");
            case "dragonminez:sacredkaiplanet":
                return Component.translatable("gui.dmz_ragnarok.core.starmap.body.sacred_kai");
            case "dmz_ragnarok:planet_vegeta":
                return Component.translatable("gui.dmz_ragnarok.core.starmap.body.vegeta");
            default:
                return Component.literal(prettify(key));
        }
    }

    // strip the namespace, turn separators into spaces and title-case each word, so an unlisted fixed dimension id reads
    // as a plain name rather than a raw resource location. Presentation only.
    private static String prettify(String key)
    {
        String path = key;
        int colon = path.indexOf(':');
        if (colon >= 0 && colon + 1 < path.length())
        {
            path = path.substring(colon + 1);
        }
        path = path.replace('_', ' ').replace('.', ' ').trim();
        if (path.isEmpty())
        {
            return key;
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean startWord = true;
        for (int i = 0; i < path.length(); ++i)
        {
            char c = path.charAt(i);
            if (c == ' ')
            {
                startWord = true;
                sb.append(c);
            }
            else if (startWord)
            {
                sb.append(Character.toUpperCase(c));
                startWord = false;
            }
            else
            {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /**
     * A readable TYPE label for a body: the sun, a main planet, a super dragon ball, or (a generated planet) its surface
     * theme phrase from the shared {@link PlanetInfoLabels} table the planet-info overlay uses, so the two read the same.
     */
    public static Component typeLabel(StarMapBody body)
    {
        switch (body.kind)
        {
            case SUN:
                return Component.translatable("gui.dmz_ragnarok.core.starmap.type.sun");
            case STAR:
                return Component.translatable("gui.dmz_ragnarok.core.starmap.type.star");
            case FIXED:
                return Component.translatable("gui.dmz_ragnarok.core.starmap.type.main_planet");
            case SUPER:
                return Component.translatable("message.dmz_ragnarok.core.space_super_body");
            case GENERATED:
            default:
                String theme = body.theme == null ? "" : body.theme.name().toLowerCase(Locale.ROOT);
                return Component.translatable(PlanetInfoLabels.envKey(theme));
        }
    }

    /**
     * Collect every currently-visitable body around the player for the atlas: the sun, all fixed main planets, the seven
     * super bodies, and the generated planets within visitable range. Runs a null-server generated-planet walk, which is
     * a pure hash reading the synced snapshots, so it is safe and cheap on the client. Bounded by the client draw
     * distance (what the player can actually see and reach) and the {@link #MAX_GENERATED} cap.
     */
    public static List<StarMapBody> collect(Vec3 playerPos)
    {
        List<StarMapBody> out = new ArrayList<>();

        // the sun at the layout centre: a landmark, never a travel target.
        out.add(new StarMapBody(PlanetPositions.SUN_KEY, StarMapBody.Kind.SUN,
                Component.translatable("gui.dmz_ragnarok.core.starmap.body.sun"),
                PlanetPositions.sunPosition(), PlanetPositions.SUN_RADIUS, PlanetPositions.SUN_TINT,
                null, 0, "", false, false, false, ""));

        // fixed main planets on their rings: always available client-side, few in number, and course targets.
        for (FixedBody fb : SpaceLayout.fixedBodies(null))
        {
            out.add(new StarMapBody(fb.key, StarMapBody.Kind.FIXED, fixedBodyName(fb.key), fb.position, fb.radius,
                    PlanetPositions.tint(fb.key), null, 0, "", false, true, true, ""));
        }

        // EVERY generated star system's SUN in the universe: enumerate the deterministic system grid ONCE per layout version
        // (GeneratedSystems.allSystems is cached) and chart only the SUNS here. A destroyed sun is hidden (the same synced
        // destroyed set the world uses). The system's PLANETS are NOT built here: doing that for the whole universe built
        // thousands of bodies every refresh and, at the zoom that showed them all, drew thousands of discs and rings, which
        // is what made zooming and selecting lag. The screen expands a system's planets on demand ({@link #systemPlanets})
        // only for the few systems inside the viewport (or the selected one), so the model here stays O(systems suns).
        for (GeneratedSystems.System system : GeneratedSystems.allSystems(null))
        {
            if (SpaceLayout.isDestroyed(null, system.starKey))
            {
                continue;   // a destroyed sun takes its whole system off the atlas.
            }
            out.add(new StarMapBody(system.starKey, StarMapBody.Kind.STAR,
                    Component.literal(GeneratedPlanets.nameFor(system.starKey)),
                    system.starPos, system.starRadius, system.starTint, null, 0, "", false, false, false,
                    system.starKey));
        }

        // LEGACY exempt planets (old-scheme, claimed or stamped, unmoving) within visitable range of the player: enumerate
        // them near the player, but SKIP the system planets that {@link GeneratedPlanets#generatedNear} unions in (those are
        // charted on demand by the screen, per system, tied to their sun). The system planet ids near the player come from
        // the same source the union does, so the skip set is exact and cheap (bounded by the draw distance).
        double range = Math.min(SpaceLayout.clientDrawDistance(), SpaceLayout.MAX_DRAW_DISTANCE);
        java.util.Set<String> nearSystemIds = new java.util.HashSet<>();
        for (GeneratedPlanets.Generated sp : GeneratedSystems.planetsNear(null, playerPos, range))
        {
            nearSystemIds.add(sp.id);
        }
        List<GeneratedPlanets.Generated> gens = new ArrayList<>(GeneratedPlanets.generatedNear(null, playerPos, range));
        gens.sort((a, b) -> Double.compare(a.position.distanceToSqr(playerPos), b.position.distanceToSqr(playerPos)));
        int count = 0;
        for (GeneratedPlanets.Generated g : gens)
        {
            if (nearSystemIds.contains(g.id))
            {
                continue;   // a system planet: charted on demand by the screen, not as a static legacy body.
            }
            String owner = SpaceLayout.ownerOf(g.id);
            out.add(new StarMapBody(g.id, StarMapBody.Kind.GENERATED, Component.literal(GeneratedPlanets.nameFor(g.id)),
                    g.position, g.radius, g.tint, themeFor(g.id), g.surfaceSize, owner, false, true, true, ""));
            if (++count >= MAX_GENERATED)
            {
                break;
            }
        }

        // the seven super dragon ball bodies: authoritative synced state. HIDDEN unless the viewer holds a Super dragon
        // radar, exactly as the Super balls are only discoverable with that radar; and NEVER a course target (the server
        // refuses a super key in PlanetCourse.armCourseToBody). Positions orbit slowly, derived live from synced elements.
        // Added LAST so they draw ON TOP of the system suns (they sit in the inner system, among the charted suns, so
        // painting them last keeps the seven revealed balls visible rather than buried under the nearest system icons).
        if (holdsSuperRadar())
        {
            for (PacketSpaceLayoutSync.SuperBody sb : SpaceLayout.clientSuperBodies())
            {
                out.add(new StarMapBody(sb.id(), StarMapBody.Kind.SUPER,
                        Component.translatable("message.dmz_ragnarok.core.space_super_body"),
                        sb.pos(), SUPER_NOMINAL_RADIUS, PlanetPositions.SUN_TINT, null, 0, "", sb.claimed(), true, false,
                        ""));
            }
        }
        return out;
    }

    /**
     * The orbiting PLANETS of one system, as star map bodies at the current epoch, or an empty list. Built on demand by the
     * screen for a system that is inside the viewport or selected, so the whole-universe planet set is never materialised at
     * once (that was the select/zoom lag). Cheap: 5 to 9 planets, one cos/sin each. A destroyed planet is hidden through the
     * same synced destroyed set the world uses. {@code systemPlanetsNear} in the world does the identical derivation.
     */
    public static List<StarMapBody> systemPlanets(GeneratedSystems.System system, long epoch)
    {
        List<StarMapBody> out = new ArrayList<>(system.planets.size());
        for (GeneratedSystems.SystemPlanet p : system.planets)
        {
            if (SpaceLayout.isDestroyed(null, p.id))
            {
                continue;
            }
            SurfaceStamp.Theme theme = themeFor(p.id);
            out.add(new StarMapBody(p.id, StarMapBody.Kind.GENERATED,
                    Component.literal(GeneratedPlanets.nameFor(p.id)),
                    system.planetPositionAt(p, epoch), p.radius, p.tint, theme, p.surfaceSize,
                    SpaceLayout.ownerOf(p.id), false, true, true, system.starKey));
        }
        return out;
    }

    // the surface theme for a planet id, or null if it cannot be derived (never throws, so a single planet can never
    // break the whole atlas build).
    private static SurfaceStamp.Theme themeFor(String id)
    {
        try
        {
            return SurfaceStamp.surfaceThemeFor(id);
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    // The DMZ registry PATH of the Super dragon radar item (SuDragonBallDefinitions registers it under this bare name; the
    // namespace differs between our jar and DMZ's, so match on the path exactly as RadarDimensionReport does).
    private static final String SUPER_RADAR_PATH = "super_dball_radar";

    // Whether the local player is carrying a Super dragon radar anywhere reachable: the vanilla inventory (hotbar, main,
    // armour and the offhand are all in the inventory container) and, when Curios is present, an equipped curio slot. This
    // is the same "does the player hold this radar" question the Super balls' own discoverability asks, so the star map
    // reveals the Super bodies on exactly the same condition. Client-only; a null player (title screen) holds nothing.
    private static boolean holdsSuperRadar()
    {
        Player player = Minecraft.getInstance().player;
        if (player == null)
        {
            return false;
        }
        for (int i = 0; i < player.getInventory().getContainerSize(); ++i)
        {
            if (isSuperRadar(player.getInventory().getItem(i)))
            {
                return true;
            }
        }
        return holdsSuperRadarInCurios(player);
    }

    // guarded Curios scan: only touch the Curios API when the mod is loaded, and swallow any API shift so a missing or
    // changed Curios can never break the map. Curios is an optional dependency, so the class reference sits behind the
    // ModList guard, never classloaded when the mod is absent.
    private static boolean holdsSuperRadarInCurios(Player player)
    {
        if (!ModList.get().isLoaded("curios"))
        {
            return false;
        }
        try
        {
            return top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player)
                    .map(inv ->
                    {
                        var curios = inv.getEquippedCurios();
                        for (int i = 0; i < curios.getSlots(); ++i)
                        {
                            if (isSuperRadar(curios.getStackInSlot(i)))
                            {
                                return true;
                            }
                        }
                        return false;
                    })
                    .orElse(false);
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    // whether a stack is the Super dragon radar, matched on the registry path (any namespace), like RadarDimensionReport.
    private static boolean isSuperRadar(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return false;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && SUPER_RADAR_PATH.equals(id.getPath());
    }
}
