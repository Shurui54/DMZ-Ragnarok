package net.shurui.shuruisutilities.client.planet;

import net.minecraft.resources.ResourceLocation;

/**
 * The single place every per-planet tint colour is chosen. DragonMineZ paints Namek's grass block, ajissa leaves and
 * short grass from greyscale textures (shipped by SU under {@code shuruisutilities:block/}), and SU tints them per
 * biome so each planet reads differently. Retune a planet's look by editing ONE constant here.
 *
 * <p>WHY A CODE MAP AND NOT THE BIOME FIELDS. A biome carries only six colour fields (grass, foliage, water, water fog,
 * fog, sky). Grass and foliage map naturally, but the deferred blocks (ajissa planks, namek dirt, the rocky blocks and
 * the flowers) have NO natural biome field, and the ajissa flowers in particular want a red that no biome field carries.
 * Keeping every planet's palette in one keyed-by-biome table lets those blocks be given colours a biome could never
 * express, and keeps grass and foliage retunable in the same spot. The main Namek surface biome (ajissa_plains) does not
 * even author grass_color/foliage_color, so reading the biome fields would fall back to a vanilla-plains green and turn
 * real Namek the wrong colour; the DEFAULT below is a Namek teal so any biome not listed keeps the Namek look.
 *
 * <p>The values are MULTIPLY tints (final pixel = greyscale texel * tint / 255), so a tint can only darken, never
 * brighten. The supplied greyscale set is fairly dark, so results are muted; push the dominant channel toward 255 for a
 * more vivid planet, or brighten the source textures if more headroom is wanted.
 */
public final class NamekBlockTints
{
    private NamekBlockTints()
    {
    }

    // Namek default: a teal-green close to DragonMineZ's original hand-painted Namek grass, so any planet or dimension
    // not listed below (real Namek included) keeps the Namek look rather than turning vanilla-green.
    private static final int GRASS_DEFAULT = 0x14F0F0;
    // Planet Vegeta: the user wants it clearly greener than Namek.
    private static final int GRASS_VEGETA = 0x82FF3C;
    // Planet Beerus: the user wants it purple.
    private static final int GRASS_BEERUS = 0xC85AFF;

    private static final int FOLIAGE_DEFAULT = 0x3CD2FF;
    private static final int FOLIAGE_VEGETA = 0x6EF03C;
    private static final int FOLIAGE_BEERUS = 0xBE55F0;

    // warm pale ajissa wood DragonMineZ ships (greyscale planks are bright, avg luminance ~199), so real Namek and any
    // off-planet placement look unchanged. Vegeta and Beerus override toward each planet's palette so wood matches the
    // rest of the surface set there: Vegeta a warm rusty timber, Beerus a muted violet-grey timber. Kept as its own
    // category (not the grass green) because the user's note "planks do not come out green" wants wood to read as wood.
    private static final int WOOD_DEFAULT = 0xF2D8A6;
    private static final int WOOD_VEGETA = 0xC08A50;
    private static final int WOOD_BEERUS = 0xA88CC0;

    // look unchanged; Vegeta reads as a rusty arid ground, Beerus as a muted violet-grey soil.
    private static final int DIRT_DEFAULT = 0xE8B888;
    private static final int DIRT_VEGETA = 0xCE7A50;
    private static final int DIRT_BEERUS = 0xB090C0;

    // tint per planet keeps them a matched set). Default reproduces the DMZ pinkish stone; Vegeta reddish rock; Beerus
    // violet-grey rock.
    private static final int ROCK_DEFAULT = 0xEFC7A6;
    private static final int ROCK_VEGETA = 0xCE7856;
    private static final int ROCK_BEERUS = 0xAE8EC2;

