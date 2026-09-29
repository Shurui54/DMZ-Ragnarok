package net.shurui.shuruisutilities.client.shard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.shard.GhostAppearance;
import net.shurui.shuruisutilities.shard.PacketGhosts;

/**
 * Keeps the client's ghosts in step with what the server last said.
 *
 * <h2>Why the whole visible set arrives each time</h2>
 * The server sends everything a player can currently see, so anything absent from an update has either walked
 * out of range or logged off, and either way it should stop being drawn. That removes the need for a despawn
 * message and, more usefully, means a dropped packet fixes itself a tenth of a second later instead of leaving
 * somebody stood there for ever.
 *
 * <h2>Interpolation</h2>
 * Updates arrive at ten a second and the game draws sixty or more, so a ghost placed exactly where the last
 * packet said would visibly step. {@code lerpTo} is the same mechanism vanilla uses for every other player on a
 * server, so the movement between updates is smoothed by the same code and looks the same.
 *
 * <h2>Identity</h2>
 * The real UUID goes into the GameProfile, which is what makes the skin resolve: the client looks a player's
 * texture up by profile exactly as it does for anyone else. Entity IDs are negative and allocated here, because
 * positive ones belong to the server and reusing one would collide with a real entity.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class GhostManager
{
    private GhostManager() {}

    /** Ghosts by player id, so an update can find the one it is about. */
    private static final Map<UUID, GhostPlayer> GHOSTS = new HashMap<>();

    /** Entity ids handed out here. Counting DOWN from a long way below anything a server will ever assign. */
    private static int nextId = -30000;

    /** Applied on the client thread by the packet handler. */
    public static void accept(List<PacketGhosts.Ghost> incoming)
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null)
        {
            GHOSTS.clear();
            return;
        }
        Set<UUID> seen = new HashSet<>();
        for (PacketGhosts.Ghost g : incoming)
        {
            // Never draw a ghost of yourself. It can happen for a moment during a transfer, while the server
            // you are leaving still has you in its table and the one you have arrived on is already drawing it.
            if (mc.player != null && mc.player.getUUID().equals(g.id()))
                continue;
            // Never draw a ghost of somebody this client is already holding as a REAL player. A ghost carries the
            // real uuid, and ClientLevel keeps one entity per uuid: the second arrival is logged as a duplicate and
            // DROPPED. Spawn a ghost over a present player and it is the present player who disappears, for as long
            // as the ghost stands there, because the server will not send their add packet again until they leave
            // tracking range and return. The server side already refuses to send a ghost for anybody online there
            // (see ShardGhosts.onlyThoseNotHere); this covers the moment in between, while a transferring player is
            // in both places at once.
            if (isRealPlayerHere(level, g.id()))
                continue;
            seen.add(g.id());
            GhostPlayer ghost = GHOSTS.get(g.id());
            if (ghost == null)
            {
                ghost = spawn(level, g);
                if (ghost == null)
                    continue;
                GHOSTS.put(g.id(), ghost);
            }
            // Keep the drawn race in step with the packet, so a transformation on the other shard shows here
            // too. Only writes when something actually changed, so a standing ghost costs nothing.
            applyCharacter(ghost, g);
            GhostAppearance.applyArmor(ghost, g.armor());
            // Smoothed over the next three ticks, which is about how far apart the updates are.
            ghost.lerpTo(g.x(), g.y(), g.z(), g.yaw(), g.pitch(), 3, false);
            ghost.setYHeadRot(g.yaw());
            // A client tick can clear onGround again between updates, so re-assert it every time a
            // position arrives. See spawn() for why a ghost must always report as standing.
            ghost.setOnGround(true);
        }
        // Anything the server did not mention is out of range or gone.
        List<UUID> stale = new ArrayList<>();
        for (UUID id : GHOSTS.keySet())
            if (!seen.contains(id))
                stale.add(id);
        for (UUID id : stale)
            despawn(level, GHOSTS.remove(id));
    }

    /**
     * A REAL player entity is about to be added to the client level under this uuid: drop any ghost standing in for
     * them FIRST, so the real add is not refused as a duplicate.
     *
     * <p>This is the deterministic half of the invisibility guard. {@link #accept} already skips a ghost of a present
     * player, but it only runs when a ghost packet arrives (ten a second), so a real player's own add can reach the
     * client in the window between two ghost updates while the stale ghost is still standing. When it does,
     * {@code EntityLookup.add} sees the uuid already held by the ghost, logs {@code Duplicate entity UUID} and DROPS
     * the real player: they are invisible until a re-track (leave range and return, which is what "tp away and back"
     * forces). Removing the ghost at the very moment the real add begins frees the uuid first, whatever order the
     * ghost despawn and the real add would otherwise have arrived in. Called from the client-level add-player hook.
     */
    public static void onRealPlayerJoin(UUID id)
    {
        if (id == null)
            return;
        GhostPlayer ghost = GHOSTS.remove(id);
        if (ghost == null)
            return;
        // The ghost's negative entity id is distinct from the incoming real player's, so removing it here frees only
        // the uuid, never the real entity. removeEntity runs the level callback that clears the uuid from the lookup
        // synchronously, so the add that follows this call finds it free.
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null)
            despawn(level, ghost);
    }

    /**
     * Whether this client already holds a REAL player under that uuid.
     *
     * <p>Skipping such a ghost also drops any ghost already standing in for them, because the skip leaves the uuid
     * out of {@code seen} and the stale sweep at the end of {@link #accept} then despawns it. That is the right way
     * round: the moment the real player is here, the stand-in has nothing to stand in for.
     */
    private static boolean isRealPlayerHere(ClientLevel level, UUID id)
    {
        net.minecraft.world.entity.player.Player existing = level.getPlayerByUUID(id);
        return existing != null && !(existing instanceof GhostPlayer);
    }

    private static GhostPlayer spawn(ClientLevel level, PacketGhosts.Ghost g)
    {
        try
        {
            // The real uuid and name, so the skin loads through the ordinary profile lookup.
            GhostPlayer ghost = new GhostPlayer(level, new GameProfile(g.id(), g.name()));
            ghost.setId(nextId--);
            ghost.setPos(g.x(), g.y(), g.z());
            ghost.setYRot(g.yaw());
            ghost.setXRot(g.pitch());
            ghost.setYHeadRot(g.yaw());
            // A ghost is pinned in place by lerpTo, never by physics, so it must never appear to fall.
            // noPhysics (set in the constructor) turns off BLOCK collision but leaves gravity and the
            // on-ground flag untouched, so without these two the client keeps onGround false and the
            // player renderer draws the airborne, falling limb pose for ever. Report solid ground, and
            // switch gravity off so nothing tries to accelerate it downward between updates.
            ghost.setOnGround(true);
            ghost.setNoGravity(true);
            level.addPlayer(ghost.getId(), ghost);
            // Set the race before the first frame, so DragonMineZ builds the right model straight away instead
            // of drawing a human for a moment and swapping.
            applyCharacter(ghost, g);
            GhostAppearance.applyArmor(ghost, g.armor());
            return ghost;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * Write the packet's race state onto the ghost's DragonMineZ character, which is what {@code DMZPlayerRenderer}
     * reads to pick a model. A ghost is a real player entity, so it carries the stats capability like any player
     * (defaulting to human), and drawing it as the right race is just a matter of setting the same few fields the
     * owning shard read off the real player: race, gender, body type and current form.
     *
     * <p>Empty race means the other shard had no character for them (a fresh join, or a peer on the old jar that
     * does not send this yet), so the default human is left untouched. Fields are only written when they differ,
     * so a standing ghost never churns the renderer's model cache, and the whole thing is wrapped: failing to set
     * a race draws a human, which is the pre existing behaviour, not a crash on the render path.
     */
    private static void applyCharacter(GhostPlayer ghost, PacketGhosts.Ghost g)
    {
        String race = g.race();
        if (race == null || race.isEmpty())
            return;
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, ghost).resolve().orElse(null);
            if (stats == null)
                return;
            Character ch = stats.getCharacter();
            if (!race.equals(ch.getRace()))
                ch.setRace(race);
            if (g.gender() != null && !g.gender().isEmpty() && !g.gender().equals(ch.getGender()))
                ch.setGender(g.gender());
            if (g.bodyType() != ch.getBodyType())
                ch.setBodyType(g.bodyType());
            // THE TWO ARG setActiveForm, not the two one arg setters. Setting the group and the form separately
            // writes both fields and stops there; the pair version additionally runs updateOozaruCache(), which
            // is what the renderer's derived state depends on. Doing it the other way was why a ghost stayed in
            // its base look after the real player transformed on the other shard.
            String group = g.formGroup() == null ? "" : g.formGroup();
            String form = g.form() == null ? "" : g.form();
            if (!group.isEmpty() && !form.isEmpty()
                    && (!group.equals(ch.getActiveFormGroup()) || !form.equals(ch.getActiveForm())))
                ch.setActiveForm(group, form);

            // Hair, colours, face types, tail. Written every update rather than diffed: each setter is a field
            // assignment, and the alternative is fourteen comparisons to save fourteen stores.
            GhostAppearance.apply(ch, g.appearance());
        }
        catch (Throwable ignored)
        {
            // A ghost drawn as a human is a cosmetic miss, not a crash.
        }
    }

    private static void despawn(ClientLevel level, GhostPlayer ghost)
    {
        if (ghost == null)
            return;
        try
        {
            level.removeEntity(ghost.getId(), Entity.RemovalReason.DISCARDED);
        }
        catch (Throwable ignored)
        {
        }
    }

    /**
     * Drop everything when the world goes.
     *
     * <p>Ghosts belong to a level. Leaving them in the map across a world change would have the next accept
     * update entities that no longer exist anywhere, and on a network a world change happens every time
     * somebody switches server, which is often.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null && !GHOSTS.isEmpty())
            GHOSTS.clear();
    }
}
