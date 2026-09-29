package net.shurui.shuruisutilities.util;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.api.permissions.SUPermissions;
import net.shurui.shuruisutilities.api.permissions.WorldZone;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;
import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.core.misc.Translator;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.MutableComponent;
import net.minecraftforge.server.ServerLifecycleHooks;

public class CommandContextParcer {
    public final CommandSourceStack sender;
    public final ServerPlayer senderPlayer;
    public final UserIdent ident;
    public final CommandContext<CommandSourceStack> context;
    public final String methodParms;
    
    public CommandContextParcer(CommandContext<CommandSourceStack> ctx, String params)
    {
    	this.context = ctx;
    	this.sender = context.getSource();
    	this.senderPlayer = CommandUtils.getServerPlayer(sender);
        this.ident = UserIdent.get(sender);
        this.methodParms = params;
    }

    public void sendMessage(String message)
    {
    	ChatOutputHandler.sendMessage(sender, message);
    }

    public void sendMessage(MutableComponent message)
    {
    	ChatOutputHandler.sendMessage(sender, message);
    }

    public void confirm(String message, Object... args)
    {
    	ChatOutputHandler.chatConfirmation(sender, Translator.format(message, args));
    }

    public void notify(String message, Object... args)
    {
    	ChatOutputHandler.chatNotification(sender, Translator.format(message, args));
    }

    public void warn(String message, Object... args)
    {
    	ChatOutputHandler.chatWarning(sender, Translator.format(message, args));
    }

    public void error(String message, Object... args)
    {
    	ChatOutputHandler.chatError(sender, Translator.format(message, args));
    }


    public boolean hasPlayer()
    {
        return senderPlayer != null;
    }


    public UserIdent parsePlayer(String name, boolean mustExist, boolean mustBeOnline) throws SUCommandParsingException
    {
    	return CommandUtils.parsePlayer(name, mustExist, mustBeOnline);
    }

    public boolean hasPermission(String perm)
    {
        if (sender.getEntity() instanceof Player)
        return APIRegistry.perms.checkPermission(senderPlayer, perm);
        else return true;
    }

    // parse a Minecraft-format time string
    public Long mcParseTimeReadable(String timeStr) throws SUCommandParsingException
    {
    	return CommandUtils.mcParseTimeReadable(timeStr);
    }
    public long parseTimeReadable(String timeStr) throws SUCommandParsingException
    {
    	return CommandUtils.mcParseTimeReadable(timeStr);
    }

    public WorldPoint getSenderPoint()
    {
        CommandSourceStack s = sender != null ? sender : ServerLifecycleHooks.getCurrentServer().createCommandSourceStack();
        return new WorldPoint(s.getLevel().dimension(), s.getPosition());
    }

    public WorldZone getWorldZone() throws SUCommandParsingException
    {
        if (senderPlayer == null)
            throw new SUCommandParsingException("Player needed");
        return APIRegistry.perms.getServerZone().getWorldZone(senderPlayer.level());
    }

    public void needsPlayer() throws SUCommandParsingException
    {
        if (senderPlayer == null)
            throw new SUCommandParsingException(SUPermissions.MSG_NO_CONSOLE_COMMAND);
    }

    public boolean getArgumentBoolean(String argumentName) throws SUCommandParsingException {
    	try {
    		return BoolArgumentType.getBool(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    }

    public double getArgumentDouble(String argumentName) throws SUCommandParsingException {
    	try {
    		return DoubleArgumentType.getDouble(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    } 

    public float getArgumentFloat(String argumentName) throws SUCommandParsingException {
    	try {
    		return FloatArgumentType.getFloat(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    }

    public int getArgumentInteger(String argumentName) throws SUCommandParsingException {
    	try {
    		return IntegerArgumentType.getInteger(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    }

    public long getArgumentLong(String argumentName) throws SUCommandParsingException {
    	try {
    		return LongArgumentType.getLong(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    }
    
    
    public String getArgumentString(String argumentName) throws SUCommandParsingException {
    	try {
    		return StringArgumentType.getString(context, argumentName);
    	}catch(IllegalArgumentException e) {
    		throw new SUCommandParsingException(e.getMessage());
    	}
    }
}
