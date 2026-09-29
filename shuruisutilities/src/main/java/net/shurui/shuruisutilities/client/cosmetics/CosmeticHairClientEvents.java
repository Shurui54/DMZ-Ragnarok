package net.shurui.shuruisutilities.client.cosmetics;

import java.util.Locale;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Registers {@code /cosmetichair <on|off>}, the CLIENT switch for whether an enclosing HEAD cosmetic (a pumpkin
 * head, a full mask) hides DragonMineZ's hair on this machine.
 *
 * <p>A client command, not a server one, because it is a pure rendering preference: it must work identically on
 * a server that has never heard of the suite, and nothing server side depends on the answer. It drives
 * {@link CosmeticRenderOptions#setHideHairUnderHelmets(boolean)}, which persists the choice client side. A GUI
 * button can call the same setter later; the command is the headless equivalent.
 *
 * <p>The annotation is deliberately BARE (no modid): the five original addons are one jar now, so naming a modid
 * is a chance to name the wrong one, and a wrong modid on an EventBusSubscriber fails SILENTLY. This matches the
 * sibling client event classes.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class CosmeticHairClientEvents
{
    private CosmeticHairClientEvents()
    {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cosmetichair");
        root.executes(ctx -> report(ctx.getSource()));
        root.then(Commands.argument("mode", StringArgumentType.word())
                .suggests((ctx, sb) ->
                {
                    sb.suggest("on");
                    sb.suggest("off");
                    return sb.buildFuture();
                })
                .executes(ctx -> apply(ctx.getSource(), StringArgumentType.getString(ctx, "mode"))));
        event.getDispatcher().register(root);
    }

    private static int apply(CommandSourceStack source, String modeName)
    {
        String mode = modeName == null ? "" : modeName.trim().toLowerCase(Locale.ROOT);
        boolean on;
        if (mode.equals("on") || mode.equals("true") || mode.equals("yes"))
            on = true;
        else if (mode.equals("off") || mode.equals("false") || mode.equals("no"))
            on = false;
        else
            return report(source);
        CosmeticRenderOptions.setHideHairUnderHelmets(on);
        source.sendSystemMessage(Component.literal(
                "Hide hair under enclosing head cosmetics: " + (on ? "on" : "off") + "."));
        return 1;
    }

    private static int report(CommandSourceStack source)
    {
        source.sendSystemMessage(Component.literal("Hide hair under enclosing head cosmetics: "
                + (CosmeticRenderOptions.hideHairUnderHelmets() ? "on" : "off")
                + ". Use /cosmetichair <on|off> to change."));
        return 1;
    }
}
