package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonRadarDefinition;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardDimensions;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.shard.ShardTags;

/**
 * Right-clicking a dragon radar tells the holder WHICH DIMENSIONS that radar's balls are in, and how many are in
 * each.
 *
 * <h2>Why this is worth having</h2>
 *
 * <p>The radar HUD is a compass, not a map: DMZ's sync packet carries bare {@code BlockPos} values with no dimension
 * attached, and the client draws every position it was given for the set. A blip therefore says "this way, this
 * far" and nothing about WHERE, so a ball sitting on a planet surface and a ball sitting in the overworld are drawn
 * the same way, at coordinates that mean different things. That was tolerable while each set lived in exactly one
 * dimension. It stopped being tolerable when a player could die anywhere and leave balls in a grave totem there,
 * which is the change this ships alongside.
 *
 * <p>Rather than widen the packet (every position would need a dimension, for a HUD that has nowhere to draw it),
 * the question is answered on demand, entirely on the server, in chat. Nothing is sent to the client that was not
 * sent before, and there is no new packet at all: {@code PlayerInteractEvent.RightClickItem} already fires
 * server-side when a player right-clicks with an item.
 *
 * <p>The event is NOT cancelled, so whatever the radar itself does on right-click still happens.
 *
 * <h2>What it counts</h2>
 *
 * <p>Three sources, matching what a player can actually chase down: DMZ's known ball positions in EVERY dimension
 * this server hosts (not just the set's home dimension, so a ball that drifted somewhere unusual is still named),
 * SU's grave totems holding balls of that set across every level, and, on a live shard network, the balls other
 * servers hold, read from the sync {@link CrossShardRadar} already maintains rather than any new one. Positions are
 * printed (capped, with a remainder count) and fuzzed exactly as the HUD draws them, so the readout says where to go
 * without handing a modified client an exact coordinate the radar itself withholds.
 *
 * <p>Each group names the DIMENSION and, when the network is live, the SERVER: "here" for a dimension this server
 * hosts, the short tag (SMP, OW1, OW2) for one that lives elsewhere. Off a network there is no server half, so it
 * degrades to just the dimension. Totems are called out separately, since "in a totem" is the difference between a
 * ball lying in the world and one somebody has to be walked to and taken out of a container.
 *
 * <p>The honest limit: a server can only enumerate the balls it KNOWS about. Its own {@link DragonBallSavedData} is
 * per level, so it holds only the dimensions it hosts; the cross-shard source fills in the rest, but only for balls
 * some shard has published. A ball on a shard that has never synced (or is offline and never did) cannot be named.
 */
