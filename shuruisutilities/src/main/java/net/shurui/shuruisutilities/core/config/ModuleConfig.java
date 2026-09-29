package net.shurui.shuruisutilities.core.config;

/**
 * Module enable/disable state for the SU module launcher.
 *
 * <p>There used to be an operator switchboard (the flat {@code ShuruisUtilities/Modules.cfg} and, later, the
 * hierarchical {@code config/dmz_ragnarok/modules.cfg}) that could turn any module off. Batch M removed it: a
 * module's presence is now decided by installing its jar and a private feature by the Ragnarok Key, so there is
 * no per-module on/off file any more. Every module runs (subject only to the key allow-list in
 * {@code PublicContent}, enforced separately at server start).
 *
 * <p>{@link #get(String, boolean)} therefore returns the module's own annotation default, which is {@code true}
 * for every shipped {@code @SUModule}. Both old files are left on disk, unread. The class is kept because
 * {@code ConfigBase} constructs one and {@code ModuleContainer} asks it whether a module loads.
 */
public class ModuleConfig
{
    /**
     * Is this module enabled? The operator switchboard is gone, so this is simply the module's own default
     * ({@code annot.defaultModule()}, true for every shipped module). The two legacy switchboard files are left
     * on disk, ignored.
     */
    public boolean get(String name, boolean defaultValue)
    {
        return defaultValue;
    }
}
