package net.shurui.shuruisutilities.katchin;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * Tag keys used by the katchin material system. The JSON that populates these tags is owned by the asset/datapack
 * side; this class only declares the {@link TagKey}s the Java code references.
 *
 * <p>Harvest-gate semantics: a {@link net.minecraftforge.common.ForgeTier}'s tag argument is "the blocks that
 * REQUIRE that tier". Combined with {@link net.minecraftforge.common.TierSortingRegistry} (see {@link KatchinTiers}),
 * a block placed in tier T's tag can only be harvested by a tool of tier T or higher.
 * <ul>
 *   <li>{@link #NEEDS_GETE_TOOL} is DragonMineZ's own {@code dragonminez:needs_gete_tool} block tag. The katchin
 *       (dark) family blocks are added to it by the datapack, so katchin requires a gete-tier pickaxe.</li>
 *   <li>{@link #NEEDS_KATCHIN_TOOL} gates the katchi katchin (light) family: those nine blocks are added to it,
 *       so they require a katchin-tier pickaxe.</li>
 *   <li>{@link #NEEDS_KATCHI_KATCHIN_TOOL} is the tag the top tier declares. Nothing in this pack requires that
 *       tier, so the tag is intentionally empty (no JSON needed); it exists so the tier has a valid harvest tag.</li>
 *   <li>{@link #KATCHI_KATCHIN_ITEMS} holds the three colour ore items so they are fully interchangeable in
 *       crafting and as the katchi katchin tool repair material.</li>
 * </ul>
 */
public final class KatchinTags
{
    private KatchinTags() {}

    // DragonMineZ's own block tag; the katchin blocks are folded into it by the datapack so they need a gete pickaxe.
    public static final TagKey<Block> NEEDS_GETE_TOOL =
            TagKey.create(Registries.BLOCK, new ResourceLocation("dragonminez", "needs_gete_tool"));

    // SU-owned: blocks requiring a katchin-tier tool (the katchi katchin family lives here).
    public static final TagKey<Block> NEEDS_KATCHIN_TOOL =
            TagKey.create(Registries.BLOCK, new ResourceLocation(ShuruisUtilities.MODID, "needs_katchin_tool"));

    // SU-owned: the harvest tag the top tier declares. Intentionally empty (no block needs a katchi katchin tool).
    public static final TagKey<Block> NEEDS_KATCHI_KATCHIN_TOOL =
            TagKey.create(Registries.BLOCK, new ResourceLocation(ShuruisUtilities.MODID, "needs_katchi_katchin_tool"));

    // SU-owned: the three katchi katchin colour ore items, held together so crafting treats every colour the same.
    public static final TagKey<Item> KATCHI_KATCHIN_ITEMS =
            TagKey.create(Registries.ITEM, new ResourceLocation(ShuruisUtilities.MODID, "katchi_katchin"));
}
