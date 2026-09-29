package net.shurui.shuruisutilities.worldborder.effect;

import net.shurui.shuruisutilities.core.commands.registration.SUCommandParsingException;
import net.shurui.shuruisutilities.util.PlayerInfo;
import net.shurui.shuruisutilities.worldborder.WorldBorder;
import net.shurui.shuruisutilities.worldborder.WorldBorderEffect;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Expected syntax: <interval>
 */
public class EffectSmite extends WorldBorderEffect
{

    public int interval;

    @Override
    public void provideArguments(CommandContext<CommandSourceStack> ctx) throws SUCommandParsingException
    {
        interval = IntegerArgumentType.getInteger(ctx, "interval");
    }

    @Override
    public void activate(WorldBorder border, ServerPlayer player)
    {
        if (interval <= 0)
            doEffect(player);
    }

    @Override
    public void tick(WorldBorder border, ServerPlayer player)
    {
        if (interval <= 0)
            return;
        PlayerInfo pi = PlayerInfo.get(player);
        if (pi.checkTimeout(this.getClass().getName()))
        {
            doEffect(player);
            pi.startTimeout(this.getClass().getName(), interval * 1000L);
        }
    }

    public void doEffect(ServerPlayer player)
    {

        LightningBolt lightningboltentity = EntityType.LIGHTNING_BOLT.create(player.level());
        lightningboltentity.moveTo(
                Vec3.atBottomCenterOf(BlockPos.containing(player.position().x, player.position().y, player.position().z)));
        lightningboltentity.setVisualOnly(true);
        player.level().addFreshEntity(lightningboltentity);
    }

    public String toString()
    {
        return "smite interval: " + interval + " smite";
    }

    public String getSyntax()
    {
        return "<interval>";
    }

}
