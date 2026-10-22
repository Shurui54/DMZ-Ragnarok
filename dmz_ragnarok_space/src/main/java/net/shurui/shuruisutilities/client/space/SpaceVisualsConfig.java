package net.shurui.shuruisutilities.client.space;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.BoolArgumentType;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Client-side preferences for how open space is drawn, stored as config/dmz_ragnarok-spacevisuals.json like {@link
 * PlanetInfoConfig}. Pure client looks: the server never needs to know, so this is a small local json, not a synced
 * config. Toggled in game with the client command {@code /spacevisuals orbitlines <true|false>} (and {@code distant},
 * {@code deepsky}), or by editing the file.
 */
public final class SpaceVisualsConfig
{
    /** Faint orbit lines for the main system's rings and nearby systems' planets. Off by default (owner: optional). */
    public boolean orbitLines = false;

    /** Distant systems and far bodies drawn as lit planet discs. On by default; off falls back to bare sun beacons. */
    public boolean distantBodies = true;

    /** The extra deep starfield and nebula layer in the space sky. Read when the sky buffers are first built. */
    public boolean deepSky = true;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SpaceVisualsConfig instance;

    public static SpaceVisualsConfig get()
    {
        if (instance == null)
        {
            instance = load();
        }
        return instance;
    }

    private static File file()
    {
        return FMLPaths.CONFIGDIR.get().resolve("dmz_ragnarok-spacevisuals.json").toFile();
    }

    private static SpaceVisualsConfig load()
    {
        try (FileReader in = new FileReader(file()))
        {
            SpaceVisualsConfig c = GSON.fromJson(in, SpaceVisualsConfig.class);
            if (c != null)
            {
                return c;
            }
        }
        catch (Exception ignored)
        {
            // no file yet, or an unreadable one: the defaults. Never fatal.
        }
        return new SpaceVisualsConfig();
    }

    public void save()
    {
        try (FileWriter out = new FileWriter(file()))
        {
            GSON.toJson(this, out);
        }
        catch (Exception ignored)
        {
            // a failed write only loses the preference for next launch.
        }
    }

    /** The {@code /spacevisuals} client command. */
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", value = Dist.CLIENT)
    public static final class Command
    {
        private Command()
        {
        }

        @SubscribeEvent
        public static void onRegisterClientCommands(RegisterClientCommandsEvent event)
        {
            event.getDispatcher().register(Commands.literal("spacevisuals")
                    .then(Commands.literal("orbitlines").then(Commands.argument("on", BoolArgumentType.bool())
                            .executes(ctx -> set(ctx.getSource(), "orbitlines", BoolArgumentType.getBool(ctx, "on")))))
                    .then(Commands.literal("distant").then(Commands.argument("on", BoolArgumentType.bool())
                            .executes(ctx -> set(ctx.getSource(), "distant", BoolArgumentType.getBool(ctx, "on")))))
                    .then(Commands.literal("deepsky").then(Commands.argument("on", BoolArgumentType.bool())
                            .executes(ctx -> set(ctx.getSource(), "deepsky", BoolArgumentType.getBool(ctx, "on"))))));
        }

        private static int set(CommandSourceStack src, String what, boolean on)
        {
            SpaceVisualsConfig c = get();
            switch (what)
            {
                case "orbitlines" -> c.orbitLines = on;
                case "distant" -> c.distantBodies = on;
                default -> c.deepSky = on;
            }
            c.save();
            String note = "deepsky".equals(what) ? " (applies after a restart)" : "";
            src.sendSystemMessage(Component.literal("Space visuals: " + what + " " + (on ? "on" : "off") + note));
            return 1;
        }
    }
}
