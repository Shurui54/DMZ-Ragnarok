package net.shurui.shuruisutilities.core.commands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.annotation.Nonnull;

import org.jetbrains.annotations.NotNull;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.core.misc.CommandPermissionManager;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.BaseCommandBlock;
import net.shurui.shuruisutilities.api.permissions.DefaultPermissionLevel;

public abstract class ShuruisUtilitiesCommandBuilder extends CommandProcessor
{
    protected LiteralArgumentBuilder<CommandSourceStack> baseBuilder;

    boolean enabled;

    // Command usage

    // Root gate for every SU command. The op level is the ordinary answer: SU elevates a player's SOURCE to level 4
    // at parse time (MixinServerPlayNetHandler), so this passes for players and the real gates are the command.<node>
    // check in ShuruisUtilities#commandEvent and the guard inside the command. The SU grant is ORed in for the case
    // where that elevation is not there: on a Bukkit hybrid the mixin into ServerGamePacketListenerImpl is skipped
    // (see SUMixinConfig), and without this a non-op could not parse an OP-level SU command however many permissions
    // they had been granted. Same node the client tree is built from, so seeing a command and being able to type it
    // stop disagreeing. Nothing is widened for a player who was granted nothing: command.* nodes are deny-by-default.
    private static boolean mayParse(CommandSourceStack source, DefaultPermissionLevel level, String commandNode)
    {
        return source.hasPermission(CommandPermissionManager.fromDefaultPermissionLevel(level))
                || CommandGate.grantedCommand(source, commandNode);
    }

    public ShuruisUtilitiesCommandBuilder(boolean enabled)
    {
        final String node = getName();
        this.baseBuilder = Commands.literal(node).requires(source -> mayParse(source, getPermissionLevel(), node));
        this.enabled = enabled;

    }

    public ShuruisUtilitiesCommandBuilder(boolean enabled, String name, DefaultPermissionLevel level)
    {
        final String node = getFullName(name);
        this.baseBuilder = Commands.literal(node).requires(source -> mayParse(source, level, node));
        this.enabled = enabled;

    }

    public LiteralArgumentBuilder<CommandSourceStack> getMainBuilder()
    {
        return baseBuilder;
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    abstract public LiteralArgumentBuilder<CommandSourceStack> setExecution();

    // Permissions
    public boolean hasPermission(CommandSourceStack sender, String perm)
    {
        if (!canConsoleUseCommand() && !(sender.getEntity() instanceof Player))
            return false;
        if (sender.getEntity() != null && sender.getEntity() instanceof Player)
            return APIRegistry.perms.checkPermission(getServerPlayer(sender), perm);
        CommandSource source = GetSource(sender);
        return source instanceof MinecraftServer || source instanceof BaseCommandBlock;
    }

    /**
     * The form to use in a SUBCOMMAND's {@code .requires()}: the feature node above, OR the SU grant on the
     * {@code command.<commandNode>} node that decides whether this subcommand is in the player's command tree.
     *
     * <h2>Why the second half is needed</h2>
     * Visibility and execution in this suite are both decided on {@code command.*}: SU builds the client command
     * tree from it ({@code MixinCommands}) and cancels execution on it ({@code ShuruisUtilities#commandEvent}).
     * A {@code .requires()} that asks a different question is a third gate, and when it is the one that says no
     * the player sees the subcommand and Brigadier answers "Incorrect argument for command", which reads like a
     * typo rather than the permission refusal it is. ORing the command node in makes the granted-and-visible case
     * runnable. It adds nothing for an ungranted player, because {@code command.*} nodes are registered
     * deny-by-default.
     *
     * @param commandNode dotted literal path with no {@code command.} prefix, e.g. {@code "money.give"}.
     */
    public boolean hasPermission(CommandSourceStack sender, String perm, String commandNode)
    {
        return hasPermission(sender, perm) || CommandGate.grantedCommand(sender, commandNode);
    }

    public abstract boolean canConsoleUseCommand();

    public abstract DefaultPermissionLevel getPermissionLevel();

    // Command alias

    protected @NotNull abstract String getPrimaryAlias();

    @Nonnull
    protected String[] getDefaultSecondaryAliases()
    {
        return new String[] {};
    }

    public String getName()
    {
    	return getFullName(getPrimaryAlias());
    }

    /**
     * Returns the command name as-is. SU no longer force-prefixes commands with {@code fe}; each command is
     * registered once under its bare primary alias (e.g. {@code warp}, not {@code suwarp}). Conflicts with
     * vanilla/other-mod commands are resolved by {@link SUCommandManager#checkOverwritingCommands} when
     * {@code SUConfig.overwriteConflictingCommands} is enabled.
     */
    public String getFullName(String name) {
        return name;
    }

    /**
     * The module this command is filed under (the permission editor's tab), or null to derive it from the class
     * package ({@code SUCommandManager.moduleOf}). A command moved into the Ragnarok Key lives outside the
     * {@code net.shurui.shuruisutilities} packages, so it overrides this with the module it belonged to in core.
     */
    public String getModuleOverride()
    {
        return null;
    }

    public List<String> getAliases()
    {
        return new ArrayList<>(Arrays.asList(getDefaultSecondaryAliases()));
    }

    /**
     * Secondary aliases only. The primary alias is the main command name, so it is not repeated here (doing so
     * would register the same name twice).
     */
    public List<String> getDefaultAliases()
    {
        return getAliases();
    }

    /**
     * Registers additional permissions
     */
    public void registerExtraPermissions()
    {
        /* do nothing */
    }

}