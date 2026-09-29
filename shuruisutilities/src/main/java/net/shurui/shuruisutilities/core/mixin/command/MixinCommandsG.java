package net.shurui.shuruisutilities.core.mixin.command;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

@Mixin(Commands.class)
public class MixinCommandsG<S>
{
    // elevate the parse-time permission level so SU's permission system (not vanilla op level) gates execution.
    // performPrefixedCommand calls parse(String, Object); CommandSourceStack still has the 9-arg public ctor.
    @SuppressWarnings("unchecked")
    @Redirect(method = "performPrefixedCommand",
            at = @At(value = "INVOKE",
                    target = "Lcom/mojang/brigadier/CommandDispatcher;parse(Ljava/lang/String;Ljava/lang/Object;)Lcom/mojang/brigadier/ParseResults;",
                    remap = false))
    public ParseResults<S> performCommand(CommandDispatcher<S> instance, String command, S source)
    {
        if (source instanceof CommandSourceStack && ((CommandSourceStack) source).getEntity() != null)
        {
            CommandSourceStack css = (CommandSourceStack) source;
            source = (S) new CommandSourceStack(css.getEntity(), css.getPosition(), css.getRotation(), css.getLevel(), 4, css.getTextName(),
                    css.getDisplayName(), css.getServer(), css.getEntity());
        }
        return instance.parse(command, source);
    }
}
