package net.shurui.shuruisutilities.swordsman;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

import net.shurui.shuruisutilities.core.config.ConfigData;
import net.shurui.shuruisutilities.core.config.ConfigLoaderBase;

/**
 * {@code ShuruisUtilities/Swordsman.toml}: what counts as a sword for the Konatsian swordsman class passive.
 *
 * <p>The bonus SIZES are not here on purpose. DragonMineZ keeps every class passive's numbers in the race's
 * {@code stats.json} ({@code classes.swordsman.passive.values}), which is per race, synced to clients (so the passive
 * tooltip shows the server's real numbers) and editable in the SDU race editor. This file only decides which held
 * items trigger the bonus, which is server wide and has nothing to do with any one race. As an SU loader it shows up
 * in the in-game options editor on its own (Combat tab).
 */
public class SwordsmanConfig extends ConfigLoaderBase
{
    private static final String CATEGORY = "Swordsman";

    private static ForgeConfigSpec SPEC;
    private static final ConfigData DATA = new ConfigData(CATEGORY, SPEC, new ForgeConfigSpec.Builder());

    static final List<String> DEFAULT_CATEGORIES = List.of("sword", "katana", "claymore", "cutlass", "rapier");
    static final List<String> DEFAULT_KI_WEAPON_TYPES = List.of("blade");

    private static ForgeConfigSpec.ConfigValue<List<? extends String>> SUswordCategories;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> SUextraSwordItems;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> SUexcludedItems;
    private static ForgeConfigSpec.ConfigValue<List<? extends String>> SUkiWeaponTypes;

    // Baked copies. Seeded with the defaults so a check before the first bake still behaves.
    static volatile Set<String> swordCategories = Set.copyOf(DEFAULT_CATEGORIES);
    static volatile Set<String> extraSwordItems = Set.of();
    static volatile Set<String> excludedItems = Set.of();
    static volatile Set<String> kiWeaponTypes = Set.copyOf(DEFAULT_KI_WEAPON_TYPES);

    @Override
    public void load(Builder builder, boolean isReload)
    {
        builder.push(CATEGORY);
        SUswordCategories = builder
                .comment("DragonMineZ weapon categories that count as a sword (the 'category' of a weapon_attributes "
                        + "entry; vanilla and modded swords get one through DMZ's fallback). An item with a category "
                        + "NOT listed here never counts, even if it is a SwordItem (DMZ's Power Pole and Tamagami "
                        + "trident and hammer are SwordItems).")
                .defineListAllowEmpty(List.of("swordCategories"), () -> DEFAULT_CATEGORIES, SwordsmanConfig::isString);
        SUextraSwordItems = builder
                .comment("Extra items that always count as a sword: item ids (modid:item) or item tags (#modid:tag).")
                .defineListAllowEmpty(List.of("extraSwordItems"), List::of, SwordsmanConfig::isString);
        SUexcludedItems = builder
                .comment("Items that never count as a sword, checked first: item ids (modid:item) or item tags "
                        + "(#modid:tag).")
                .defineListAllowEmpty(List.of("excludedItems"), List::of, SwordsmanConfig::isString);
        SUkiWeaponTypes = builder
                .comment("DragonMineZ ki weapon types (Ki Manipulation) that count as a sword while drawn. 'blade' "
                        + "is DMZ's ki sword.")
                .defineListAllowEmpty(List.of("kiWeaponTypes"), () -> DEFAULT_KI_WEAPON_TYPES, SwordsmanConfig::isString);
        builder.pop();
    }

    private static boolean isString(Object o)
    {
        return o instanceof String;
    }

    @Override
    public void bakeConfig(boolean reload)
    {
        swordCategories = lower(SUswordCategories.get());
        extraSwordItems = lower(SUextraSwordItems.get());
        excludedItems = lower(SUexcludedItems.get());
        kiWeaponTypes = lower(SUkiWeaponTypes.get());
    }

    private static Set<String> lower(List<? extends String> in)
    {
        if (in == null)
            return Set.of();
        return in.stream().filter(s -> s != null && !s.isBlank()).map(s -> s.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public ConfigData returnData()
    {
        return DATA;
    }
}
