package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraftforge.server.permission.nodes.PermissionNode;

/**
 * Lets SU's permission system gate DragonMineZ's commands, the same way it already gates vanilla and SU commands.
 *
 * <h2>The bug this closes</h2>
 * Every DMZ command node ({@code /dmzstats}, {@code /dmzskills}, {@code /dmzforms}, ...) guards itself with
 * {@code requires(source -> DMZPermissions.check(source, ...))}, and {@code check} calls
 * {@code DMZPermissions.hasPermission(source, node)}. That method resolves through Forge's PermissionAPI on the
 * player ENTITY and, failing that, {@code player.hasPermission(2)}, which reads the player's real op level
 * ({@code ServerPlayer.getPermissionLevel} = {@code MinecraftServer.getProfilePermissions}). It never looks at the
 * {@link CommandSourceStack}'s own permission level.
 *
 * <p>SU makes non-op grants work by ELEVATING the command source to level 4 at parse time (MixinCommandsG for
 * {@code performPrefixedCommand}, MixinServerPlayNetHandler for a player's chat command and its suggestions) and then
 * enforcing the real grant with {@code command.<node>} in CommandExecutionGuard and the client command tree. That
 * works for any command whose {@code requires} reads the SOURCE. DMZ reads the ENTITY instead, so the elevation never
 * reaches it: a non-op with the grant was still refused at parse, producing "Incorrect argument for command" and no
 * tab completion, while the command tree had already offered the command.
 *
 * <h2>The fix</h2>
 * Honour the source stack's permission level in DMZ's single permission chokepoint. When the source SU handed to the
 * parse is op-equivalent ({@code source.hasPermission(2)}, i.e. SU's level-4 elevation, a real op, the console, a
 * command block or a function), the check passes; otherwise it falls through to DMZ's own logic unchanged. This is
 * purely additive: it never denies anything DMZ would have allowed. The real authority stays where SU keeps it, on
 * the {@code command.<node>} grant checked by CommandExecutionGuard (execution) and the client command tree
 * (visibility and completion), so a non-op WITHOUT the grant is still refused (the command is not in their tree, and
 * the guard cancels it if typed anyway) and real ops are unaffected.
 *
 * <p>{@code check(source, selfNode, othersNode)} delegates to {@code hasPermission}, so hooking the one method covers
 * both the literal command gates and the argument-node "others" gates. Because the whole DMZ command becomes usable
 * once the source is elevated, the grant is coarse at the {@code command.<node>} literal level: granting
 * {@code command.dmzstats.*} unlocks the whole subtree, editing others included, exactly like every other command SU
 * gates. DMZ's own per-node self/others split is not preserved for a granted non-op; that is the same coarseness SU's
 * client tree already applies.
 *
 * <p>{@code remap = false}: {@code DMZPermissions} and its {@code hasPermission} are DMZ's own names, never remapped.
 * {@code require = 0} per the standing DMZ mixin rule: if DMZ restructures the method the injector degrades to DMZ's
 * own op-level gate rather than failing mod load. A green build does not prove this bound; launch-test against 2.1.3.
 */
@Mixin(targets = "com.dragonminez.server.commands.DMZPermissions", remap = false)
public abstract class MixinDmzCommandPermission
{
    @Inject(method = "hasPermission", at = @At("HEAD"), cancellable = true, require = 0)
    private static void su$honourElevatedSource(CommandSourceStack source, PermissionNode<Boolean> node,
            CallbackInfoReturnable<Boolean> cir)
    {
        if (source != null && source.hasPermission(2))
            cir.setReturnValue(true);
    }
}
