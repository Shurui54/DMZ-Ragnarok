package net.shurui.shuruisutilities.runes;

import java.util.EnumMap;
import java.util.Map;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * The seven rune items: one per stat plus the dormant rune. Registered like SU's other item groups; the register is
 * attached to the mod bus in {@code ShuruisUtilities}.
 */
public final class RuneItems
{
    private RuneItems() {}

    public static final DeferredRegister<Item> REGISTER =
            DeferredRegister.create(ForgeRegistries.ITEMS, ShuruisUtilities.MODID);

    private static final Map<RuneGrade, Map<RuneStat, RegistryObject<Item>>> BY_GRADE = new EnumMap<>(RuneGrade.class);

    /** The raw rune: what a broken piece hands back and what a player awakens into a stat rune. */
    public static final RegistryObject<Item> DORMANT =
            REGISTER.register("rune_dormant", () -> new RuneItem(new Item.Properties(), null));

    static
    {
        for (RuneGrade g : RuneGrade.values())
        {
            Map<RuneStat, RegistryObject<Item>> byStat = new EnumMap<>(RuneStat.class);
            for (RuneStat s : RuneStat.values())
                byStat.put(s, REGISTER.register("rune_" + s.key() + g.suffix(),
                        () -> new RuneItem(new Item.Properties(), s, g)));
            BY_GRADE.put(g, byStat);
        }
    }

    /**
     * The all-stat admin rune: one item that fills EVERY stat to the ceiling. The six per stat admin runes are
     * registered by the grade loop above like any other grade, which is what makes seven in total.
     */
    public static final RegistryObject<Item> ADMIN_ALL =
            REGISTER.register("rune_all_admin",
                    () -> new RuneItem(new Item.Properties(), null, RuneGrade.ADMIN, true));

    public static RegistryObject<Item> of(RuneStat stat, RuneGrade grade)
    {
        return stat == null ? DORMANT : BY_GRADE.get(grade == null ? RuneGrade.NORMAL : grade).get(stat);
    }

    public static RegistryObject<Item> of(RuneStat stat)
    {
        return of(stat, RuneGrade.NORMAL);
    }

    /** Every rune item, dormant first, for listings such as a creative tab. */
    public static java.util.List<RegistryObject<Item>> all()
    {
        java.util.List<RegistryObject<Item>> out = new java.util.ArrayList<>();
        out.add(DORMANT);
        out.add(ADMIN_ALL);
        for (RuneGrade g : RuneGrade.values())
            for (RuneStat s : RuneStat.values())
                out.add(BY_GRADE.get(g).get(s));
        return out;
    }
}
