package net.shurui.shuruisutilities.commands.util;

import java.util.HashMap;
import java.util.Map;

import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The loaded kits (S18b: the store half of {@code CommandKit}, which now lives in the Ragnarok Key). Core keeps it
 * because the prestige kits and the cross-shard config sync read and reload it. The records themselves are
 * {@link Kit} files in the DataManager folder {@code Kit} (name unchanged).
 *
 * <p>Nothing loads it keyless: the key's {@code /kit} loads the files when its command is registered, exactly as
 * before the move, so a keyless server holds no kits (the same empty map it had before) and never reads or writes a
 * kit file.
 */
public final class KitRegistry
{
    /** {@code su.commands.kit.bypasscooldown}: skip a kit's cooldown. Checked by {@link Kit#giveKit}. */
    public static final String PERM_BYPASS_COOLDOWN = "su.commands.kit.bypasscooldown";

    /** Every loaded kit by name. Replaced wholesale on a load. */
    public static Map<String, Kit> kits = new HashMap<>();

    private KitRegistry() {}

    /** Read every kit from disk. */
    public static void load()
    {
        kits = DataManager.getInstance().loadAll(Kit.class);
    }

    /** Re-read every kit from disk, for a kit file that arrived from another shard. Server thread. */
    public static void reloadFromDisk()
    {
        try
        {
            kits = DataManager.getInstance().loadAll(Kit.class);
            LoggingHandler.sulog.info("[kit] Reloaded {} kit(s) after a change from the network.", kits.size());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[kit] Could not reload kits after a network change: {}", t.toString());
        }
    }

    public static void removeKit(Kit kit)
    {
        kits.remove(kit.getName());
        DataManager.getInstance().delete(Kit.class, kit.getName());
    }

    public static void addKit(Kit kit)
    {
        kits.put(kit.getName(), kit);
        DataManager.getInstance().save(kit, kit.getName());
    }
}
