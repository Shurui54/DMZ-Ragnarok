package net.shurui.shuruisutilities.core.moduleLauncher;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.objectweb.asm.Type;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigLoader;
import net.shurui.shuruisutilities.util.events.ConfigReloadEvent;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.ModFileScanData;

public class ModuleLauncher
{
    public ModuleLauncher()
    {
        instance = this;
    }

    public static ModuleLauncher instance;

    private static TreeMap<String, ModuleContainer> containerMap = new TreeMap<>();

    private static final Type MOD = Type.getType(SUModule.class);

    public void init()
    {
        LoggingHandler.sulog.info("Discovering and loading modules...");

        final List<ModFileScanData.AnnotationData> data = ModList.get().getAllScanData().stream()
                .map(ModFileScanData::getAnnotations).flatMap(Collection::stream)
                .filter(a -> MOD.equals(a.annotationType())).collect(Collectors.toList());

        LoggingHandler.sulog.info("Found {} SUModule annotations", data.size());
        for (ModFileScanData.AnnotationData asm : data)
        {
            LoggingHandler.sulog.debug("Found SUModule {}", asm.memberName());
        }

        // Create THE MODULES!
        ModuleContainer temp, other;
        for (ModFileScanData.AnnotationData asm : data)
        {
            temp = new ModuleContainer(asm);
            if(temp.version<ShuruisUtilities.CURRENT_MODULE_VERSION) {
            	temp.isLoadable=false;
            	LoggingHandler.sulog.error("Module: [" + temp.name + "] is outdated! Please update this module to use the latest dev jar! Disabling Module!");
            }
            if(temp.version>ShuruisUtilities.CURRENT_MODULE_VERSION) {
            	temp.isLoadable=false;
            	LoggingHandler.sulog.error("Module: [" + temp.name + "] is too new for this version of ShuruisUtilities! Please update your ShuruisUtilities installation! Disabling Module!");
            }
            if (temp.isLoadable && !APIRegistry.SU_EVENTBUS.post(new ModuleRegistrationEvent(temp)))
            {
                //LoggingHandler.sulog.debug("Checking if contanerMap contains: " + temp.name);
                if (containerMap.containsKey(temp.name))
                {
                    other = containerMap.get(temp.name);
                    if (temp.doesOverride && other.mod == ShuruisUtilities.instance)
                    {
                        LoggingHandler.sulog.debug("Duplicate module overrided the existing one");
                        containerMap.put(temp.name, temp);
                    }
                    else if (temp.mod == ShuruisUtilities.instance && other.doesOverride)
                    {
                        LoggingHandler.sulog.debug("Duplicate module was overrided by the existing one");
                        continue;
                    }
                    else
                    {
                        throw new RuntimeException(
                                "{SU-Module-Launcher} " + temp.name + " is conflicting with " + other.name);
                    }
                }
                else
                {
                    containerMap.put(temp.name, temp);
                }
                temp.createAndPopulate();
                LoggingHandler.sulog.debug("Discovered SU module " + temp.name);
            }
        }

        // Register modules with configuration manager
        for (ModuleContainer module : containerMap.values())
        {
            if (module.module instanceof ConfigLoader)
            {
                LoggingHandler.sulog.debug("Registering configuration for SU module " + module.name);
                ShuruisUtilities.getConfigManager().registerSpecs((ConfigLoader) module.module);
            }
            else
            {
                LoggingHandler.sulog.debug("No configuration for SU module " + module.name);
            }
        }

        ShuruisUtilities.getConfigManager().loadAllRegisteredConfigs();
        ShuruisUtilities.getConfigManager().buildAllRegisteredConfigs();
        // Moved to ServerAboutToStart Event in Main Class
        // ShuruisUtilities.getConfigManager().bakeAllRegisteredConfigs(false);
    }

    public void reloadConfigs()
    {
        ShuruisUtilities.getConfigManager().bakeAllRegisteredConfigs(true);
        APIRegistry.getSUEventBus().post(new ConfigReloadEvent());
    }

    public void unregister(String moduleName)
    {
        ModuleContainer container = containerMap.get(moduleName);
        try
        {
            if (container == null)
            {
                LoggingHandler.sulog.error("Module " + moduleName + " has a null containerMap entry!");
            }
            if (container.module == null)
            {
                LoggingHandler.sulog
                        .error("Module " + moduleName + " has a null module entry in the containerMap entry!");
            }
            MinecraftForge.EVENT_BUS.unregister(container.module);
            LoggingHandler.sulog.error("Un-Registered module:  " + moduleName);
        }
        catch (NullPointerException e)
        {
            LoggingHandler.sulog.error("Failed to un-register module:  " + moduleName);
            LoggingHandler.sulog.error(
                    "This could be a major issue, if anything unexpected happens please contact the ShuruisUtilities team!");
        }
        containerMap.remove(moduleName);
    }

    public void handleModuleParents() {
    	for(ModuleContainer mod :getModuleMap().values()) {
    		mod.handleParentMod();
    	}
    }

    public static Collection<String> getModuleList()
    {
        return containerMap.keySet();
    }

    public static Map<String, ModuleContainer> getModuleMap()
    {
        return containerMap;
    }

    @Nullable
    public static ModuleContainer getModuleContainer(String slug)
    {
        return containerMap.getOrDefault(slug, null);
    }
}
