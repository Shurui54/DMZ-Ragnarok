package net.shurui.shuruisutilities.worldborder;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;
import net.shurui.shuruisutilities.worldborder.effect.EffectBlock;
import net.shurui.shuruisutilities.worldborder.effect.EffectCommand;
import net.shurui.shuruisutilities.worldborder.effect.EffectDamage;
import net.shurui.shuruisutilities.worldborder.effect.EffectKick;
import net.shurui.shuruisutilities.worldborder.effect.EffectKnockback;
import net.shurui.shuruisutilities.worldborder.effect.EffectMessage;
import net.shurui.shuruisutilities.worldborder.effect.EffectPotion;
import net.shurui.shuruisutilities.worldborder.effect.EffectSmite;

public enum WorldBorderEffects
{
    COMMAND(EffectCommand.class), DAMAGE(EffectDamage.class), KICK(EffectKick.class), KNOCKBACK(EffectKnockback.class), MESSAGE(EffectMessage.class), POTION(
            EffectPotion.class), SMITE(EffectSmite.class), BLOCK(EffectBlock.class);

    public Class<?> clazz;

    WorldBorderEffects(Class<?> clazz)
    {
        this.clazz = clazz;
    }

    public WorldBorderEffect get()
    {
        try
        {
            return (WorldBorderEffect) clazz.newInstance();
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.error("There was a problem initializing Worldborder effects.");
            return null;
        }
    }

}
