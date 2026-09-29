package net.shurui.shuruisutilities.core.config;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public class ConfigBase
{
    protected static ModuleConfig moduleConfig;

    private Set<ConfigLoader> loaders = new HashSet<>();

    private Set<ConfigLoader> loadedLoaders = new HashSet<>();

    private Set<ConfigLoader> builtLoaders = new HashSet<>();

    public static Predicate<Object> stringValidator = a -> a instanceof String;
    public static Predicate<Object> intValidator = b -> b instanceof Integer;
    public static Predicate<Object> booleanValidator = c -> c instanceof Boolean;

    public ConfigBase()
    {
        moduleConfig = new ModuleConfig();
    }

    public void registerSpecs(ConfigLoader loader)
    {
        // make list of unique specs or config files
        if (!loaders.contains(loader))
            loaders.add(loader);
    }

    public void loadNBuildSpec(ConfigLoader loader)
    {
        loadConfigForced(loader);
        buildConfigForced(loader);
    }

    public void loadNBuildNBakeSpec(ConfigLoader loader)
    {
        loadConfigForced(loader);
        buildConfigForced(loader);
        bakeConfigForced(loader, false);
    }

    /*
     * Should only be called once
     */
    public void loadAllRegisteredConfigs()
    {
        LoggingHandler.sulog.debug("Loading configuration files");

        for (ConfigLoader loader : loaders)
        {
            if (loadedLoaders.contains(loader))
            {
                LoggingHandler.sulog
                        .error("Configuration file: " + loader.returnData().getName() + " is alredy loaded");
                continue;
            }
            if (builtLoaders.contains(loader))
            {
                LoggingHandler.sulog.error("Configuration file: " + loader.returnData().getName() + " is alredy built");
                continue;
            }

            loadedLoaders.add(loader);
            LoggingHandler.sulog.debug("Loading configuration file: " + loader.returnData().getName());
            loader.load(loader.returnData().getSpecBuilder(), false);
        }

        LoggingHandler.sulog.debug("Finished loading configuration files");
    }

    public void loadConfigForced(ConfigLoader loader)
    {
        loadedLoaders.add(loader);
        LoggingHandler.sulog.debug("Loading configuration file: " + loader.returnData().getName());
        loader.load(loader.returnData().getSpecBuilder(), false);
    }

    /*
     * Should only be called once
     */
    public void buildAllRegisteredConfigs()
    {
        LoggingHandler.sulog.debug("Building configuration files");
        for (ConfigLoader loader : loaders)
        {
            if (!loadedLoaders.contains(loader))
            {
                builtLoaders.add(loader);
                LoggingHandler.sulog.error(
                        "Cant Build config: " + loader.returnData().getName() + " because it hasen't been loaded");
                continue;
            }
            if (builtLoaders.contains(loader))
            {
                LoggingHandler.sulog.error("Configuration file: " + loader.returnData().getName() + " is alredy built");
            }
            else
            {
                LoggingHandler.sulog.debug("Building configuration file : " + loader.returnData().getName());
                loader.returnData().setSpec(loader.returnData().getSpecBuilder().build());
                builtLoaders.add(loader);
                registerConfigManual(loader.returnData().getSpec(), loader.returnData().getName(), true);
            }
        }
        LoggingHandler.sulog.debug("Finished building configuration files");
    }

    public void buildConfigForced(ConfigLoader loader)
    {
        LoggingHandler.sulog.debug("Building configuration file : " + loader.returnData().getName());
        loader.returnData().setSpec(loader.returnData().getSpecBuilder().build());
        builtLoaders.add(loader);
        registerConfigManual(loader.returnData().getSpec(), loader.returnData().getName(), true);
    }

    /*
     * Can be called any number of times loading, building, and baking needing to be done after this is called use
     * ShuruisUtilities.getConfigManager().loadNBuildNBakeSpec(ConfigLoader);
     */
    public void bakeAllRegisteredConfigs(boolean reload)
    {
        LoggingHandler.sulog.debug("Baking configuration files");
        for (ConfigLoader loader : loaders)
        {
            if (!loadedLoaders.contains(loader))
            {
                builtLoaders.add(loader);
                LoggingHandler.sulog.error(
                        "Cant Bake config: " + loader.returnData().getName() + " because it hasen't been loaded");
                continue;
            }
            if (!builtLoaders.contains(loader))
            {
                LoggingHandler.sulog
                        .error("Cant Bake config: " + loader.returnData().getName() + " because it hasen't been built");
                continue;
            }
            LoggingHandler.sulog.debug("Baked config:" + loader.returnData().getName());
            loader.bakeConfig(reload);
        }
        LoggingHandler.sulog.debug("Finished baking configuration files");
    }

    public void bakeConfigForced(ConfigLoader loader, boolean reload)
    {
        LoggingHandler.sulog.debug("Baked config:" + loader.returnData().getName());
        loader.bakeConfig(reload);
    }

    /*
     * public static void registerConfigManual(ForgeConfigSpec spec, Path path, boolean autoSave) { LoggingHandler.sulog.debug("Registering configuration fileZYA: "+path); if
     * (autoSave) { final CommentedFileConfig configData = CommentedFileConfig.builder(path) .sync() .autosave() .writingMode(WritingMode.REPLACE) .build(); configData.load();
     * spec.setConfig(configData); }else { final CommentedFileConfig configData = CommentedFileConfig.builder(path) .sync() .writingMode(WritingMode.REPLACE) .build();
     * configData.load(); spec.setConfig(configData); } }
     */
    public static void registerConfigManual(ForgeConfigSpec spec, String name, boolean autoSave)
    {
        LoggingHandler.sulog.debug("Registering configuration fileM: " + name);
        SUModConfig peModConfig;
        if (autoSave)
        {
            LoggingHandler.sulog.debug("Registering configuration fileT: " + name);
            peModConfig = new SUModConfig(ModLoadingContext.get().getActiveContainer(), ModConfig.Type.COMMON, spec,
                    name, true);
            // final CommentedFileConfig configData = CommentedFileConfig.builder(rootDirectory+"/"+name+".toml")
            // .sync()
            // .autosave()
            // .writingMode(WritingMode.REPLACE)
            // .build();
            // configData.load();
            // spec.setConfig(configData);
        }
        else
        {
            LoggingHandler.sulog.debug("Registering configuration fileF: " + name);
            peModConfig = new SUModConfig(ModLoadingContext.get().getActiveContainer(), ModConfig.Type.COMMON, spec,
                    name, false);
            // final CommentedFileConfig configData = CommentedFileConfig.builder(rootDirectory+"/"+name+".toml")
            // .sync()
            // .writingMode(WritingMode.REPLACE)
            // .build();
            // configData.load();
            // spec.setConfig(configData);
        }
        ModLoadingContext.get().getActiveContainer().addConfig(peModConfig);
        LoggingHandler.sulog.debug("Registering done for configuration fileF: " + name);

    }

    // public static void registerConfigAutomatic(List<ConfigData> data)
    // {
    // LoggingHandler.sulog.debug("Registering configuration files AUTO");
    // for(ConfigData config : data) {
    // SUModConfig peModConfig = new SUModConfig(ShuruisUtilities.MOD_CONTAINER, ModConfig.Type.SERVER, config.getSpec(), config.getName(), true);
    // ShuruisUtilities.MOD_CONTAINER.addConfig(peModConfig);
    // final CommentedFileConfig configData = CommentedFileConfig.builder(rootDirectory+"/"+Name+".toml")
    // .sync()
    // .autosave()
    // .writingMode(WritingMode.REPLACE)
    // .build();
    //
    // configData.load();
    // spec.setConfig(configData);
    // }
    // LoggingHandler.sulog.debug("Finished registering configuration files AUTO");
    // }

    public String getMainConfigName()
    {
        return "main";
    }

    public static ModuleConfig getModuleConfig()
    {
        return moduleConfig;
    }
}
