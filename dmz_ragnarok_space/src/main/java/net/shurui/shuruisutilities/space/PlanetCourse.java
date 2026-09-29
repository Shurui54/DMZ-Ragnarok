package net.shurui.shuruisutilities.space;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import com.dragonminez.common.init.entities.SpacePodEntity;
import com.dragonminez.common.spacepod.SpacePodDestinationDefinition;
import com.dragonminez.common.spacepod.SpacePodDestinationRegistry;

/**
 * Turns a selected space-pod destination into a compass COURSE to that planet's body in the space dimension,
 * instead of an instant teleport. Called from {@link net.shurui.shuruisutilities.space.mixin.dmz.MixinDmzTravelToPlanet}
 * (which cancels DMZ's teleport only when this claims the trip) and read by {@link SpaceTravelModule} when a
 * player flies into a body to land.
 *
 * <p>Course state is one string on the player's raw persistent tag: the KEY (target dimension id) of the planet
 * the course points at. It is intentionally NOT in the PlayerPersisted sub-tag: a course is a transient piece of
 * navigation, not something that must survive death, and the compass marker it drives is itself transient in
 * sdu's memory. We DO re-assert the marker whenever the course is (re)set so selecting a new destination replaces
 * the old pip in place, and clear both together on arrival.
 *
 * <p>dragonminez is a mandatory dependency so the DMZ destination lookup here needs no guard. The compass marker
 * goes through {@link SpaceCompassBridge}, which is the one place that names sdu (an OPTIONAL dependency) and is
 * fully ModList-gated. With sdu absent the course key is still recorded and landing still works; there is simply
 * no on-screen pip.
 */
public final class PlanetCourse
{
    private PlanetCourse()
    {
    }

    // the planet key (target dimension id) the player's current course points at, "" if none. Raw persistent tag,
    // not the death-surviving sub-tag: a course is transient navigation.
    private static final String COURSE_TAG = "su_space_course";

    /**
     * Decide what should happen to a DMZ "travel to planet" request, returning true if the caller must cancel DMZ's
     * teleport. Two cases claim (cancel) the trip:
     *
     * <ul>
     *   <li>The destination is a space planet body: we set a compass course to it instead of teleporting. This is
     *       the phase 1c behaviour, unchanged, and applies whether or not the player is in a pod.</li>
     *   <li>The destination is NOT a body (otherworld, the time chamber, any non-body) AND the player is NOT riding
     *       a DMZ space pod: we refuse it. This is the planet-select opener guard. DMZ's real teleport would
     *       {@code stopRiding}, teleport the player and spawn a fresh pod at the destination, so a player who opened
     *       the chooser on foot to track a planet could be teleported away and given an unwanted pod. Refusing here,
     *       server-side, closes that off no matter what the client sends.</li>
     * </ul>
     *
     * Everything else (a non-body destination while the player IS in a pod) returns false so DMZ travels exactly as
     * it always has. Never throws: any failure resolving the destination falls through to false (let DMZ handle it),
     * which is the safe default.
     */
    public static boolean handleTravel(ServerPlayer player, String destinationId)
    {
        // ON FOOT: unchanged phase 1c behaviour. A body destination sets a compass course; anything else is refused
        // so no unexpected teleport or orphan/extra pod can come out of the on-foot planet-select opener. The
        // MANUAL-navigation message tells them to fly up and follow the compass, because on foot there is no
        // autopilot: they climb and steer by hand.
        if (!isRidingSpacePod(player))
        {
            if (setCourseIfBody(player, destinationId))
            {
                player.displayClientMessage(
                        Component.translatable("message.dmz_ragnarok.core.space_course_set",
                                courseDisplayName(player)), false);
                return true;
            }
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_needs_pod"), false);
            return true;
        }

        // POD RIDER (Job 2: launch flies you there automatically, but ONLY in a pod). A non-body destination
        // (otherworld, the time chamber) is left to DMZ's own in-pod teleport, exactly as before.
        if (!setCourseIfBody(player, destinationId))
        {
            return false;
        }
        // it IS a body and the course + compass pip are now set. Arm the launch autopilot toward the course target,
        // but ONLY from a place a launch is actually allowed FROM: already in space, or an eligible non-forbidden
        // launch dim. From the nether/end/otherworld we set only the course pip and never launch, exactly how an
        // on-foot altitude entry is refused there. SpaceTravelModule's per-player tick performs the actual space
        // entry (which brings the pod) and the per-tick drive, so all the vehicle/teleport machinery stays in one
        // place; here we only record intent. DMZ's instant teleport is cancelled in every pod+body case.
        MinecraftServer server = player.getServer();
        boolean canLaunch = SpaceDimension.isSpace(player.level())
                || SpaceTravelModule.canLaunchFrom(server, player.level().dimension());
        Component name = courseDisplayName(player);
        if (canLaunch)
        {
            SpaceAutopilot.set(player, courseKey(player));
            // AUTOPILOT message, not the manual "follow your compass" one: from here the pod launches under power and
            // flies itself, so telling the player to navigate by hand (the old bug) would be plainly wrong.
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_autopilot_set", name), false);
        }
        else
        {
            // the course pip is set but this dimension forbids a launch (nether/end/otherworld): it is TRACKED, not
            // auto-flown, so the player still needs to reach an eligible dimension. The manual-navigation wording is
            // the honest message here.
            player.displayClientMessage(
                    Component.translatable("message.dmz_ragnarok.core.space_course_set", name), false);
        }
        return true;
    }

