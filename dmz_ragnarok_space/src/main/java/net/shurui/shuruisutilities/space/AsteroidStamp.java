package net.shurui.shuruisutilities.space;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Stamps a single asteroid into real blocks: a rough, irregular stone LUMP with a scattering of ores, at the cell
 * centre derived by {@link AsteroidPositions}. This replaces the old tinted cube ENTITY entirely; asteroids are now
 * mineable block structures a player can tell apart from a planet body at a glance.
 *
 * <p>Modelled on {@link SurfaceStamp}: a one-shot deterministic block stamp, guarded by a persisted "already stamped"
 * flag in {@link AsteroidStampData} so a lump is placed exactly once and never rebuilt over a player's mining. Every
 * block placed is a pure function of the asteroid's per-body seed and the block offset, so the structure is identical
 * every time it is (re)derived and nothing about its shape or material mix is stored.
 *
 * <h3>Shape</h3>
 * Not a cube and not a sphere. For each block in the lump's bounding cube we test a per-DIRECTION wobbled radius: the
 * effective surface radius along a block's direction from the centre is the base radius scaled by a low-frequency
 * hash of that direction, so the surface bulges and dents irregularly. The result is a lumpy potato, varied per body
 * by the seed.
 *
 * <h3>Themes and materials</h3>
 * Each lump belongs to one {@link AsteroidPositions.Theme}, chosen deterministically from its cell hash and weighted so
 * plain STONE dominates and the exotic themes are a find. The theme selects the whole material family:
 * <ul>
 *   <li><b>STONE</b> the vanilla stone mix (stone, deepslate, andesite, diorite, granite, tuff, calcite, basalt,
 *       blackstone) with the overworld ore table. The baseline space rock.</li>
 *   <li><b>NAMEK</b> DragonMineZ's own namek stone and namek deepslate, with DMZ's namek ore variants plus its native
 *       kikono and gete ores as the rich tail. Every DMZ id is resolved by ResourceLocation with a vanilla fallback, so
 *       a missing id degrades to plain rock/ore rather than crashing or placing air.</li>
 *   <li><b>NETHER</b> netherrack, basalt, blackstone and soul soil, with nether quartz, nether gold and a rare ancient
 *       debris tail.</li>
 *   <li><b>END</b> end stone and the purpur family. Vanilla the End has NO ores, so an End asteroid instead yields
 *       obsidian (as a mineable "cooled" material) rather than inventing an end ore that does not exist.</li>
 * </ul>
 * Within a theme, stone types are mixed per body: each lump picks a small palette of that theme's stone variants and
 * fills from it with a per-block hash, so no lump is uniform and different lumps read differently. Ores are then
 * sprinkled in at a small fraction, weighted so common ores are common and valuable ones are rare; where the theme
 * distinguishes a deepslate host the matching deepslate ore variant is used.
 */
public final class AsteroidStamp
{
    private AsteroidStamp()
    {
    }

    // How many distinct stone types a single lump mixes. Small so a lump has a recognisable dominant rock with a couple
    // of veins of others, not a uniform static of all of a theme's variants at once.
    private static final int STONES_PER_BODY = 3;

    // Fraction of a lump's blocks that are ore rather than stone. Kept low on purpose: an asteroid is a nice find, not
    // a mining trivialiser. At 0.06 roughly one block in seventeen is an ore of some kind, and the valuable ones are a
    // small slice of THAT (see each theme's ore table), so a whole lump yields a handful of common ore and only the
    // occasional prize.
    private static final double ORE_FRACTION = 0.06;

    // Amplitude of the surface wobble, 0..1: how far in/out the lumpy surface can deviate from the base radius. 0.35
    // means the surface ranges from ~0.65x to ~1.0x the base radius along different directions, giving clear bulges and
    // dents without ever collapsing the lump to nothing.
    private static final double WOBBLE = 0.35;

    // The vanilla stone palette a STONE lump draws its body from. A per-body hash picks a handful of these, so lumps are
    // mixed but not a uniform noise of all nine at once. Deepslate here drives which ore variant is used per block.
    private static final BlockState[] STONE_PALETTE = new BlockState[] {
            Blocks.STONE.defaultBlockState(),
            Blocks.DEEPSLATE.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.TUFF.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState(),
            Blocks.BASALT.defaultBlockState(),
            Blocks.BLACKSTONE.defaultBlockState(),
    };