public final class RadarDimensionReport
{
    private static final String PREFIX_COLOUR = ChatFormatting.YELLOW.toString();

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event)
    {
        if (event.getLevel().isClientSide || !(event.getEntity() instanceof ServerPlayer player))
        {
            return;
        }
        try
        {
            DragonRadarDefinition radar = radarFor(event.getItemStack());
            if (radar == null)
            {
                return;
            }
            report(player, radar);
        }
        catch (Throwable ignored)
        {
            // a radar that fails to explain itself must still work as a radar: never let this break the interaction
        }
    }

    /** The radar definition whose item this stack is, or null when the stack is not a radar. */
    private static DragonRadarDefinition radarFor(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null)
        {
            return null;
        }
        for (DragonRadarDefinition radar : DragonBallDefinitions.getRadars())
        {
            // DMZ stores the bare registry path ("super_dball_radar"), so compare on the path and let any
            // namespace match: our radars and DMZ's live under different mod ids.
            if (id.getPath().equals(radar.getItemRegistryName()))
            {
                return radar;
            }
        }
        return null;
    }

    // At most this many coordinates are printed per group before the rest are summarised as "(+N more)", so a large
    // scatter reads as somewhere to go rather than a wall of numbers.
    private static final int MAX_POSITIONS_PER_GROUP = 6;

    /**
     * One place the radar knows balls to be: a dimension, plus the server that hosts it when the network is live.
     * Loose (lying in the world) and totem (in a grave) positions are kept apart, because a totem has to be opened.
     */
    private static final class Place
    {
        final ResourceLocation dimension;
        final String serverLabel; // null when there is no shard network, so the readout says just the dimension
        final List<BlockPos> loose = new ArrayList<>();
        final List<BlockPos> totems = new ArrayList<>();

        Place(ResourceLocation dimension, String serverLabel)
        {
            this.dimension = dimension;
            this.serverLabel = serverLabel;
        }
    }

    private static void report(ServerPlayer player, DragonRadarDefinition radar)
    {
        MinecraftServer server = player.getServer();
        String setId = radar.getBallSetId();
        if (server == null || setId == null)
        {
            return;
        }

        // Keyed by dimension id plus server label so a dimension two shards both host (a mirror twin) does not merge
        // across servers. LinkedHashMap so the reading order is stable between right-clicks.
        Map<String, Place> places = new LinkedHashMap<>();

        // 1) Loose balls in EVERY dimension this server hosts, not just the set's home dimension, so a ball that ended
        //    up somewhere the set does not normally scatter is still named. getAllKnownPositionsForRadar is rewritten
        //    by MixinDmzRadarFuzz, so these are already fuzzed for the normal sets and exact for Super / Black Star,
        //    matching what the HUD would draw.
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            List<BlockPos> known = DragonBallSavedData.get(level).getAllKnownPositionsForRadar(setId);
            if (known == null || known.isEmpty())
            {
                continue;
            }
            placeFor(places, level.dimension().location(), localServerLabel()).loose.addAll(known);
        }

        // 2) Loose balls and grave totems held by OTHER shards, read from the sync CrossShardRadar already maintains,
        //    so a radar here can name a ball or totem sitting in a dimension that lives on another server (the driving
        //    case: a player who died on OW1 and is now on the SMP). Only dimensions this server does not host itself
        //    (the same rule the radar packet uses), only live shards (an offline shard leaves no line), and fuzzed
        //    exactly like the local ones.
        if (shardActive())
        {
            String thisShard = ShardConfig.get().serverId;
            addRemote(places, server, setId, thisShard, CrossShardRadar.snapshotByOwner(), false);
            addRemote(places, server, setId, thisShard, CrossShardRadar.snapshotTotemsByOwner(), true);
        }

        // 3) Grave totems holding balls of this set, in every dimension this server hosts. A totem sits at one spot
        //    however many balls it holds, so its position is listed once and fuzzed to match the radar.
        for (ServerLevel level : server.getAllLevels())
        {
            if (level == null)
            {
                continue;
            }
            List<BlockPos> totems = totemPositionsIn(level, setId);
            if (totems.isEmpty())
            {
                continue;
            }
            placeFor(places, level.dimension().location(), localServerLabel())
                    .totems.addAll(RadarFuzz.fuzzForRadar(setId, totems));
        }

        String name = radar.getDisplayName().orElse(setId);
        if (places.isEmpty())
        {
            player.sendSystemMessage(Component.literal(PREFIX_COLOUR + "[" + name + "] ")
                    .append(Component.literal("No balls of this set are anywhere in the world right now.")
                            .withStyle(ChatFormatting.GRAY)));
            return;
        }

        MutableComponent line = Component.literal(PREFIX_COLOUR + "[" + name + "] ");
        List<Component> parts = new ArrayList<>();
        for (Place place : places.values())
        {
            String where = placeLabel(place.dimension, place.serverLabel);
            if (!place.loose.isEmpty())
            {
                parts.add(Component.literal(place.loose.size() + " in " + where + ": " + positions(place.loose))
                        .withStyle(ChatFormatting.WHITE));
            }
            if (!place.totems.isEmpty())
            {
                parts.add(Component.literal(place.totems.size() + " in a totem in " + where + ": "
                        + positions(place.totems)).withStyle(ChatFormatting.AQUA));
            }
        }
        for (int i = 0; i < parts.size(); i++)
        {
            if (i > 0)
            {
                line.append(Component.literal(" | ").withStyle(ChatFormatting.GRAY));
            }
            line.append(parts.get(i));
        }
        player.sendSystemMessage(line);
    }

    /** The Place for this dimension and server, created (in reading order) the first time it is asked for. */
    private static Place placeFor(Map<String, Place> places, ResourceLocation dimension, String serverLabel)
    {
        String key = dimension.toString() + "|" + (serverLabel == null ? "" : serverLabel);
        return places.computeIfAbsent(key, ignored -> new Place(dimension, serverLabel));
    }

    // Fold one CrossShardRadar snapshot (loose balls, or grave totems) into the places, naming the owning server.
    // Skips this shard's own subtree and any dimension this shard hosts a copy of, so the local sweeps above own
    // everything local and this only adds what genuinely lives on another shard. Positions arrive exact and are
    // fuzzed here, matching the local paths.
    private static void addRemote(Map<String, Place> places, MinecraftServer server, String setId, String thisShard,
            Map<String, Map<String, Map<String, List<BlockPos>>>> network, boolean intoTotems)
    {
        for (Map.Entry<String, Map<String, Map<String, List<BlockPos>>>> owner : network.entrySet())
        {
            if (owner.getKey().equals(thisShard))
            {
                continue; // our own balls are already covered by the live local sweeps
            }
            for (Map.Entry<String, Map<String, List<BlockPos>>> dimEntry : owner.getValue().entrySet())
            {
                ResourceLocation dim = ResourceLocation.tryParse(dimEntry.getKey());
                if (dim == null)
                {
                    continue;
                }
                if (server.getLevel(ResourceKey.create(Registries.DIMENSION, dim)) != null)
                {
                    continue; // this shard hosts a copy of that id: the local sweep above is the truth here
                }
                List<BlockPos> exact = dimEntry.getValue().get(setId);
                if (exact == null || exact.isEmpty())
                {
                    continue;
                }
                List<BlockPos> fuzzed = RadarFuzz.fuzzForRadar(setId, exact);
                Place place = placeFor(places, dim, labelForServer(owner.getKey()));
                if (intoTotems)
                {
                    place.totems.addAll(fuzzed);
                }
                else
                {
                    place.loose.addAll(fuzzed);
                }
            }
        }
    }

    // The positions of every grave totem in this level that holds at least one ball of this set: one entry per totem,
    // because the player is being told where to go, not how many balls sit inside.
    private static List<BlockPos> totemPositionsIn(ServerLevel level, String setId)
    {
        List<BlockPos> out = new ArrayList<>();
        for (GraveData data : GraveStorage.get(level).all())
        {
            Container container = data.container();
            for (int slot = 0; slot < container.getContainerSize(); ++slot)
            {
                if (setId.equals(DragonBallSets.setIdOf(container.getItem(slot))))
                {
                    out.add(data.pos());
                    break;
                }
            }
        }
        return out;
    }

    // True only when there is a live shard network to name a server on. Off a network (singleplayer, a standalone
    // server) the readout degrades to just the dimension rather than printing an empty or null server.
    private static boolean shardActive()
    {
        try
        {
            return ShardConfig.get().enabled && ShardSync.active();
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    // The label for a dimension this server hosts: "here" on a live network, null off one (so the server half is
    // omitted entirely rather than shown as a confusing self-reference).
    private static String localServerLabel()
    {
        return shardActive() ? "here" : null;
    }

    // The short label for another server id (SMP, OW1, OW2), falling back to the raw id when there is no tag for it.
    private static String labelForServer(String serverId)
    {
        if (serverId == null || serverId.isEmpty())
        {
            return null;
        }
        try
        {
            String tag = ShardTags.tagFor(serverId);
            return tag == null || tag.isEmpty() ? serverId : tag;
        }
        catch (Throwable ignored)
        {
            return serverId;
        }
    }

    // "Planet Surface (OW1)" or, off a network, just "Planet Surface".
    private static String placeLabel(ResourceLocation dimension, String serverLabel)
    {
        String pretty = prettyDimension(dimension);
        return serverLabel == null ? pretty : pretty + " (" + serverLabel + ")";
    }

    // Up to MAX_POSITIONS_PER_GROUP coordinates, comma-joined, with a count of any remainder.
    private static String positions(List<BlockPos> list)
    {
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(list.size(), MAX_POSITIONS_PER_GROUP);
        for (int i = 0; i < shown; i++)
        {
            if (i > 0)
            {
                sb.append("; ");
            }
            BlockPos p = list.get(i);
            sb.append(p.getX()).append(", ").append(p.getY()).append(", ").append(p.getZ());
        }
        if (list.size() > shown)
        {
            sb.append(" (+").append(list.size() - shown).append(" more)");
        }
        return sb.toString();
    }

    /**
     * A dimension id as something a player can read: "planet_surface" becomes "Planet Surface". The namespace is
     * kept only when it is not vanilla or ours, so a modded dimension is still unambiguous.
     */
    private static String prettyDimension(ResourceLocation id)
    {
        StringBuilder out = new StringBuilder();
        for (String word : id.getPath().split("_"))
        {
            if (word.isEmpty())
            {
                continue;
            }
            if (out.length() > 0)
            {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        String namespace = id.getNamespace();
        boolean familiar = "minecraft".equals(namespace) || "dragonminez".equals(namespace)
                || "shuruisutilities".equals(namespace) || "dmz_ragnarok".equals(namespace);
        return familiar ? out.toString() : out + " (" + namespace + ")";
    }
}
