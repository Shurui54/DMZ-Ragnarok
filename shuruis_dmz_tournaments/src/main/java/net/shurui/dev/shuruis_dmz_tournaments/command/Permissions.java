package net.shurui.dev.shuruis_dmz_tournaments.command;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.permission.PermissionAPI;
import net.minecraftforge.server.permission.events.PermissionGatherEvent;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;
import net.shurui.dev.shuruis_dmz_tournaments.Shuruis_dmz_tournaments;

/**
 * Forge permission nodes for the command tree, so servers can grant access via LuckPerms or similar.
 * <ul>
 *   <li>{@code dmz_ragnarok.tournaments.use}: player commands (join/leave/tpwait/status/list). Default: everyone.</li>
 *   <li>{@code dmz_ragnarok.tournaments.admin}: management (edit/open/start/cancel/npc/title). Default: ops (perm level 2).</li>
 * </ul>
 *
 * <p>The {@code tournaments.} path segment keeps these nodes distinct from the sibling addons' after the five
 * collapsed onto the shared {@code dmz_ragnarok} modid; without it every addon would register
 * {@code dmz_ragnarok.use} and Forge rejects the duplicate.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_tournaments")
public final class Permissions {
    public static final PermissionNode<Boolean> USE = new PermissionNode<>(
            new ResourceLocation(Shuruis_dmz_tournaments.MODID, "tournaments.use"),
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> true);

    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(
            new ResourceLocation(Shuruis_dmz_tournaments.MODID, "tournaments.admin"),
            PermissionTypes.BOOLEAN, (player, uuid, ctx) -> player != null && player.hasPermissions(2));

    private Permissions() {}

    @SubscribeEvent
    public static void onGatherNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(USE, ADMIN);
    }

    /**
     * Command predicate with no SU command node: console and command blocks fall back to op level, players are
     * checked against the Forge node. Prefer {@link #check(CommandSourceStack, PermissionNode, String)}, which also
     * honours the SU grant that decides whether the player can see the command in the first place.
     */
    public static boolean check(CommandSourceStack src, PermissionNode<Boolean> node) {
        return check(src, node, null);
    }

    /**
     * Command predicate for a node the SU permission tree knows as {@code command.<commandNode>}.
     *
     * <h2>Why the extra argument exists</h2>
     * {@link PermissionAPI#getPermission} falls through to each node's DEFAULT resolver unless the server has
     * selected a permission handler in {@code forge-server.toml}, and {@link #ADMIN}'s default resolver is
     * {@code player.hasPermissions(2)}: the vanilla ops file, which no SU grant can change (nothing in the suite
     * can raise a player's vanilla permission level, and SU's parse-time elevation rewrites the SOURCE, not the
     * player). So every admin subcommand here was op-only in practice whatever the permission file said, and a
     * non-op who HAD been granted the command saw it in their tree and got "Incorrect argument for command" when
     * they ran it, because a {@code .requires()} that says no makes the literal unparseable.
     *
     * <p>Order: the SU grant on the same {@code command.*} node that governs visibility and that
     * {@code ShuruisUtilities.commandEvent} enforces at execution, then the Forge node exactly as before, so a
     * selected handler (LuckPerms and the like) still has the last word, ops still pass through {@link #ADMIN}'s
     * own default resolver, and {@link #USE}'s "everyone" default keeps working. Nothing here removes a gate; it
     * only adds the grant an operator actually made.
     *
     * @param commandNode dotted literal path with no {@code command.} prefix, e.g. {@code "rg.tourney.edit"}.
     */
    public static boolean check(CommandSourceStack src, PermissionNode<Boolean> node, String commandNode) {
        ServerPlayer player = src.getPlayer();
        if (player == null) return src.hasPermission(2);
        if (net.shurui.shuruisutilities.core.commands.CommandGate.grantedCommand(src, commandNode)) return true;
        return PermissionAPI.getPermission(player, node);
    }
}
