package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Drives the client side of triggered cosmetic animations: ages the FX list each tick, clears it on logout, and
 * registers the client-only options command.
 *
 * <p>The annotation is deliberately BARE (no modid): the five original addons are one jar now, so naming a modid
 * is a chance to name the wrong one, and a wrong modid on an EventBusSubscriber fails SILENTLY. This matches the
 * sibling {@link CosmeticClientEvents}.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class CosmeticAnimationClientEvents
{
    private CosmeticAnimationClientEvents()
    {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase == TickEvent.Phase.END)
            CosmeticAnimationClientStore.tick();
    }

    @SubscribeEvent
    public static void onLoggedOut(ClientPlayerNetworkEvent.LoggingOut event)
    {
        CosmeticAnimationClientStore.clear();
    }

    /**
     * {@code /cosmeticanims <all|self|off> [radius]}, a CLIENT command: whether this machine draws other players'
     * animations, and within how many blocks. A client command because it is a rendering preference, not a
     * server-authoritative fact, and it must work identically on a server that has never heard of the suite.
     */
    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event)
    {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cosmeticanims");
        root.then(Commands.argument("mode", StringArgumentType.word())
                .suggests((ctx, sb) ->
                {
                    sb.suggest("all");
                    sb.suggest("self");
                    sb.suggest("off");
                    return sb.buildFuture();
                })
                .executes(ctx -> apply(ctx.getSource(), StringArgumentType.getString(ctx, "mode"),
                        CosmeticAnimationClientOptions.radius()))
                .then(Commands.argument("radius", IntegerArgumentType.integer(8, 96))
                        .executes(ctx -> apply(ctx.getSource(), StringArgumentType.getString(ctx, "mode"),
                                IntegerArgumentType.getInteger(ctx, "radius")))));
        // A separate branch for the rig toggle: /cosmeticanims rigs <on|off>. Off falls every animation back to its
        // particle style, for a machine that cannot afford the heavy models.
        root.then(Commands.literal("rigs")
                .then(Commands.argument("state", StringArgumentType.word())
                        .suggests((ctx, sb) ->
                        {
                            sb.suggest("on");
                            sb.suggest("off");
                            return sb.buildFuture();
                        })
                        .executes(ctx -> applyRigs(ctx.getSource(), StringArgumentType.getString(ctx, "state")))));
        event.getDispatcher().register(root);
    }

    private static int applyRigs(CommandSourceStack source, String state)
    {
        boolean on = !"off".equalsIgnoreCase(state) && !"false".equalsIgnoreCase(state);
        CosmeticAnimationClientOptions.setRigs(on);
        source.sendSystemMessage(Component.translatable(
                on ? "message.dmz_ragnarok.cosmetic.anims.rigs.on" : "message.dmz_ragnarok.cosmetic.anims.rigs.off"));
        return 1;
    }

    private static int apply(CommandSourceStack source, String modeName, int radius)
    {
        CosmeticAnimationClientOptions.Mode mode = CosmeticAnimationClientOptions.Mode.byName(modeName);
        CosmeticAnimationClientOptions.set(mode, radius);
        source.sendSystemMessage(Component.literal(
                "Cosmetic animations: " + mode.name().toLowerCase(java.util.Locale.ROOT) + ", radius " + radius + "."));
        return 1;
    }
}
