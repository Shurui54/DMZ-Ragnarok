package net.shurui.shuruisutilities.core.moduleLauncher;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashSet;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.core.config.ConfigBase;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.Container;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.Instance;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.ModuleDir;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.ParentMod;
import net.shurui.shuruisutilities.core.moduleLauncher.SUModule.Preconditions;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.commands.CommandSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.forgespi.language.ModFileScanData;

public class ModuleContainer implements Comparable<Object>
{

    protected static HashSet<Class<?>> modClasses = new HashSet<>();

    public Object module, mod;
    Class<?> parentClass;

    // methods..
    private String reload;

    // fields
    private String instance, container, parentMod, moduleDir;

    // other vars..
    public final String className;
    public final String name;
    public final int version;
    private final boolean isCore;
    public boolean isLoadable = true;
    protected boolean doesOverride;

    public ModuleContainer(ModFileScanData.AnnotationData data)
    {
        // get the class....
        Class<?> c = null;
        className = data.memberName();

        try
        {
            c = Class.forName(className);
        }
        catch (Throwable e)
        {
            LoggingHandler.sulog.info("Error trying to load " + data.memberName() + " as a SUModule!");
            e.printStackTrace();

            isCore = false;
            name = "INVALID-MODULE";
            version = 0;
            return;
        }

        // checks original SUModule annotation.
        if (!c.isAnnotationPresent(SUModule.class))
        {
            throw new IllegalArgumentException(c.getName() + " doesn't have the @SUModule annotation!");
        }
        SUModule annot = (SUModule) c.getAnnotation(SUModule.class);
        if (annot == null)
        {
            throw new IllegalArgumentException(c.getName() + " doesn't have the @SUModule annotation!");
        }
        name = annot.name();
        isCore = annot.isCore();
        doesOverride = annot.doesOverride();
        version = annot.version();

        if (annot.canDisable())
        {
            if (!ConfigBase.getModuleConfig().get(name, annot.defaultModule()))
            {
                LoggingHandler.sulog.debug("Requested to disable module: " + name);
                isLoadable = false;
                return;
            }
            else
            {
                //LoggingHandler.sulog.debug("Requested to enable module: " + name);
            }
        }

        // try getting the parent mod.. and register it.
        parentClass = annot.parentMod();

        // check method annotations. they are all optional...
        Class<?>[] params;
        for (Method m : c.getDeclaredMethods())
        {
            if (m.isAnnotationPresent(Preconditions.class))
            {
                params = m.getParameterTypes();
                if (params.length != 0)
                {
                    throw new RuntimeException(m + " must take no arguments!");
                }
                if (!m.getReturnType().equals(boolean.class))
                {
                    throw new RuntimeException(m + " must return a boolean!");
                }
                m.setAccessible(true);

                try
                {
                    if (!((boolean) m.invoke(null)))
                    {
                        LoggingHandler.sulog.debug("Disabled module " + name);
                        isLoadable = false;
                        return;
                    }
                }
                catch (NullPointerException e)
                {
                    LoggingHandler.sulog.error(
                            String.format("Module: %s Preconditions field is not static!", name), e);
                }
                catch (IllegalAccessException | InvocationTargetException  e)
                {
                    LoggingHandler.sulog.error(
                            String.format("Exception Raised when testing preconditions for module: %s", name), e);
                }
                catch (IllegalArgumentException | SecurityException e)
                {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        }

        // collect field annotations... these are also optional.
        for (Field f : c.getDeclaredFields())
        {
            if (f.isAnnotationPresent(Instance.class))
            {
                if (instance != null)
                    throw new RuntimeException("Only one field may be marked as Instance");
                f.setAccessible(true);
                instance = f.getName();
            }
            else if (f.isAnnotationPresent(Container.class))
            {
                if (container != null)
                    throw new RuntimeException("Only one field may be marked as Container");
                if (f.getType().equals(ModuleContainer.class))
                    throw new RuntimeException("This field must have the type ModuleContainer!");
                f.setAccessible(true);
                container = f.getName();
            }
            else if (f.isAnnotationPresent(ParentMod.class))
            {
                if (parentMod != null)
                    throw new RuntimeException("Only one field may be marked as ParentMod");
                f.setAccessible(true);
                parentMod = f.getName();
            }
            else if (f.isAnnotationPresent(ModuleDir.class))
            {
                if (moduleDir != null)
                    throw new RuntimeException("Only one field may be marked as ModuleDir");
                if (!File.class.isAssignableFrom(f.getType()))
                    throw new RuntimeException("This field must be the type File!");
                f.setAccessible(true);
                moduleDir = f.getName();
            }
        }
    }

    protected void createAndPopulate()
    {
        Field f;
        Class<?> c;
        // instantiate.
        try
        {
            c = Class.forName(className);
            module = c.getDeclaredConstructor().newInstance();
        }
        catch (Throwable e)
        {
            LoggingHandler.sulog.warn(name + " could not be instantiated. SU will not load this module.");
            e.printStackTrace();
            isLoadable = false;
            return;
        }

        MinecraftForge.EVENT_BUS.register(module);
        // APIRegistry.getSUEventBus().register(module);

        // now for the fields...
        try
        {
            if (instance != null)
            {
                f = c.getDeclaredField(instance);
                f.setAccessible(true);
                f.set(module, module);
            }

            if (container != null)
            {
                f = c.getDeclaredField(container);
                f.setAccessible(true);
                f.set(module, this);
            }

            //if (parentMod != null)
            //{
            //    f = c.getDeclaredField(parentMod);
            //    f.setAccessible(true);
            //    f.set(module, mod);
            //}

            if (moduleDir != null)
            {
                File file = new File(ShuruisUtilities.getSUDirectory(), name);
                file.mkdirs();

                f = c.getDeclaredField(moduleDir);
                f.setAccessible(true);
                f.set(module, file);
            }
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.info("Error populating fields of " + name);
            throw new RuntimeException(e);
        }
    }

    public void runReload(CommandSource user)
    {
        if (!isLoadable || reload == null)
        {
            return;
        }

        try
        {
            Class<?> c = Class.forName(className);
            Method m = c.getDeclaredMethod(reload, new Class<?>[] { CommandSource.class });
            m.invoke(module, user);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.info("Error while invoking Reload method for " + name);
            throw new RuntimeException(e);
        }
    }

    public File getModuleDir()
    {
        return new File(ShuruisUtilities.getSUDirectory(), name);
    }

    @Override
    public int compareTo(Object o)
    {
        if (!(o instanceof ModuleContainer))
            return -1;

        ModuleContainer other = (ModuleContainer) o;

        if (equals(other))
        {
            return 0;
        }

        if (isCore && !other.isCore)
        {
            return 1;
        }
        else if (!isCore && other.isCore)
        {
            return -1;
        }

        return name.compareTo(other.name);
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof ModuleContainer))
        {
            return false;
        }

        ModuleContainer c = (ModuleContainer) o;

        return isCore == c.isCore && name.equals(c.name) && className.equals(c.className);
    }

