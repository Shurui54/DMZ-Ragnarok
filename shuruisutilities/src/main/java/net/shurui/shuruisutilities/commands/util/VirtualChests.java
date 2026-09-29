package net.shurui.shuruisutilities.commands.util;

import java.util.List;

import com.google.common.collect.ImmutableList;

import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;

/**
 * The virtual chest ({@code /pv}) constants and settings that core reads (S18b: split out of
 * {@code CommandVirtualchest}, which now lives in the Ragnarok Key). The public character slots carry the vault NBT
 * under {@link #VIRTUALCHEST_TAG}, and the shard inventory viewer shows a vault with the configured rows and name.
 * The values are baked from Commands.toml by the key's Commands module; keyless they keep these defaults.
 */
public final class VirtualChests
{
    /** Player persisted-data key of the vault list. Persisted: never rename. */
    public static final String VIRTUALCHEST_TAG = "VirtualChestItems";
    public static final String PERM_AMOUNT = "su.pv.amount";
    /** admin: open/edit another player's vaults via /pv <player> <n> */
    public static final String PERM_OTHERS = "su.pv.others";

    public static final List<MenuType<ChestMenu>> chestTypes = ImmutableList.of(MenuType.GENERIC_9x1,
            MenuType.GENERIC_9x2, MenuType.GENERIC_9x3, MenuType.GENERIC_9x4, MenuType.GENERIC_9x5,
            MenuType.GENERIC_9x6);

    public static int size = 54;
    public static int rowCount = 6;
    public static String name = "Vault";

    private VirtualChests() {}
}