    // as a deeper, darker stone than the surface rock, but its greyscale texture (luma ~100) is nearly as bright as the
    // surface stone greyscale (~107), so reusing the ROCK tint straight would make it look identical to rocky_stone.
    // These are the ROCK tones scaled to ~72% brightness: same planet hue as the rock above it, one clear step darker, so
    // Vegeta reads as a deep rusty stone, Beerus a deep violet stone, and everywhere else a deep warm grey-brown.
    private static final int DEEPSLATE_DEFAULT = 0xAC8F78;
    private static final int DEEPSLATE_VEGETA = 0x94563E;
    private static final int DEEPSLATE_BEERUS = 0x7D668C;

    // lotus a teal-green pad), NOT their real-world colours, so the DEFAULTs below restore that Namek look per species.
    // The user wants planet flowers reddish on Vegeta, so both Vegeta and Beerus OVERRIDE every species to one planet
    // colour (Vegeta red, Beerus blue). Species differ only in their Namek default. The greyscale flower textures are
    // dark (opaque avg luminance ~87), so the SU copies are brightened to ~150 before tinting; even so a multiply red
    // reads as a deep, slightly muted red rather than a neon one, which suits shaded petals.
    private static final int FLOWER_TEAL_DEFAULT = 0x58D2DC;
    private static final int FLOWER_AMARYLLIS_DEFAULT = 0xCCB8FF;
    private static final int FLOWER_LOTUS_DEFAULT = 0x9CE0C8;
    private static final int FLOWER_VEGETA = 0xE63C34;
    // Beerus flowers read BLUE (a cornflower/cerulean), deliberately not the planet's own violet grass, so the blooms
    // stand out clearly against Beerus's purple ground and sky rather than blending into them. Multiplied over the
    // brightened (~150) greyscale petals this lands as a clean medium blue.
    private static final int FLOWER_BEERUS = 0x3C7CFF;

    // (FOLIAGE) tint with everything else. Those specks are now split into their own overlay layer (tint index 1) so the
    // blooms colour independently of the leaf mass. The greyscale blossom pixels are bright (~180), so these MULTIPLY
    // values are kept light (dominant channel near 255) or they would crush to near-black. Namek keeps DMZ's magenta-pink
    // blossom; the two planets override per the brief: Vegeta ajissa red / sacred blue, Beerus ajissa purple / sacred pink.
    private static final int AJISSA_LEAF_FLOWER_DEFAULT = 0xFF7ADC;
    private static final int AJISSA_LEAF_FLOWER_VEGETA = 0xFF6A5E;
    private static final int AJISSA_LEAF_FLOWER_BEERUS = 0xC060FF;
    private static final int SACRED_LEAF_FLOWER_DEFAULT = 0xF078D0;
    private static final int SACRED_LEAF_FLOWER_VEGETA = 0x5A8CFF;
    private static final int SACRED_LEAF_FLOWER_BEERUS = 0xFF8CC8;

    // Biome namespace to tint. Moved to dmz_ragnarok with the biomes in the dimension/biome rename stage, so this
    // gates on the live (migrated) biome id; a pre-migration world still on old shuruisutilities biomes shows the
    // default (untinted) look until it is migrated with world-tools/ns-rename.
    private static final String NS = "dmz_ragnarok";
    private static final String BEERUS = "beerus";

    // The grass tint for the biome at a position. null biome id (unknown level or unresolved biome) falls to DEFAULT.
    public static int grass(ResourceLocation biomeId)
    {
        if (biomeId == null || !NS.equals(biomeId.getNamespace()))
        {
            return GRASS_DEFAULT;
        }
        if (isVegeta(biomeId.getPath()))
        {
            return GRASS_VEGETA;
        }
        if (BEERUS.equals(biomeId.getPath()))
        {
            return GRASS_BEERUS;
        }
        return GRASS_DEFAULT;
    }

    // The foliage (leaves) tint for the biome at a position. Same keying as grass.
    public static int foliage(ResourceLocation biomeId)
    {
        if (biomeId == null || !NS.equals(biomeId.getNamespace()))
        {
            return FOLIAGE_DEFAULT;
        }
        if (isVegeta(biomeId.getPath()))
        {
            return FOLIAGE_VEGETA;
        }
        if (BEERUS.equals(biomeId.getPath()))
        {
            return FOLIAGE_BEERUS;
        }
        return FOLIAGE_DEFAULT;
    }