    // The Namek palette: DMZ's own namek stone and namek deepslate, resolved by id with a vanilla fallback. namek
    // deepslate drives the deepslate ore variant, exactly like vanilla deepslate does for the STONE theme.
    private static final BlockState[] NAMEK_PALETTE = new BlockState[] {
            dmz("namek_stone", Blocks.STONE),
            dmz("namek_deepslate", Blocks.DEEPSLATE),
            dmz("namek_cobblestone", Blocks.COBBLESTONE),
            dmz("rocky_stone", Blocks.STONE),
    };

    // The Nether palette: the hot, dark rocks of the nether. No deepslate here, so nether ores never take a deepslate
    // variant.
    private static final BlockState[] NETHER_PALETTE = new BlockState[] {
            Blocks.NETHERRACK.defaultBlockState(),
            Blocks.BASALT.defaultBlockState(),
            Blocks.BLACKSTONE.defaultBlockState(),
            Blocks.SOUL_SOIL.defaultBlockState(),
    };

    // The End palette: end stone and the purpur family. No ores exist in the vanilla End, so an End lump yields obsidian
    // instead (see oreForEnd).
    private static final BlockState[] END_PALETTE = new BlockState[] {
            Blocks.END_STONE.defaultBlockState(),
            Blocks.PURPUR_BLOCK.defaultBlockState(),
            Blocks.PURPUR_PILLAR.defaultBlockState(),
    };

    // The DMZ namek deepslate state, cached once, so oreForNamek can tell whether a namek lump's host block is the namek
    // deepslate variant and pick the matching namek deepslate ore. Falls back to vanilla deepslate if the id is missing.
    private static final BlockState NAMEK_DEEPSLATE = dmz("namek_deepslate", Blocks.DEEPSLATE);

    /**
     * Stamp this asteroid's lump into the world once, recording it in {@link AsteroidStampData} so it is never
     * re-stamped. No-op if it was already stamped. Runs on the server thread. The caller (PlanetSpawnModule) has
     * already confirmed every chunk the lump spans is loaded, so every setBlock lands.
     */
    public static void ensureStamped(MinecraftServer server, ServerLevel space, AsteroidPositions.Asteroid asteroid)
    {
        AsteroidStampData data = AsteroidStampData.get(server);
        if (data.isStamped(asteroid.key))
        {
            return;
        }
        stampLump(space, asteroid);
        data.markStamped(asteroid.key);
    }