    // true while the player is riding a DMZ space pod (directly or nested). Mirrors the guard in SpaceTravelModule.
    private static boolean isRidingSpacePod(ServerPlayer player)
    {
        for (Entity vehicle = player.getVehicle(); vehicle != null; vehicle = vehicle.getVehicle())
        {
            if (vehicle instanceof SpacePodEntity)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * If {@code destinationId} names a destination whose target dimension is a space planet body, set a compass
     * course to that body and return true (the caller then cancels DMZ's teleport AND sends the path-appropriate
     * message, since the on-foot, autopilot and forbidden-dimension paths each need different wording). Otherwise
     * return false and change nothing, so DMZ travels as normal. Never throws: any failure resolving the
     * destination falls through to false (instant travel), which is the safe default. Sends NO message itself.
     */
    public static boolean setCourseIfBody(ServerPlayer player, String destinationId)
    {
        MinecraftServer server = player.getServer();
        if (server == null || destinationId == null)
        {
            return false;
        }

        SpacePodDestinationDefinition dest;
        try
        {
            dest = SpacePodDestinationRegistry.getServerDestination(destinationId);
        }
        catch (Throwable ignored)
        {
            // if DMZ's registry API ever shifts, do not claim the trip: let DMZ teleport as it always has.
            return false;
        }
        if (dest == null || dest.dimension() == null)
        {
            return false;
        }

        // is this destination's target dimension a planet body? bodyForDimension applies every exclusion (nether,
        // end, otherworld, time chamber, unloaded dims), so otherworld and the time chamber return null here and
        // keep teleporting.
        PlanetRegistry.Planet body = null;
        for (PlanetRegistry.Planet p : PlanetRegistry.bodies(server))
        {
            if (p.key.equals(dest.dimension()))
            {
                body = p;
                break;
            }
        }
        if (body == null)
        {
            return false;
        }

        // record the course and (re)assert the marker at the body's position in space. A new selection replaces
        // the previous course and pip because the marker id is stable per player. The caller sends the message: the
        // planet name it needs is available through courseDisplayName, which reads this same course back.
        player.getPersistentData().putString(COURSE_TAG, body.key);
        Vec3 pos = body.position();
        SpaceCompassBridge.setCourse(player, SpaceDimension.ID.toString(), pos.x, pos.y, pos.z);
        return true;
    }

    // the planet key the player's course points at, or "" if none.
    public static String courseKey(ServerPlayer player)
    {
        return player.getPersistentData().getString(COURSE_TAG);
    }

    public static boolean hasCourse(ServerPlayer player)
    {
        return !courseKey(player).isEmpty();
    }

    // clear the course and its compass pip. Called on arrival (only when the arrival planet is the course target)
    // and by the player-driven clear from the planet-select opener. Idempotent: clearing with no course is a no-op.
    public static void clearCourse(ServerPlayer player)
    {
        player.getPersistentData().remove(COURSE_TAG);
        SpaceCompassBridge.clearCourse(player);
    }

    /**
     * A display name for the planet the player's course currently points at, or null if there is no course. Mirrors
     * the naming used in {@link #setCourseIfBody}: look up a DMZ destination that targets the course dimension and
     * use its (possibly translated) name so the label reads in the player's language and matches the pod menu. Falls
     * back to the raw dimension key if no destination names that dimension. Used by the planet-select opener to tell
     * the player what they are tracking without needing an on-screen indicator inside DMZ's own screen.
     */
    public static Component courseDisplayName(ServerPlayer player)
    {
        String key = courseKey(player);
        if (key.isEmpty())
        {
            return null;
        }
        MinecraftServer server = player.getServer();
        if (server != null)
        {
            try
            {
                for (SpacePodDestinationDefinition def : SpacePodDestinationRegistry.getServerDestinations())
                {
                    if (key.equals(def.dimension()))
                    {
                        return def.translate()
                                ? Component.translatable(def.name())
                                : Component.literal(def.name());
                    }
                }
            }
            catch (Throwable ignored)
            {
                // DMZ API shape shift: fall through to the raw key rather than crash the query.
            }
        }
        return Component.literal(key);
    }
}