    // Wood tint (planks, logs, wood, stripped) by biome, same keying as the rest of the surface set. Off the two planets
    // it returns the natural pale ajissa tone so real Namek and overworld placements are unchanged.
    public static int wood(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return WOOD_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return WOOD_BEERUS;
        }
        return WOOD_DEFAULT;
    }

    // namek_dirt tint by biome, same keying as grass.
    public static int dirt(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return DIRT_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return DIRT_BEERUS;
        }
        return DIRT_DEFAULT;
    }

    // rocky_stone / rocky_cobblestone / rocky_dirt tint by biome.
    public static int rock(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return ROCK_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return ROCK_BEERUS;
        }
        return ROCK_DEFAULT;
    }

    // namek_deepslate, its stairs/slab/wall and its ores. A darker sibling of the rock tint, same keying.
    public static int deepslate(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return DEEPSLATE_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return DEEPSLATE_BEERUS;
        }
        return DEEPSLATE_DEFAULT;
    }

    // The teal Namek flowers: catharanthus, chrysanthemum, marigold, trillium. Vegeta and Beerus override to the planet
    // colour; everything else keeps the Namek teal.
    public static int flowerTeal(ResourceLocation biomeId)
    {
        return flower(biomeId, FLOWER_TEAL_DEFAULT);
    }

    // Amaryllis keeps a soft lilac on Namek rather than teal, matching DMZ's amaryllis texture.
    public static int flowerAmaryllis(ResourceLocation biomeId)
    {
        return flower(biomeId, FLOWER_AMARYLLIS_DEFAULT);
    }

    // The lotus reads as a teal-green pad on Namek. It carries a single tint index across pad and bloom, so one colour
    // covers the whole plant; on Vegeta and Beerus the whole lotus takes the planet colour.
    public static int flowerLotus(ResourceLocation biomeId)
    {
        return flower(biomeId, FLOWER_LOTUS_DEFAULT);
    }

    // Shared flower keying: planet override on Vegeta/Beerus, otherwise the passed species default.
    private static int flower(ResourceLocation biomeId, int speciesDefault)
    {
        if (isVegetaBiome(biomeId))
        {
            return FLOWER_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return FLOWER_BEERUS;
        }
        return speciesDefault;
    }

    // The ajissa leaves blossom overlay (tint index 1). Vegeta red, Beerus purple, otherwise the Namek magenta-pink.
    public static int ajissaLeafFlower(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return AJISSA_LEAF_FLOWER_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return AJISSA_LEAF_FLOWER_BEERUS;
        }
        return AJISSA_LEAF_FLOWER_DEFAULT;
    }

    // The sacred leaves blossom overlay (tint index 1). Vegeta blue, Beerus pink, otherwise the Namek magenta-pink.
    public static int sacredLeafFlower(ResourceLocation biomeId)
    {
        if (isVegetaBiome(biomeId))
        {
            return SACRED_LEAF_FLOWER_VEGETA;
        }
        if (isBeerusBiome(biomeId))
        {
            return SACRED_LEAF_FLOWER_BEERUS;
        }
        return SACRED_LEAF_FLOWER_DEFAULT;
    }

    private static boolean isVegetaBiome(ResourceLocation biomeId)
    {
        return biomeId != null && NS.equals(biomeId.getNamespace()) && isVegeta(biomeId.getPath());
    }

    private static boolean isBeerusBiome(ResourceLocation biomeId)
    {
        return biomeId != null && NS.equals(biomeId.getNamespace()) && BEERUS.equals(biomeId.getPath());
    }

    // All five SU-authored Planet Vegeta biomes share the green palette. Matched by path prefix so vegeta, vegeta_arid,
    // vegeta_meadow, vegeta_deep and vegeta_city are all covered without listing each.
    private static boolean isVegeta(String path)
    {
        return path.equals("vegeta") || path.startsWith("vegeta_");
    }
}
