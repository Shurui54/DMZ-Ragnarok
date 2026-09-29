package net.shurui.shuruisutilities.runes;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Puts the runes and the rune bench into DragonMineZ's own "DMZ Armors" tab, where the armour they modify already
 * lives, rather than into a separate SU tab a player would have to know to look in.
 *
 * <p>Keyed on {@code dragonminez:armors}. If DMZ is absent or ever renames that tab the event simply never matches
 * and nothing is added, which is why this is a match rather than a lookup: there is no call to fail.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class RuneCreativeTab
{
    private RuneCreativeTab() {}

    /**
     * DMZ registers its tabs under names that repeat the namespace, so the armour tab is
     * {@code dragonminez:dragonminez_armors_tab} and NOT {@code dragonminez:armors}. Matching the short form is what
     * kept the runes out of the tab: the event simply never fired for a key nothing owns, and a match that never
     * matches looks exactly like a tab that refuses to take items.
     *
     * <p>The short id is still checked, so if DMZ ever tidies its names to the obvious form this keeps working.
     */
    private static final ResourceKey<CreativeModeTab> DMZ_ARMORS =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB,
                    new ResourceLocation("dragonminez", "dragonminez_armors_tab"));

    private static final ResourceKey<CreativeModeTab> DMZ_ARMORS_SHORT =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, new ResourceLocation("dragonminez", "armors"));

    @SubscribeEvent
    public static void onBuildTabs(BuildCreativeModeTabContentsEvent event)
    {
        if (!DMZ_ARMORS.equals(event.getTabKey()) && !DMZ_ARMORS_SHORT.equals(event.getTabKey()))
            return;
        // Bench first: it is the thing a player needs before any rune is useful.
        RuneBenchRegistry.BENCH_ITEM.ifPresent(event::accept);
        for (var rune : RuneItems.all())
            rune.ifPresent(event::accept);
    }
}
