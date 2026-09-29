package net.shurui.shuruisutilities.api;

import net.minecraft.commands.CommandSourceStack;

public interface ScriptHandler
{
    // call before ServerStarting to register a custom script key
    void addScriptType(String key);

    // run scripts under key; null sender defaults to console
    boolean runEventScripts(String key, CommandSourceStack sender);

    boolean runEventScripts(String key, CommandSourceStack sender, Object additionalData);
}