    // stamp the irregular stone-and-ore lump centred on the asteroid position. Walks the bounding cube of the body's
    // radius and, for each block, keeps it only if it is inside the per-direction wobbled surface, then picks a stone
    // (or, at the ore fraction, an ore) for it from the body's THEME. All hashes derive from the body seed so the lump
    // is identical every time it is stamped.
    private static void stampLump(ServerLevel space, AsteroidPositions.Asteroid asteroid)
    {
        long seed = asteroid.seed;
        AsteroidPositions.Theme theme = asteroid.theme;
        int cx = (int) Math.floor(asteroid.position.x);
        int cy = (int) Math.floor(asteroid.position.y);
        int cz = (int) Math.floor(asteroid.position.z);
        int r = (int) Math.ceil(asteroid.radius);
        double baseSq = (double) asteroid.radius * asteroid.radius;

        // the small per-body stone palette: STONES_PER_BODY distinct entries picked from the theme's palette by the seed.
        BlockState[] stones = pickStones(seed, paletteFor(theme));

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; ++dx)
        {
            for (int dy = -r; dy <= r; ++dy)
            {
                for (int dz = -r; dz <= r; ++dz)
                {
                    if (insideLump(seed, asteroid.radius, baseSq, dx, dy, dz))
                    {
                        placeBlock(space, pos, cx + dx, cy + dy, cz + dz, seed, dx, dy, dz, stones, theme);
                    }
                }
            }
        }
    }

    /**
     * Remove a wreck lump: set every block the stamp would have placed back to air. Used ONLY by the debris timer when a
     * destroyed cell bumps to its next generation, so the wreck vanishes with the planet it belonged to. Reuses the
     * EXACT shape test the stamp uses ({@link #insideLump}), so it clears precisely the volume that was stamped and can
     * never drift from it. Any player-mined block in that volume is already air (a no-op clear), and any block a player
     * built inside the wreck is removed with it, which is correct: the wreck is coming apart. Runs on the server thread;
     * the caller only invokes this for a lump that was actually stamped, so the chunks it touches are worth loading.
     */
    public static void clearLump(ServerLevel space, AsteroidPositions.Asteroid asteroid)
    {
        long seed = asteroid.seed;
        int cx = (int) Math.floor(asteroid.position.x);
        int cy = (int) Math.floor(asteroid.position.y);
        int cz = (int) Math.floor(asteroid.position.z);
        int r = (int) Math.ceil(asteroid.radius);
        double baseSq = (double) asteroid.radius * asteroid.radius;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int dx = -r; dx <= r; ++dx)
        {
            for (int dy = -r; dy <= r; ++dy)
            {
                for (int dz = -r; dz <= r; ++dz)
                {
                    if (insideLump(seed, asteroid.radius, baseSq, dx, dy, dz))
                    {
                        pos.set(cx + dx, cy + dy, cz + dz);
                        // flag 2, matching the stamp: push the change to clients without neighbour ticks.
                        space.setBlock(pos, air, 2);
                    }
                }
            }
        }
    }

    // whether a block offset from the lump centre is inside the lump's wobbled surface. The SINGLE shape test both the
    // stamp and the wreck clear read, so the volume placed and the volume removed are always exactly the same set. The
    // very core (distSq < 1) is always solid so a small lump is never hollowed to nothing by the wobble; beyond it a
    // per-direction low-frequency hash scales the base radius in and out, and the block is kept only inside that wobbled
    // surface (and never past the outer wobble bound).
    private static boolean insideLump(long seed, float radius, double baseSq, int dx, int dy, int dz)
    {
        double distSq = (double) dx * dx + (double) dy * dy + (double) dz * dz;
        if (distSq < 1.0)
        {
            return true;
        }
        double wob = directionWobble(seed, dx, dy, dz);
        double surf = radius * wob;
        return distSq <= surf * surf && distSq <= baseSq * (1.0 + WOBBLE) * (1.0 + WOBBLE);
    }

    // place one block of the lump: an ore at the ore fraction, otherwise one of the body's stones. The stone/ore choice
    // is a pure per-block hash of the seed and the block offset, so it is stable across re-stamps. The ore drawn comes
    // from the body's THEME.
    private static void placeBlock(ServerLevel space, BlockPos.MutableBlockPos pos, int wx, int wy, int wz,
                                   long seed, int dx, int dy, int dz, BlockState[] stones, AsteroidPositions.Theme theme)
    {
        pos.set(wx, wy, wz);
        long bh = blockHash(seed, dx, dy, dz);
        // pick the host stone first so an ore can take the matching deepslate variant where the host is a deepslate rock.
        BlockState stone = stones[(int) ((bh >>> 8) % stones.length)];
        boolean deepslateHost = stone.is(Blocks.DEEPSLATE) || (NAMEK_DEEPSLATE.getBlock() != Blocks.DEEPSLATE && stone.is(NAMEK_DEEPSLATE.getBlock()));

        double oreRoll = unit(bh, 24);
        BlockState placed;
        if (oreRoll < ORE_FRACTION)
        {
            placed = oreFor(theme, unit(bh, 40), deepslateHost);
        }
        else
        {
            placed = stone;
        }
        // flag 2: send the change to clients without triggering block updates/neighbour ticks (nothing to update in a
        // solid lump, and it is far cheaper). Matches SurfaceStamp.
        space.setBlock(pos, placed, 2);
    }

    // the stone palette for a theme.
    private static BlockState[] paletteFor(AsteroidPositions.Theme theme)
    {
        switch (theme)
        {
            case NAMEK:
                return NAMEK_PALETTE;
            case NETHER:
                return NETHER_PALETTE;
            case END:
                return END_PALETTE;
            case STONE:
            default:
                return STONE_PALETTE;
        }
    }

    // pick STONES_PER_BODY distinct stone states from the given palette for this body, using independent hash windows so
    // the palette varies per lump. Distinctness is enforced by skipping a repeat pick.
    private static BlockState[] pickStones(long seed, BlockState[] palette)
    {
        int want = Math.min(STONES_PER_BODY, palette.length);
        BlockState[] out = new BlockState[want];
        int count = 0;
        int attempt = 0;
        while (count < want && attempt < 32)
        {
            long h = blockHash(seed, attempt + 1, 0, 0);
            BlockState candidate = palette[(int) ((h >>> 16) % palette.length)];
            boolean dup = false;
            for (int i = 0; i < count; ++i)
            {
                if (out[i] == candidate)
                {
                    dup = true;
                    break;
                }
            }
            if (!dup)
            {
                out[count++] = candidate;
            }
            attempt++;
        }
        // extremely defensive: if the loop somehow could not fill (it always can), pad with the palette's first entry.
        for (int i = 0; i < want; ++i)
        {
            if (out[i] == null)
            {
                out[i] = palette[0];
            }
        }
        return out;
    }

    // the ore to place for a given 0..1 roll, dispatched by theme. Each theme's table is documented on its method.
    private static BlockState oreFor(AsteroidPositions.Theme theme, double roll, boolean deepslateHost)
    {
        switch (theme)
        {
            case NAMEK:
                return oreForNamek(roll, deepslateHost);
            case NETHER:
                return oreForNether(roll);
            case END:
                return oreForEnd(roll);
            case STONE:
            default:
                return oreForStone(roll, deepslateHost);
        }
    }

    // STONE theme ore table, weighted so common ores are common and valuable ones are rare. The bands are cumulative
    // fractions of the ORE blocks (not of the whole lump), so with ORE_FRACTION 0.06 a diamond is ~0.06 * 0.03 = under
    // two blocks per thousand. Where the host block is deepslate the matching deepslate variant is used.
    //
    // Weighting (share of ore blocks):
    //   coal 28%  copper 22%  iron 20%  redstone 12%  lapis 8%  gold 5%  emerald 2%  diamond ~2.5%
    // then a very rare "rich" tail carved out of the top: nether quartz ~0.4%, ancient debris ~0.1%.
    private static BlockState oreForStone(double roll, boolean deepslateHost)
    {
        if (roll < 0.28)
        {
            return deepslateHost ? Blocks.DEEPSLATE_COAL_ORE.defaultBlockState() : Blocks.COAL_ORE.defaultBlockState();
        }
        if (roll < 0.50)
        {
            return deepslateHost ? Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState() : Blocks.COPPER_ORE.defaultBlockState();
        }
        if (roll < 0.70)
        {
            return deepslateHost ? Blocks.DEEPSLATE_IRON_ORE.defaultBlockState() : Blocks.IRON_ORE.defaultBlockState();
        }
        if (roll < 0.82)
        {
            return deepslateHost ? Blocks.DEEPSLATE_REDSTONE_ORE.defaultBlockState() : Blocks.REDSTONE_ORE.defaultBlockState();
        }
        if (roll < 0.90)
        {
            return deepslateHost ? Blocks.DEEPSLATE_LAPIS_ORE.defaultBlockState() : Blocks.LAPIS_ORE.defaultBlockState();
        }
        if (roll < 0.95)
        {
            return deepslateHost ? Blocks.DEEPSLATE_GOLD_ORE.defaultBlockState() : Blocks.GOLD_ORE.defaultBlockState();
        }
        if (roll < 0.97)
        {
            return deepslateHost ? Blocks.DEEPSLATE_EMERALD_ORE.defaultBlockState() : Blocks.EMERALD_ORE.defaultBlockState();
        }
        // the top ~2.5% band is diamond, with a very rare tail carved out of its end so quartz, ancient debris and the
        // katchin materials are genuinely uncommon (roughly the shares noted below of ore blocks).
        if (roll < 0.995)
        {
            return deepslateHost ? Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState() : Blocks.DIAMOND_ORE.defaultBlockState();
        }
        if (roll < 0.9975)
        {
            // nether quartz embedded in the rock: a rare, valuable oddity for a space asteroid. ~0.25%.
            return Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
        }
        if (roll < 0.9988)
        {
            // ancient debris: the netherite tail of a plain-stone asteroid. ~0.13%.
            return Blocks.ANCIENT_DEBRIS.defaultBlockState();
        }
        if (roll < 0.9996)
        {
            // katchin: the dark end-game material, deliberately rarer than DMZ gete (see oreForNamek). ~0.08%.
            return katchinOre(deepslateHost);
        }
        // katchi katchin: the rarest possible STONE-asteroid find, rarer than katchin, with the roll's position inside
        // this last sliver deciding which of the three interchangeable colours you get. ~0.04% total.
        return katchiKatchinOre((roll - 0.9996) / (1.0 - 0.9996), deepslateHost);
    }

    // NAMEK theme ore table: DragonMineZ's own namek ore variants, mirroring the STONE table's shape but using DMZ ids
    // (resolved with a vanilla fallback), then a DMZ-native rich tail of kikono ore and gete debris ore. namek deepslate
    // hosts take the matching namek deepslate ore variant.
    //
    // Weighting (share of ore blocks):
    //   coal 28%  copper 22%  iron 20%  redstone 12%  lapis 8%  gold 5%  emerald 2%  diamond ~1%
    // then the DMZ-native rich tail: namek kikono ore ~1.5%, gete debris ore ~0.5%.
    private static BlockState oreForNamek(double roll, boolean deepslateHost)
    {
        if (roll < 0.28)
        {
            return deepslateHost ? dmz("namek_deepslate_coal_ore", Blocks.DEEPSLATE_COAL_ORE) : dmz("namek_coal_ore", Blocks.COAL_ORE);
        }
        if (roll < 0.50)
        {
            return deepslateHost ? dmz("namek_deepslate_copper_ore", Blocks.DEEPSLATE_COPPER_ORE) : dmz("namek_copper_ore", Blocks.COPPER_ORE);
        }
        if (roll < 0.70)
        {
            return deepslateHost ? dmz("namek_deepslate_iron_ore", Blocks.DEEPSLATE_IRON_ORE) : dmz("namek_iron_ore", Blocks.IRON_ORE);
        }
        if (roll < 0.82)
        {
            return deepslateHost ? dmz("namek_deepslate_redstone_ore", Blocks.DEEPSLATE_REDSTONE_ORE) : dmz("namek_redstone_ore", Blocks.REDSTONE_ORE);
        }
        if (roll < 0.90)
        {
            return deepslateHost ? dmz("namek_deepslate_lapis_ore", Blocks.DEEPSLATE_LAPIS_ORE) : dmz("namek_lapis_ore", Blocks.LAPIS_ORE);
        }
        if (roll < 0.95)
        {
            return deepslateHost ? dmz("namek_deepslate_gold_ore", Blocks.DEEPSLATE_GOLD_ORE) : dmz("namek_gold_ore", Blocks.GOLD_ORE);
        }
        if (roll < 0.97)
        {
            return deepslateHost ? dmz("namek_deepslate_emerald_ore", Blocks.DEEPSLATE_EMERALD_ORE) : dmz("namek_emerald_ore", Blocks.EMERALD_ORE);
        }
        if (roll < 0.98)
        {
            return deepslateHost ? dmz("namek_deepslate_diamond_ore", Blocks.DEEPSLATE_DIAMOND_ORE) : dmz("namek_diamond_ore", Blocks.DIAMOND_ORE);
        }
        // the DMZ-native rich tail: kikono ore (drops kikono shards) is the common prize, gete debris ore (smelts to
        // gete ingots) the rare one. Both are genuine DMZ Namek materials, so a Namek asteroid yields something you
        // cannot get from a plain-stone one.
        if (roll < 0.995)
        {
            return dmz("namek_kikono_ore", Blocks.EMERALD_ORE);
        }
        if (roll < 0.9985)
        {
            // gete debris ore: DMZ's own rich tail. Trimmed a touch (from ~0.5% to ~0.35%) to seat the katchin
            // materials above it, so on a Namek asteroid katchin is genuinely rarer than gete.
            return dmz("gete_debris_ore", Blocks.ANCIENT_DEBRIS);
        }
        if (roll < 0.9994)
        {
            // katchin: rarer than the gete tail just above. ~0.09%.
            return katchinOre(deepslateHost);
        }
        // katchi katchin: rarer still than katchin, colour chosen by position within this last sliver. ~0.06% total.
        return katchiKatchinOre((roll - 0.9994) / (1.0 - 0.9994), deepslateHost);
    }

    // NETHER theme ore table: the nether's own ores. No deepslate variants exist for these and the palette has no
    // deepslate, so there is no deepslate branch.
    //
    // Weighting (share of ore blocks):
    //   nether quartz 60%  nether gold 30%  ancient debris 10%
    private static BlockState oreForNether(double roll)
    {
        if (roll < 0.60)
        {
            return Blocks.NETHER_QUARTZ_ORE.defaultBlockState();
        }
        if (roll < 0.90)
        {
            return Blocks.NETHER_GOLD_ORE.defaultBlockState();
        }
        // ancient debris as the rare tail: a nether asteroid is the reliable-but-slow way to netherite.
        return Blocks.ANCIENT_DEBRIS.defaultBlockState();
    }

    // END theme "ore" table. The vanilla End has NO ores at all, so rather than invent one an End lump yields OBSIDIAN
    // as its mineable prize: a defensible End-adjacent material (End islands are ringed by obsidian pillars) that is
    // genuinely useful and honest about what the End contains. The bulk of an End lump is therefore its purpur/end-stone
    // body with the occasional obsidian nodule.
    private static BlockState oreForEnd(double roll)
    {
        // a small slice of the "ore" roll is crying obsidian for a bit of variety and value; the rest is plain obsidian.
        if (roll < 0.15)
        {
            return Blocks.CRYING_OBSIDIAN.defaultBlockState();
        }
        return Blocks.OBSIDIAN.defaultBlockState();
    }

    // resolve a DragonMineZ block by its registry path, returning its default state, or the given vanilla fallback's
    // default state if the id is not registered. dragonminez is a mandatory dependency so the ids normally exist, but a
    // lookup-with-fallback means a wrong or removed id degrades to sensible rock/ore rather than a crash or a silent air
    // block. Every id passed here was verified present in the 2.1.3 jar (blockstate asset + MainBlocks registration).
    private static BlockState dmz(String path, Block fallback)
    {
        Block b = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("dragonminez", path));
        if (b == null || b == Blocks.AIR)
        {
            return fallback.defaultBlockState();
        }
        return b.defaultBlockState();
    }

    // SU's own katchin (dark) ore, deepslate or stone hosted. These are the only worldgen entry point for katchin:
    // only NEW asteroids gain them, and only via these code tables (the space biome has no JSON ore features and
    // planet surfaces use the separate SurfaceStamp path). Falls back to ancient debris if the id is somehow absent.
    private static BlockState katchinOre(boolean deepslateHost)
    {
        net.minecraftforge.registries.RegistryObject<Block> ore = deepslateHost
                ? net.shurui.shuruisutilities.katchin.KatchinBlocks.DEEPSLATE_KATCHIN_ORE
                : net.shurui.shuruisutilities.katchin.KatchinBlocks.KATCHIN_ORE;
        Block b = ore.get();
        return b == null ? Blocks.ANCIENT_DEBRIS.defaultBlockState() : b.defaultBlockState();
    }

    // SU's own katchi katchin (light) ore. All three colours are interchangeable, so the colour is chosen from the
    // 0..1 position inside the katchi katchin rarity band, giving all three roughly equal presence in the world.
    private static BlockState katchiKatchinOre(double bandPos, boolean deepslateHost)
    {
        int colour = bandPos < (1.0 / 3.0) ? 0 : (bandPos < (2.0 / 3.0) ? 1 : 2);
        net.minecraftforge.registries.RegistryObject<Block> ore = deepslateHost
                ? net.shurui.shuruisutilities.katchin.KatchinBlocks.DEEPSLATE_KATCHI_KATCHIN_ORE[colour]
                : net.shurui.shuruisutilities.katchin.KatchinBlocks.KATCHI_KATCHIN_ORE[colour];
        Block b = ore.get();
        return b == null ? Blocks.ANCIENT_DEBRIS.defaultBlockState() : b.defaultBlockState();
    }

    // low-frequency 0.65..1.0-ish scale of the base radius for a block's direction from the centre, so the surface
    // wobbles in and out. We quantise the direction to a coarse grid before hashing so nearby blocks share a wobble
    // value (a smooth bulge), rather than every block getting independent noise (which would just roughen the surface
    // uniformly). Returns 1.0 - WOBBLE .. 1.0.
    private static double directionWobble(long seed, int dx, int dy, int dz)
    {
        // quantise direction to a coarse grid so the wobble varies over several blocks, not per block.
        int qx = Math.floorDiv(dx, 3);
        int qy = Math.floorDiv(dy, 3);
        int qz = Math.floorDiv(dz, 3);
        long h = blockHash(seed, qx, qy, qz);
        double u = unit(h, 0);
        return 1.0 - WOBBLE * u;
    }

    // splitmix64-style hash of the body seed and a block offset. Deterministic and JVM-stable.
    private static long blockHash(long seed, int dx, int dy, int dz)
    {
        long z = seed
                ^ (dx * 0x9E3779B97F4A7C15L)
                ^ (dy * 0xC2B2AE3D27D4EB4FL)
                ^ (dz * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // 0..1 double from a 64-bit hash's given byte-window.
    private static double unit(long h, int shift)
    {
        return ((h >>> shift) & 0xFFFFFFL) / 16777216.0;
    }
}
