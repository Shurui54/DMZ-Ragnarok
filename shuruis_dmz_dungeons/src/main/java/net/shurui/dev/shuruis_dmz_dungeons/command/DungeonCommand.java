package net.shurui.dev.shuruis_dmz_dungeons.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.shurui.dev.shuruis_dmz_dungeons.Config;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonDimensions;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorManager;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonManager;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonRules;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonWarps;
import net.shurui.dev.shuruis_dmz_dungeons.network.OpenDungeonConfigPacket;
import net.shurui.dev.shuruis_dmz_dungeons.network.SddNet;

import java.util.ArrayList;
import java.util.List;

// command tree rooted at /rg dungeon (registered from ForgeEvents#onRegisterCommands). paste is the WorldEdit
// soft-dep subcommand. These are the PUBLIC subcommands (they work keyless today); the private "floor" and "crate"
// subtrees (procedural floors) live in the Ragnarok Key and merge onto the same /rg dungeon node when it is installed.
public final class DungeonCommand {

    private DungeonCommand() {
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_WARPS = (ctx, builder) ->
            SharedSuggestionProvider.suggest(DungeonWarps.get(ctx.getSource().getServer()).names(), builder);

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // /sdd moved onto the shared /rg tree: /sdd edit is now /rg dungeon edit. Brigadier merges this with
        // TicketCommand's /rg dungeon registration into one node.
        dispatcher.register(Commands.literal("rg")
                .then(Commands.literal("dungeon")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("tp")
                        .executes(ctx -> tp(ctx, new Vec3(0.5, 5.0, 0.5)))
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                .executes(ctx -> tp(ctx, Vec3Argument.getVec3(ctx, "pos")))))
                .then(Commands.literal("setwarp")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(DungeonCommand::setwarp)))
                .then(Commands.literal("warp")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_WARPS)
                                .executes(DungeonCommand::warp)))
                .then(Commands.literal("delwarp")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests(SUGGEST_WARPS)
                                .executes(DungeonCommand::delwarp)))
                .then(Commands.literal("warps")
                        .executes(DungeonCommand::listWarps))
                .then(Commands.literal("highlight")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1))
                                .executes(DungeonCommand::highlight)))
                // paste is the only subcommand touching WorldEdit. WorldEditCompat.isLoaded() guards the reflective
                // paste underneath, which is the soft-dep check that matters.
                .then(Commands.literal("paste")
                        .then(Commands.argument("schematic", StringArgumentType.string())
                                .then(Commands.argument("pos", Vec3Argument.vec3())
                                        .executes(DungeonCommand::paste))))
                // dungeon config GUI. NOT key-gated at the command level: the dungeon-wide rules work without the
                // key, so a keyless server can still open the editor for those. The screen hides the floor section
                // when the key is absent (OpenDungeonConfigPacket carries the gate), and the floor save path is
                // re-gated server-side.
                .then(Commands.literal("edit")
                        .executes(DungeonCommand::openGui))));

        // Legacy hidden alias: /sdd still works, redirected onto /rg dungeon. Registered here (not in TicketCommand)
        // so there is exactly one redirect; it targets the live merged node so ticket/bossreset resolve through it too.
        dispatcher.register(Commands.literal("sdd")
                .redirect(dispatcher.getRoot().getChild("rg").getChild("dungeon")));
    }

    private static final String M = "message.dmz_ragnarok.dungeons.";

    private static int setwarp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable(M + "run_as_player"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        DungeonWarps.Warp warp = new DungeonWarps.Warp(
                player.level().dimension().location().toString(),
                player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
        DungeonWarps.get(src.getServer()).set(name, warp);
        src.sendSuccess(() -> Component.translatable(M + "warp_set", name), true);
        return 1;
    }

    private static int warp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable(M + "run_as_player"));
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (DungeonWarps.teleport(player, name)) {
            src.sendSuccess(() -> Component.translatable(M + "warp_done", name), false);
            return 1;
        }
        src.sendFailure(Component.translatable(M + "warp_not_found_or_dim", name));
        return 0;
    }

    private static int delwarp(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "name");
        if (DungeonWarps.get(src.getServer()).remove(name)) {
            src.sendSuccess(() -> Component.translatable(M + "warp_deleted", name), true);
            return 1;
        }
        src.sendFailure(Component.translatable(M + "warp_not_found", name));
        return 0;
    }

    private static int listWarps(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        List<String> names = DungeonWarps.get(src.getServer()).names();
        src.sendSuccess(() -> Component.translatable(M + "warp_list", names.size(), names.toString()), false);
        return names.size();
    }

    private static int tp(CommandContext<CommandSourceStack> ctx, Vec3 pos) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable(M + "run_as_player"));
            return 0;
        }
        ServerLevel dungeon = DungeonDimensions.level(src.getServer());
        if (dungeon == null) {
            src.sendFailure(Component.translatable(M + "dungeon_dim_not_loaded"));
            return 0;
        }
        player.teleportTo(dungeon, pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
        src.sendSuccess(() -> Component.translatable(M + "tp_done", BlockPos.containing(pos).toString()), false);
        return 1;
    }

    private static int highlight(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable(M + "run_as_player"));
            return 0;
        }
        int radius = Math.min(IntegerArgumentType.getInteger(ctx, "radius"), Config.maxHighlightRadius);
        ServerLevel level = player.serverLevel();
        List<BlockPos> found = DungeonManager.findDisguisedSpawners(level, player.blockPosition(), radius);
        SddNet.highlightSpawners(player, found, 200); // 10 seconds
        src.sendSuccess(() -> Component.translatable(M + "highlight_result", found.size(), radius), false);
        return found.size();
    }

    private static int paste(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        String schematic = StringArgumentType.getString(ctx, "schematic");
        Vec3 v = Vec3Argument.getVec3(ctx, "pos");
        BlockPos origin = BlockPos.containing(v);
        ServerLevel level = src.getLevel();
        boolean ok = DungeonManager.pasteSchematic(level, origin, schematic);
        if (ok) {
            src.sendSuccess(() -> Component.translatable(M + "paste_done", schematic, origin.toString()), true);
            return 1;
        }
        src.sendFailure(Component.translatable(M + "paste_failed", schematic));
        return 0;
    }

    // open the config GUI on the sender's client, prefilled. gathers state server-side and pushes it in one S2C packet.
    private static int openGui(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer player)) {
            src.sendFailure(Component.translatable(M + "run_as_player"));
            return 0;
        }
        DungeonRules rules = DungeonRules.get(src.getServer());
        DungeonFloors floors = DungeonFloors.get(src.getServer());
        if (rules == null || floors == null) {
            src.sendFailure(Component.translatable(M + "dungeon_dim_not_loaded"));
            return 0;
        }
        boolean unlocked = DungeonFloorManager.floorsEnabled();
        List<net.minecraft.nbt.CompoundTag> floorNbts = new ArrayList<>();
        for (int i = 1; i <= floors.count(); i++) {
            DungeonFloorConfig c = floors.get(i);
            if (c != null) {
                floorNbts.add(c.save(new net.minecraft.nbt.CompoundTag()));
            }
        }
        SddNet.sendToPlayer(new OpenDungeonConfigPacket(unlocked, rules.pvp, rules.kiBlockDestruction,
                rules.blockEditing, rules.timeLimitSeconds, rules.cooldownSeconds, floorNbts), player);
        return 1;
    }

}
