package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.ItemStackHandler;

import net.shurui.shuruisutilities.compat.curios.DragonBallBagCurios;
import net.shurui.shuruisutilities.corrupted.ShadowDragonStorage;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagInventory;
import net.shurui.shuruisutilities.dragonballbag.DragonBallBagItem;
import net.shurui.shuruisutilities.grave.GraveData;
import net.shurui.shuruisutilities.grave.GraveStorage;
import net.shurui.shuruisutilities.shard.ShardConfig;
import net.shurui.shuruisutilities.shard.ShardSync;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * {@code /rgballs [set]}: the staff view of where every dragon ball is right now, op only (permission level 2).
 *
 * <p>For each set (every DragonMineZ set plus the corrupted set) it lists, star by star:
 * <ul>
 *   <li>balls in the WORLD on this server: placed blocks (exact position) and pending scatter spots (not generated
 *       yet, X/Z only), per dimension;</li>
 *   <li>balls HELD by online players here: inventory, ender chest, the equipped dragon ball bag and any bag carried
 *       in the inventory, with the player's name;</li>
 *   <li>balls in grave TOTEMS here, with the totem's owner;</li>
 *   <li>balls lying on the ground as ITEMS in loaded chunks;</li>
 *   <li>with the shard network on: the loose balls and totems the OTHER shards publish for the radar
 *       ({@link CrossShardRadar}), with their server id.</li>
 * </ul>
 * Balls held by players who are offline or on another shard live in their vault and are not visible from here; the
 * output says so. Positions are exact (never radar-fuzzed). Coordinates are click-to-suggest {@code /tp} commands.
 *
 * <p>A plain Forge command with a real permission gate, like {@link BallSetCommands} (which owns {@code /scatter}
 * and friends); public, because the dragon ball sets are public. Text is literal (no lang keys), server side only.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class BallLocatorCommand
{
    private BallLocatorCommand() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("rgballs")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> run(ctx, null))
                .then(Commands.argument("set", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(allSetIds(), builder))
                        .executes(ctx -> run(ctx, StringArgumentType.getString(ctx, "set")))));
    }

    /** One located ball: where, and what kind of place it is. */
    private record Spot(int star, Component text) {}

    private static int run(CommandContext<CommandSourceStack> ctx, String only)
    {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        Map<String, List<Spot>> bySet = new TreeMap<>();
        try
        {
            collectWorld(server, bySet);
            collectPlayers(server, bySet);
            collectGraves(server, bySet);
            collectItems(server, bySet);
            collectCorrupted(server, bySet);
            collectRemote(bySet);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[rgballs] lookup failed part way: {}", t.toString());
        }

        List<String> sets = new ArrayList<>(allSetIds());
        for (String id : bySet.keySet())
            if (!sets.contains(id))
                sets.add(id);
        if (only != null)
        {
            if (!sets.contains(only))
            {
                source.sendFailure(Component.literal("Unknown dragon ball set '" + only + "'. Known: "
                        + String.join(", ", sets)));
                return 0;
            }
            sets = List.of(only);
        }

        String here = ShardSync.active() ? " on " + ShardConfig.selfId() : "";
        source.sendSuccess(() -> Component.literal("Dragon balls" + here + ":").withStyle(ChatFormatting.GOLD), false);
        for (String setId : sets)
        {
            List<Spot> spots = new ArrayList<>(bySet.getOrDefault(setId, List.of()));
            spots.sort((a, b) -> Integer.compare(a.star(), b.star()));
            String dormant = BallDormancy.isDormant(server, setId) ? " (dormant)" : "";
            source.sendSuccess(() -> Component.literal(setId + dormant + ": " + spots.size() + " found")
                    .withStyle(ChatFormatting.YELLOW), false);
            for (Spot spot : spots)
            {
                MutableComponent line = Component.literal("  " + (spot.star() <= 0 ? "?" : spot.star()) + " star: ")
                        .withStyle(ChatFormatting.GRAY);
                line.append(spot.text());
                source.sendSuccess(() -> line, false);
            }
        }
        source.sendSuccess(() -> Component.literal("Players offline or on another server are not listed (their "
                + "balls are in the vault).").withStyle(ChatFormatting.DARK_GRAY), false);
        return Command.SINGLE_SUCCESS;
    }

    /* ---------------------------------------------------------------- sources */

    private static void collectWorld(MinecraftServer server, Map<String, List<Spot>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            DragonBallSavedData data = DragonBallSavedData.get(level);
            String dim = level.dimension().location().toString();
            for (String setId : dmzSetIds())
            {
                for (Map.Entry<Integer, List<BlockPos>> e : data.getActiveBalls(setId).entrySet())
                    if (e.getValue() != null)
                        for (BlockPos p : e.getValue())
                            add(out, setId, e.getKey(), place("block", dim, p, true));
                for (Map.Entry<Integer, List<BlockPos>> e : data.getPendingBalls(setId).entrySet())
                    if (e.getValue() != null)
                        for (BlockPos p : e.getValue())
                            add(out, setId, e.getKey(), place("not generated yet", dim, p, false));
            }
        }
    }

    private static void collectPlayers(MinecraftServer server, Map<String, List<Spot>> out)
    {
        for (ServerPlayer player : server.getPlayerList().getPlayers())
        {
            String name = player.getGameProfile().getName();
            scanContainer(player.getInventory(), out, name + " (inventory)");
            scanContainer(player.getEnderChestInventory(), out, name + " (ender chest)");
            try
            {
                ItemStack bag = DragonBallBagCurios.findEquipped(player);
                if (!bag.isEmpty())
                    scanBag(bag, out, name + " (equipped bag)");
            }
            catch (Throwable ignored)
            {
            }
        }
    }

    private static void collectGraves(MinecraftServer server, Map<String, List<Spot>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            String dim = level.dimension().location().toString();
            for (GraveData grave : GraveStorage.get(level).all())
            {
                String owner = grave.ownerName() == null || grave.ownerName().isEmpty() ? "unknown" : grave.ownerName();
                Container c = grave.container();
                for (int i = 0; i < c.getContainerSize(); i++)
                {
                    DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(c.getItem(i));
                    if (info != null)
                        add(out, info.setId, info.star, place("totem of " + owner, dim, grave.pos(), true));
                }
            }
        }
    }

    private static void collectItems(MinecraftServer server, Map<String, List<Spot>> out)
    {
        for (ServerLevel level : server.getAllLevels())
        {
            String dim = level.dimension().location().toString();
            for (Entity entity : level.getAllEntities())
            {
                if (entity instanceof ItemEntity item)
                {
                    DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(item.getItem());
                    if (info != null)
                        add(out, info.setId, info.star, place("dropped item", dim, item.blockPosition(), true));
                }
            }
        }
    }

    private static void collectCorrupted(MinecraftServer server, Map<String, List<Spot>> out)
    {
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        String dim = "minecraft:overworld";
        for (BlockPos p : storage.getPlaced())
            add(out, DragonBallSets.CORRUPTED_SET_ID, 0, place("block", dim, p, true));
        for (ShadowDragonStorage.Pending p : storage.getPending())
            add(out, DragonBallSets.CORRUPTED_SET_ID, p.star(),
                    place("not generated yet", dim, new BlockPos(p.x(), 0, p.z()), false));
    }

    private static void collectRemote(Map<String, List<Spot>> out)
    {
        if (!ShardSync.active())
            return;
        String self = ShardConfig.selfId();
        remote(CrossShardRadar.snapshotByOwner(), self, "block", out);
        remote(CrossShardRadar.snapshotTotemsByOwner(), self, "totem", out);
    }

    private static void remote(Map<String, Map<String, Map<String, List<BlockPos>>>> view, String self, String kind,
            Map<String, List<Spot>> out)
    {
        for (Map.Entry<String, Map<String, Map<String, List<BlockPos>>>> owner : view.entrySet())
        {
            if (owner.getKey().equalsIgnoreCase(self))
                continue;
            for (Map.Entry<String, Map<String, List<BlockPos>>> dim : owner.getValue().entrySet())
                for (Map.Entry<String, List<BlockPos>> set : dim.getValue().entrySet())
                    for (BlockPos p : set.getValue())
                        add(out, set.getKey(), 0, Component.literal(kind + " on server " + owner.getKey() + " in "
                                + dim.getKey() + " " + p.getX() + " " + p.getY() + " " + p.getZ())
                                .withStyle(ChatFormatting.AQUA));
        }
    }

    /* ---------------------------------------------------------------- helpers */

    private static void scanContainer(Container c, Map<String, List<Spot>> out, String where)
    {
        for (int i = 0; i < c.getContainerSize(); i++)
        {
            ItemStack stack = c.getItem(i);
            if (stack.getItem() instanceof DragonBallBagItem)
            {
                scanBag(stack, out, where + " bag");
                continue;
            }
            DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(stack);
            if (info != null)
                for (int n = 0; n < Math.max(1, stack.getCount()); n++)
                    add(out, info.setId, info.star, Component.literal("held by " + where)
                            .withStyle(ChatFormatting.GREEN));
        }
    }

    private static void scanBag(ItemStack bag, Map<String, List<Spot>> out, String where)
    {
        ItemStackHandler handler = DragonBallBagInventory.read(bag);
        for (int i = 0; i < handler.getSlots(); i++)
        {
            DragonBallTotemInfo.Info info = DragonBallTotemInfo.describe(handler.getStackInSlot(i));
            if (info != null)
                add(out, info.setId, info.star, Component.literal("held by " + where).withStyle(ChatFormatting.GREEN));
        }
    }

    private static Component place(String kind, String dim, BlockPos p, boolean withY)
    {
        String coords = p.getX() + " " + (withY ? Integer.toString(p.getY()) : "~") + " " + p.getZ();
        String tp = "/execute in " + dim + " run tp @s " + p.getX() + " " + (withY ? p.getY() + 1 : 200) + " "
                + p.getZ();
        return Component.literal(kind + " in " + dim + " ")
                .withStyle(ChatFormatting.WHITE)
                .append(Component.literal("[" + coords + "]").withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.literal("Click to fill a teleport there")))));
    }

    private static void add(Map<String, List<Spot>> out, String setId, Integer star, Component text)
    {
        out.computeIfAbsent(setId, k -> new ArrayList<>()).add(new Spot(star == null ? 0 : star, text));
    }

    private static List<String> dmzSetIds()
    {
        List<String> ids = new ArrayList<>();
        try
        {
            for (DragonBallSetDefinition def : DragonBallDefinitions.getBallSets())
                if (def != null && def.getId() != null)
                    ids.add(def.getId());
        }
        catch (Throwable ignored)
        {
        }
        return ids;
    }

    private static List<String> allSetIds()
    {
        List<String> ids = dmzSetIds();
        ids.add(DragonBallSets.CORRUPTED_SET_ID);
        return ids;
    }
}
