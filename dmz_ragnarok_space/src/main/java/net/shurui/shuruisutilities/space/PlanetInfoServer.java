package net.shurui.shuruisutilities.space;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Server-side authority for PLANET INFO. It resolves which generated planet a player is looking at, snapshots that
 * planet's public state into a {@link PlanetInfoView}, and either PUSHES it to that client's floating overlay
 * ({@link #push}) or PRINTS it to chat for the {@code /planet look} command ({@link #readout}). Mirrors
 * {@link net.shurui.shuruisutilities.guilds.network.GuildGuiServer}: the client only asks, the server does ALL of the
 * work, so the client never computes toughness or reads guild/garrison data directly.
 *
 * <h2>Targeting reuses the planet-buster's ray, not a new one</h2>
 * The body a player is aiming at is found with the EXACT same {@link GeneratedPlanets#bodyAlongRay} call and the SAME
 * {@link SpaceLayout#LABEL_DISTANCE} surface-distance gate the planet-buster trigger uses (see PlanetBusterModule). So
 * "its name is showing" (the renderer label), "I can destroy it" (the buster) and "I can open its info" all agree on
 * exactly which bodies are in reach, with one shared piece of geometry rather than three that merely match today.
 *
 * <h2>Failure-open, never a crash</h2>
 * Every cross-system read (garrison, surface theme, guild) is wrapped so a DragonMineZ or sibling-system drift is caught,
 * logged ONCE via a latched boolean, and degrades to the field's defined "none/unknown" default. A partial view still
 * opens the screen; the player just sees "unknown" for the part that failed.
 */
public final class PlanetInfoServer
{
    private PlanetInfoServer()
    {
    }

    // one-shot latches so a persistent drift in a cross-system read warns ONCE for the whole run, never once per open
    // (which a player could otherwise trigger every keypress). One per touchpoint so a failure in one does not mask the
    // others' first warning.
    private static final AtomicBoolean GARRISON_WARNED = new AtomicBoolean(false);
    private static final AtomicBoolean THEME_WARNED = new AtomicBoolean(false);
    private static final AtomicBoolean GUILD_WARNED = new AtomicBoolean(false);

    /**
     * Resolve the generated planet the player is looking at and PUSH its view to that client's floating overlay, silently
     * on a miss. This is the CONTINUOUS overlay path: it is called from the empty {@link PacketPlanetInfoRequest} the
     * client fires when the aimed-at planet changes or on its once-a-second throttle, so it must NOT spam the player with
     * action-bar reasons on every miss the way the one-shot {@link #readout} does. Runs on the server thread. Never throws.
     */
    public static void push(ServerPlayer player)
    {
        PlanetInfoTarget.Hit target = resolveTarget(player, false);
        if (target == null)
        {
            return;
        }
        PlanetInfoView view = build(player.getServer(), target);
        NetworkUtils.INSTANCE.send(PacketDistributor.PLAYER.with(() -> player), new PacketPlanetInfoGui(view));
    }

    /**
     * One-shot chat READOUT for {@code /planet look}: resolve the aimed-at planet and print its full state to the caller's
     * chat, or send a translated action-bar reason on a miss. This is the deliberate command counterpart to the floating
     * overlay: it works regardless of whether the client has the overlay toggled on, so a player whose P key is stolen (or
     * who just wants a quick text dump) still has a way to read a planet. Runs on the server thread. Never throws.
     */
    public static void readout(ServerPlayer player)
    {
        PlanetInfoTarget.Hit target = resolveTarget(player, true);
        if (target == null)
        {
            return;
        }
        PlanetInfoView v = build(player.getServer(), target);
        sendReadout(player, v, target.id);
    }

    // shared targeting for BOTH paths, so the overlay push and the /planet look readout can never disagree on which body
    // is in reach. Routes through PlanetInfoTarget.resolve, the ONE rule the client overlay reads too, so a moon or a
    // generated planet is picked identically on both sides. Applies the same surface-distance gate afterward. When
    // announce is true (the command) it sends a translated action-bar reason on each miss; when false (the continuous
    // overlay) it stays silent so a client polling once a second never floods the player with "look at a planet" messages.
    private static PlanetInfoTarget.Hit resolveTarget(ServerPlayer player, boolean announce)
    {
        if (player == null)
        {
            return null;
        }
        MinecraftServer server = player.getServer();
        if (server == null)
        {
            return null;
        }
        // only meaningful in space, where planets are drawn bodies you look at rather than blocks you stand on.
        if (!SpaceDimension.isSpace(player.level()))
        {
            if (announce)
            {
                notice(player, "planet_info_not_space");
            }
            return null;
        }

        // cast from the eye along the look vector for the first body (generated planet OR orbiting moon), capped at the
        // legal name-range plus one max body radius so we never scan further than a label shows. gameTime places the
        // moons at the same whole tick the client uses, so the clickable moon matches the one the renderer draws.
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double maxRay = SpaceLayout.LABEL_DISTANCE + GeneratedPlanets.maxBodyRadius();
        long gameTime = player.level().getGameTime();
        PlanetInfoTarget.Hit target = PlanetInfoTarget.resolve(server, eye, look, maxRay, gameTime);
        if (target == null)
        {
            if (announce)
            {
                notice(player, "planet_info_none");
            }
            return null;
        }
        // the exact same surface-distance rule the label gate uses, so info shows only for a body whose name is showing.
        double surfaceDistance = eye.distanceTo(target.position) - target.radius;
        if (surfaceDistance > SpaceLayout.LABEL_DISTANCE)
        {
            if (announce)
            {
                notice(player, "planet_info_out_of_range");
            }
            return null;
        }
        return target;
    }

    // snapshot the target body's public state. Takes the id + surfaceSize the view needs off the shared
    // PlanetInfoTarget.Hit (position/radius live on the Hit only for the caller's surface gate), so it builds identically
    // for a generated planet or a moon: every read below is id-keyed and nameFor already routes a sumoon: id to its
    // parent-derived moon name. Each cross-system block is independently guarded so one failing read leaves the rest of
    // the view intact (a partial GUI), per the failure-open rule.
    private static PlanetInfoView build(MinecraftServer server, PlanetInfoTarget.Hit target)
    {
        PlanetInfoView v = new PlanetInfoView();
        String id = target.id;

        // identity: both pure derivations off the id, so neither can throw.
        v.planetName = GeneratedPlanets.nameFor(id);
        v.surfaceSize = target.surfaceSize;

        GeneratedPlanetClaims claims = GeneratedPlanetClaims.get(server);
        v.destroyed = claims.isDestroyed(id);

        // toughness: PlanetToughness.resolve is documented never to throw and never to return below 1.0, but wrap it
        // anyway so a future change there can never take the whole GUI down. It reads guild strength internally; we do
        // NOT read it here, keeping the client ignorant of guild internals.
        try
        {
            v.toughness = PlanetToughness.resolve(server, id);
        }
        catch (Throwable t)
        {
            v.toughness = 1.0;
            warnOnce(GUILD_WARNED, "toughness", id, t);
        }

        // garrison: which family defends, and alive-vs-original counts. `populated` distinguishes a never-visited
        // planet (unknown) from a cleared one (0 of N). A missing record reads as unpopulated, not as a crash.
        try
        {
            PlanetGarrisonData garrison = PlanetGarrisonData.get(server);
            v.populated = garrison.isPopulated(id);
            if (v.populated)
            {
                v.garrisonFamily = garrison.family(id);
                v.defendersRemaining = garrison.remaining(id);
                v.defendersTotal = garrison.roster(id).size();
                v.recommendedBattlePower = garrison.recommendedBattlePower(id);
            }
        }
        catch (Throwable t)
        {
            v.populated = false;
            warnOnce(GARRISON_WARNED, "garrison", id, t);
        }

        // environment: the surface theme. surfaceThemeFor is a pure hash of the id (it does not touch DMZ blocks), but
        // guard it so any future coupling degrades to "unknown" rather than failing the build.
        try
        {
            SurfaceStamp.Theme theme = SurfaceStamp.surfaceThemeFor(id);
            v.environmentTheme = theme == null ? "" : theme.name();
        }
        catch (Throwable t)
        {
            v.environmentTheme = "";
            warnOnce(THEME_WARNED, "theme", id, t);
        }

        // guild ownership: name, member count and battle power of the owning guild if one still exists. A claim whose
        // guild disbanded is treated as unclaimed, exactly like the command and buster paths.
        try
        {
            // PUBLIC personal conquest claim takes precedence in the display: reuse the owner-name field so the overlay's
            // existing "owned by X" line shows the conquering player, with no packet change (member count 1, battle power 0
            // because a personal claim has no guild behind it).
            String personalOwner = claims.personalOwner(id);
            if (personalOwner != null)
            {
                v.claimed = true;
                v.ownerGuildName = personalOwnerName(server, personalOwner);
                v.ownerMemberCount = 1;
                v.ownerBattlePower = 0.0;
            }
            else
            {
                String ownerGuildId = claims.owner(id);
                if (ownerGuildId != null)
                {
                    Guild guild = GuildManager.byId(ownerGuildId);
                    if (guild != null)
                    {
                        v.claimed = true;
                        v.ownerGuildName = guild.name;
                        v.ownerMemberCount = guild.memberCount();
                        v.ownerBattlePower = guild.battlePower();
                    }
                }
            }
        }
        catch (Throwable t)
        {
            v.claimed = false;
            warnOnce(GUILD_WARNED, "guild", id, t);
        }

        return v;
    }

    // resolve a personal-claim owner uuid to a display name via the profile cache, falling back to the raw uuid. Never
    // throws: a cache miss or malformed uuid just shows the uuid.
    private static String personalOwnerName(MinecraftServer server, String uuid)
    {
        try
        {
            java.util.Optional<com.mojang.authlib.GameProfile> profile =
                    server == null || server.getProfileCache() == null ? java.util.Optional.empty()
                            : server.getProfileCache().get(java.util.UUID.fromString(uuid));
            if (profile.isPresent() && profile.get().getName() != null)
            {
                return profile.get().getName();
            }
        }
        catch (Throwable ignored)
        {
            // fall through to the raw uuid.
        }
        return uuid;
    }

    // print the built view to the caller's chat as the one-shot /planet look readout. Uses the SAME gui.*.planetinfo.*
    // lang keys the floating overlay draws and the SAME PlanetInfoLabels resolution, so the command and the panel read
    // identically and every "unknown/none/not explored/unclaimed" fallback is preserved. The lines are translatable, so
    // each resolves in the receiving client's own language.
    private static void sendReadout(ServerPlayer player, PlanetInfoView v, String planetId)
    {
        String root = "gui.dmz_ragnarok.core.planetinfo.";

        // header: the planet name (or the shared "unknown planet" phrase), reading first like the overlay's header.
        Component name = v.planetName.isEmpty()
                ? Component.translatable(root + "unknown_planet")
                : Component.literal(v.planetName);
        player.sendSystemMessage(name.copy().withStyle(ChatFormatting.GOLD));

        // admins get the raw planet id, clickable to copy, right under the name, so a look-at readout also hands over the
        // id for /planet destroy or /planet restore. Same admin node and same copyable component the /planet info line
        // uses. This is a plain chat line, NOT part of the client overlay packet (PacketPlanetInfoGui), so no packet
        // change is needed: the overlay stays exactly as it was and only this text readout carries the id.
        if (planetId != null
                && APIRegistry.perms.checkPermission(player, ModuleSpacePlanetClaims.PERM_ADMIN))
        {
            player.sendSystemMessage(CommandSpacePlanet.idLine(planetId));
        }

        if (v.destroyed)
        {
            player.sendSystemMessage(Component.translatable(root + "destroyed").withStyle(ChatFormatting.RED));
        }

        // ENVIRONMENT
        player.sendSystemMessage(Component.translatable(root + "section.environment").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.translatable(root + "environment",
                Component.translatable(PlanetInfoLabels.envKey(v.environmentTheme))).withStyle(ChatFormatting.WHITE));
        player.sendSystemMessage(Component.translatable(root + "surface", String.valueOf(v.surfaceSize))
                .withStyle(ChatFormatting.WHITE));

        // STRENGTH
        player.sendSystemMessage(Component.translatable(root + "section.strength").withStyle(ChatFormatting.YELLOW));
        player.sendSystemMessage(Component.translatable(root + "toughness", num(v.toughness))
                .withStyle(ChatFormatting.WHITE));
        Component power = v.populated
                ? Component.literal(num(v.recommendedBattlePower))
                : Component.translatable(PlanetInfoLabels.UNKNOWN_KEY);
        player.sendSystemMessage(Component.translatable(root + "recommended", power).withStyle(ChatFormatting.WHITE));

        // DEFENDERS
        player.sendSystemMessage(Component.translatable(root + "section.defenders").withStyle(ChatFormatting.YELLOW));
        if (!v.populated)
        {
            player.sendSystemMessage(Component.translatable(root + "defenders_unexplored").withStyle(ChatFormatting.GRAY));
        }
        else if (v.defendersTotal <= 0 || v.garrisonFamily.isEmpty())
        {
            player.sendSystemMessage(Component.translatable(root + "defenders_none").withStyle(ChatFormatting.WHITE));
        }
        else
        {
            player.sendSystemMessage(Component.translatable(root + "defenders_family",
                    Component.translatable(PlanetInfoLabels.familyKey(v.garrisonFamily))).withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.translatable(root + "defenders_count",
                    String.valueOf(v.defendersRemaining), String.valueOf(v.defendersTotal))
                    .withStyle(ChatFormatting.WHITE));
        }

        // GUILD
        player.sendSystemMessage(Component.translatable(root + "section.guild").withStyle(ChatFormatting.YELLOW));
        if (!v.claimed)
        {
            player.sendSystemMessage(Component.translatable(root + "guild_unclaimed").withStyle(ChatFormatting.GRAY));
        }
        else
        {
            player.sendSystemMessage(Component.translatable(root + "guild_owner", v.ownerGuildName)
                    .withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.translatable(root + "guild_members", String.valueOf(v.ownerMemberCount))
                    .withStyle(ChatFormatting.WHITE));
            player.sendSystemMessage(Component.translatable(root + "guild_power", num(v.ownerBattlePower))
                    .withStyle(ChatFormatting.WHITE));
        }
    }

    // whole-number formatting with thousands separators, matching the overlay and GuildScreen's power/bank lines.
    private static String num(double value)
    {
        return String.format("%,.0f", value);
    }

    // action-bar notice, resolved in the player's own language client-side (matches the space-travel message style).
    private static void notice(ServerPlayer player, String key)
    {
        player.displayClientMessage(
                Component.translatable("message.dmz_ragnarok.core." + key), true);
    }

    // log a cross-system read failure exactly once per touchpoint for the whole run.
    private static void warnOnce(AtomicBoolean latch, String what, String planetId, Throwable t)
    {
        if (latch.compareAndSet(false, true))
        {
            LoggingHandler.sulog.warn("[PlanetInfo] Failed reading {} for planet {}; showing partial info.",
                    what, planetId, t);
        }
    }
}