    @Override
    public int hashCode()
    {
        return (11 + name.hashCode()) * 29 + className.hashCode();
    }

	public void handleParentMod() {
		String modid;

		// Since the suite merge, the SU code no longer registers its own @Mod container: every addon is hosted
		// inside the single dmz_ragnarok container. A module's parent mod is therefore no longer discoverable as a
		// loaded mod object whose class equals parentClass (no loaded container's getMod() returns a
		// ShuruisUtilities instance any more). The parent mod object is SU's own singleton, and the loaded
		// container that ships it is the active one SU cached at construction (dmz_ragnarok). Resolving the
		// container through that cache avoids hardcoding its id and survives a future rename.
		Object obj = ShuruisUtilities.instance;
		ModContainer contain = ShuruisUtilities.MOD_CONTAINER;

		if (obj == null || contain == null || !parentClass.isInstance(obj))
			throw new RuntimeException(
					parentClass + " isn't a loaded mod class!");

		modid = contain.getModId() + "-" + contain.getModInfo().getVersion();
		if (modClasses.add(parentClass))
			LoggingHandler.sulog
					.info("Modules from " + modid + " are being validated");
		mod = obj;
		Field f;
		Class<?> c;
		try {
			c = Class.forName(className);
		} catch (Throwable e) {
			LoggingHandler.sulog.warn(name + " could not be validated.");
			e.printStackTrace();
			return;
		}
		try {
			if (parentMod != null) {
				f = c.getDeclaredField(parentMod);
				f.setAccessible(true);
				f.set(module, mod);
			}
		} catch (Exception e) {
			LoggingHandler.sulog.info("Error populating fields of " + name);
			throw new RuntimeException(e);
		}
	}
}
