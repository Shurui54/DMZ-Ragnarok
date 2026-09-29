package net.shurui.shuruisutilities.compat.sponge;

import org.spongepowered.api.Game;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.LoadedGameEvent;
import org.spongepowered.api.event.lifecycle.ProvideServiceEvent.GameScoped;
import org.spongepowered.api.event.lifecycle.StartingEngineEvent;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import net.shurui.shuruisutilities.core.environment.Environment;
import com.google.inject.Inject;

// SU-Sponge compat plugin. more to come.
@Plugin(value = "shuruisutilities-sponge")
public class SUSpongeCompat
{

    @Inject
    private Game game;

    @Listener
    public void checkEnvironment(StartingEngineEvent<?> e)
    {
        if (!game.platform().executionType().name().equals("SpongeForge"))
        {
            throw new RuntimeException(
                    "You must be running the Forge implementation of SpongeAPI on Minecraft Forge in order to load ShuruisUtilities!");
        }
    }

    @Listener
    public void register(LoadedGameEvent e)
    {
        Environment.registerSpongeCompatPlugin(game.pluginManager().plugin("worldedit").isPresent());
    }

}
