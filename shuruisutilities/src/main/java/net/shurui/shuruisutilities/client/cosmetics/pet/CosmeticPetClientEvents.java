package net.shurui.shuruisutilities.client.cosmetics.pet;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntities;

/**
 * Client wiring for the cosmetic pet: the mod bus binds the renderer, and the forge bus registers the client-only
 * {@code /cosmeticpets <show|hide>} preference command. There is no per-tick input feed like the mount has: a pet is
 * server-driven and every client just lerps toward its synced position.
 */
public final class CosmeticPetClientEvents
{
    private CosmeticPetClientEvents()
    {
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModBus
    {
        private ModBus()
        {
        }

        @SubscribeEvent
        public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event)
        {
            event.registerEntityRenderer(CosmeticPetEntities.COSMETIC_PET.get(), CosmeticPetRenderer::new);
        }
    }

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
    public static final class ForgeBus
    {
        private ForgeBus()
        {
        }

        /**
         * {@code /cosmeticpets <show|hide>}, a CLIENT command: whether this machine draws cosmetic pets. A client
         * command because it is a rendering preference, not a server-authoritative fact, and it must work identically
         * on a server that has never heard of the suite.
         */
        @SubscribeEvent
        public static void onRegisterClientCommands(RegisterClientCommandsEvent event)
        {
            LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("cosmeticpets");
            root.then(Commands.argument("mode", StringArgumentType.word())
                    .suggests((ctx, sb) ->
                    {
                        sb.suggest("show");
                        sb.suggest("hide");
                        return sb.buildFuture();
                    })
                    .executes(ctx -> apply(ctx.getSource(), StringArgumentType.getString(ctx, "mode"))));
            event.getDispatcher().register(root);
        }

        private static int apply(CommandSourceStack source, String mode)
        {
            boolean hide = "hide".equalsIgnoreCase(mode == null ? "" : mode.trim());
            CosmeticPetClientOptions.setHidden(hide);
            source.sendSystemMessage(Component.translatable(
                    hide ? "message.dmz_ragnarok.cosmetics.pets.hidden" : "message.dmz_ragnarok.cosmetics.pets.shown"));
            return 1;
        }
    }
}
