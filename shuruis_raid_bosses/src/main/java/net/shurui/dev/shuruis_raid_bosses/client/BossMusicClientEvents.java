package net.shurui.dev.shuruis_raid_bosses.client;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client wiring for boss music: hush vanilla background music while a loop plays, cut the loop on disconnect,
 * and the {@code /bossmusic on|off} toggle. Client Forge bus, so a dedicated server never loads any of it.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_raids", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class BossMusicClientEvents {
    private BossMusicClientEvents() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        // While our loop plays, keep vanilla's music manager silent so the two never overlap on SoundSource.MUSIC.
        // stopPlaying() is cheap and idempotent; the manager simply never gets to start a track over the boss loop.
        if (BossMusicClient.isPlaying()) {
            Minecraft.getInstance().getMusicManager().stopPlaying();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        // A disconnect ends the fight from the client's side: drop the loop so nothing lingers or resumes.
        BossMusicClient.onWorldLeave();
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("bossmusic")
                .then(Commands.literal("on").executes(ctx -> {
                    BossMusicClient.setEnabled(true);
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.dmz_ragnarok.bossmusic.on"), false);
                    return 1;
                }))
                .then(Commands.literal("off").executes(ctx -> {
                    BossMusicClient.setEnabled(false);
                    ctx.getSource().sendSuccess(
                            () -> Component.translatable("command.dmz_ragnarok.bossmusic.off"), false);
                    return 1;
                }));
        event.getDispatcher().register(root);
    }
}
