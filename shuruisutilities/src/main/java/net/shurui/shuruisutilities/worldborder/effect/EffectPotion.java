package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Expected syntax: <interval> <effect> <seconds> <amplifier>
 */
public class EffectPotion extends WorldBorderEffect
{

    public int id;

    public int duration;

    public int modifier;

    public int interval;

    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
        interval = IntegerArgumentType.getInteger(ctx, "interval");
        try
        {
            id = MobEffect.getId(ResourceArgument.getMobEffect(ctx, "effect").value());
        }
        catch (CommandSyntaxException e)
        {
            throw new SUCommandParsingException("Bad effect argument");
        }
        duration = IntegerArgumentType.getInteger(ctx, "seconds");
        modifier = IntegerArgumentType.getInteger(ctx, "amplifier");
    }

    @Override
    public void activate(WorldBorder border, ServerPlayer player)
    {
        player.addEffect(new MobEffectInstance(MobEffect.byId(id), duration, modifier, false, true, true));
    }

    @Override
    public void tick(WorldBorder border, ServerPlayer player)
    {
        if (interval <= 0)
            return;
        PlayerInfo pi = PlayerInfo.get(player);
        if (pi.checkTimeout(this.getClass().getName()))
        {
            player.addEffect(new MobEffectInstance(MobEffect.byId(id), duration, modifier, false, true, true));
            pi.startTimeout(this.getClass().getName(), interval * 1000L);
        }
    }

    @Override
    public String getSyntax()
    {
        return "<interval> <effect> <seconds> <amplifier>";
    }

    @Override
    public String toString()
    {
        return String.format("potion interval: %d id: %d duration: %d amplifier: %d", interval, id, duration, modifier);
    }
}
