package net.shurui.shuruisutilities.api.modules;

import net.shurui.shuruisutilities.core.moduleLauncher.ModuleContainer;
import net.shurui.shuruisutilities.core.moduleLauncher.ModuleLauncher;

public class SUModules
{
    public boolean isModuleEnabled(String moduleSlug)
    {
        ModuleContainer mC = getModuleContainer(moduleSlug);

        // mC.isLoadable should always return true because items are only added to
        // ModuleLauncher.containerMap if isLoadable is true
        // This check is unnecessary but is added in case something changes in the
        // future.
        return mC != null && mC.isLoadable;
    }

    public ModuleContainer getModuleContainer(String moduleSlug)
    {
        return ModuleLauncher.getModuleContainer(moduleSlug);
    }
}
