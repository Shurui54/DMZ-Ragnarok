package net.shurui.shuruisutilities.core.config;

import java.nio.file.Path;
import java.util.function.Function;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.config.ConfigFileTypeHandler;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

public class SUModConfig extends ModConfig
{
    private static final SUConfigFileTypeHandler SU_TOML = new SUConfigFileTypeHandler();

    private final boolean autoSave;

    public SUModConfig(ModContainer container, Type type, ForgeConfigSpec spec, String name, boolean autoSave)
    {
        super(type, spec, container, name + ".toml");
        this.autoSave = autoSave;
    }

    @Override
    public ConfigFileTypeHandler getHandler()
    {
        return SU_TOML;
    }

    public boolean isAutoSave()
    {
        return autoSave;
    }

    private static class SUConfigFileTypeHandler extends ConfigFileTypeHandler
    {

        private static Path getPath(Path configPath)
        {
            // Intercept server config path reading for SU configs and reroute it to the
            // normal config directory
            if (configPath.endsWith("serverconfig") || FMLPaths.CONFIGDIR.get() == configPath)
            {
                return ShuruisUtilities.getSUDirectory().toPath();
            }
            return FMLPaths.CONFIGDIR.get();
        }

        @Override
        public Function<ModConfig, CommentedFileConfig> reader(Path configBasePath)
        {
            return super.reader(getPath(configBasePath));
        }

        @Override
        public void unload(Path configBasePath, ModConfig config)
        {
            super.unload(getPath(configBasePath), config);
        }
    }
}
