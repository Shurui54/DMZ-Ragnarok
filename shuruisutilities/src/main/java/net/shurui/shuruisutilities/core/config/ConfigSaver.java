package net.shurui.shuruisutilities.core.config;

public interface ConfigSaver extends ConfigLoader
{

    void save(boolean reload);

}
