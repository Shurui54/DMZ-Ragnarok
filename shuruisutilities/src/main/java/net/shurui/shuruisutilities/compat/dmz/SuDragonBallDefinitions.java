package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntSupplier;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.dragonball.DragonAssetDefinition;
import com.dragonminez.common.dragonball.DragonBallDefinitions;
import com.dragonminez.common.dragonball.DragonBallSetAssetDefinition;
import com.dragonminez.common.dragonball.DragonBallSetDefinition;
import com.dragonminez.common.dragonball.DragonDefinition;
import com.dragonminez.common.dragonball.DragonRadarAssetDefinition;
import com.dragonminez.common.dragonball.DragonRadarDefinition;
import com.dragonminez.common.wish.DragonWishRegistry;
import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.WishManager;
import com.dragonminez.common.wish.wishes.ItemWish;
import com.dragonminez.common.wish.wishes.TPSWish;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.world.space.SpaceKeys;
import net.minecraft.server.MinecraftServer;

import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.shuruisutilities.core.SUConfig;

/**
 * Shurui's three dragon ball SETS (Black Star, Super, Cerulean), hardcoded in Shurui's Utilities instead of
 * shipped as an external filesystem pack.
 *
 * <p>WHY hardcode rather than ship a pack. DragonMineZ can read ball set definitions from a {@code dragonballs/}
 * folder placed at the game instance ROOT (next to {@code mods/}). That worked, but it is an extra manual install
 * step, it cannot ride along inside the mod jar (DMZ scans the disk folder, not jar datapacks), and the content is
 * really ours, so it belongs in our code. Every value below is the exact value the old pack JSON carried (the pack
 * has been deleted), except the scatter range, which the pack hardcoded to 3000 and we now read from SU config
 * (default 3000) so the overworld map bound is tunable.
 *
 * <p>WHERE this plugs in. {@code DragonBallDefinitions} runs a static initialiser that (1) registers DMZ's own
 * earth and namek definitions, (2) calls {@code loadExternalBootstrapDefinitions()} to fold in the disk pack, then
 * (3) resets the runtime maps from the bootstrap maps. Every {@code registerBootstrap*} method is public and stores
 * into a map KEYED BY ID, so re-registering an id REPLACES it. A mixin at the TAIL of
 * {@code loadExternalBootstrapDefinitions()} therefore lands at exactly the right moment: after DMZ's own defs
 * exist, after the (now empty) disk pack was folded in, and before anything reads the maps. That mixin calls
 * {@link #registerBootstrapDefinitions()}. Because our registration runs LAST, reusing any DMZ id wins. We add our
 * three new ids AND deliberately re-register DMZ's own {@code earth} ball set to clamp its overworld scatter range
 * to the imported map (see {@code registerEarthOverride}); every other earth field is mirrored verbatim, and DMZ's
 * {@code namek} set is left entirely alone.
 *
 * <p>WISHES are a separate path. DMZ has no bootstrap registration for wishes; {@code DragonWishRegistry} is a
 * reload listener whose {@code apply()} rebuilds the server wish map from the disk pack on every datapack reload,
 * giving every dragon an EMPTY wish list when the pack is gone. So a one-shot set would not survive a reload. We
 * therefore SEED our wishes via {@link #reapplyWishes()} from a HIGH-priority {@link SuDragonWishBridge} handler on
 * Forge's {@code OnDatapackSyncEvent}, which fires just after every reload / login and, at HIGH priority, just
 * BEFORE DMZ's own default-priority handler on the same event syncs the wishes to clients. We SEED IF ABSENT, never
 * overwrite: a wish list an operator saved through the editor (persisted by DMZ to
 * {@code <world>/dragonminez/wishes/<dragon>.json}) must win over our hardcoded defaults, so we only install those
 * defaults where no wish data exists yet. Because a vanilla {@code /reload} empties our ids without re-reading
 * those files, {@code reapplyWishes()} first asks DMZ to re-overlay them ({@code WishManager.loadWishes}) whenever
 * one of our ids is empty, restoring a saved edit before it seeds. See {@link #reapplyWishes()} for the full
 * contract. The public seam is {@code DragonWishRegistry.getServerWishes()} / {@code setServerWishes(Map)} plus
 * {@code WishManager.loadWishes(server)} and the public {@code TPSWish} and {@code ItemWish} constructors.
 *
 * <p>{@link #registerBootstrapDefinitions()} does not catch its own throwables: it runs from a mixin into a DMZ
 * static initialiser, and that mixin owns the try/catch(Throwable) so a DMZ API shift degrades to "our sets are
 * absent" rather than taking DMZ's class init (and the whole game) down. {@link #reapplyWishes()} likewise leaves
 * the guard to {@link SuDragonWishBridge}. Keeping the guards in the callers means this class stays a plain,
 * readable data table.
 */
public final class SuDragonBallDefinitions
{
    private SuDragonBallDefinitions()
    {
    }

    // How many complete copies of a set DMZ auto-scatters into the world at once. All three of our sets use 0: none are
    // DMZ-scattered. Black Star and Super balls are placed by our own code (ApophisSummonModule and SuperPlanetGod
    // respectively), and Cerulean is summon/craft only.
    private static final int COPIES_NONE = 0;

    // Blocks within this radius of the summon point are cleared when the dragon spawns. DMZ's own sets use 5.
    private static final int SUMMON_RADIUS = 5;

    // The Super set needs a wider summon radius than DMZ's default 5. getSummonRadius() drives the Chebyshev box that
    // areAllDragonBallsNearby scans to confirm all 7 balls are present (and the matching removeAllDragonBalls sweep at
    // consumption), so one value keeps detection and consumption consistent. The 4x ball models are ~2.9 blocks across
    // and placed freehand, so 7 of them cannot all fit inside a radius-5 box and the summon never triggers.
    private static final int SUPER_SUMMON_RADIUS = 16;

    // The radar's three lock-on distance rings, in blocks, shared by all three of our radars.
    private static final int[] RADAR_RANGES = new int[] { 150, 300, 600 };

    // A senzu bean wish always hands over sixteen, matching the pack.
    private static final int SENZU_WISH_COUNT = 16;
    private static final String SENZU_ITEM_ID = "dragonminez:senzu_bean";

    // Every one of our dragons may be summoned in this same wide dimension list. The overworld plus DMZ's own
    // dimensions plus our two space dimensions, so a gathered set summons its dragon wherever a player fights.
    /**
     * The dimension ids these worlds carried BEFORE the rename to the {@code dmz_ragnarok} namespace.
     *
     * <p>Listed ALONGSIDE the live ids everywhere below, never instead of them. A world created before the rename
     * still names the old id in its level data, and {@code DragonRadarDefinition.supportsDimension} is an exact set
     * membership test on that id, so naming only one namespace silently disables the radar for every save on the
     * other. That is precisely what had happened: the code had moved to {@code dmz_ragnarok:space} while these sets
     * still said {@code shuruisutilities:space}, so DragonMineZ decided the radar was not usable here, never called
     * its own {@code renderRadar}, and the HUD our inject draws from never got the chance to run. The radar was not
     * drawing the wrong thing, it was never asked to draw at all.
     */
    private static final ResourceLocation LEGACY_SPACE = new ResourceLocation("shuruisutilities", "space");
    private static final ResourceLocation LEGACY_PLANET_SURFACE =
            new ResourceLocation("shuruisutilities", "planet_surface");

    private static final Set<ResourceLocation> DRAGON_DIMENSIONS = Set.of(
            new ResourceLocation("minecraft", "overworld"),
            new ResourceLocation("minecraft", "the_nether"),
            new ResourceLocation("minecraft", "the_end"),
            new ResourceLocation("dragonminez", "namek"),
            new ResourceLocation("dragonminez", "otherworld"),
            new ResourceLocation("dragonminez", "sacredkaiplanet"),
            new ResourceLocation("dragonminez", "time_chamber"),
            SpaceKeys.SPACE_ID,
            SpaceKeys.SURFACE_ID,
            LEGACY_SPACE,
            LEGACY_PLANET_SURFACE);

    // Ball sets themselves only ever SCATTER in the overworld, so their dimension set is just the overworld. This
    // is separate from the dragon dimensions above on purpose: widening a SET's dimensions would scatter its balls
    // everywhere, which we do not want.
    private static final Set<ResourceLocation> OVERWORLD_ONLY = Set.of(new ResourceLocation("minecraft", "overworld"));

    // Cerulean's INTENDED future home, Planet Cereal, which does not exist yet. A ball set is confined to this instead
    // of the overworld precisely so it does NOT scatter today: DMZ resolves a set's first-spawn level as
    // server.getLevel(firstValidDimension), and with no such dimension registered that resolves to null and DMZ skips
    // the set entirely (ForgeCommonEvents.onServerStarting: "if (targetLevel == null ... ) continue"). The same absent
    // level makes scatterDragonBalls and the chunk-load/rescan generators no-ops for cerulean, because
    // supportsDimension and getBallSetsForDimension both key off this list. When Planet Cereal ships and registers this
    // id, cerulean begins scattering there with no further code change (and rejoins the cross-shard authority by the
    // same ShardDimensions.hosts rule as every other set). See registerCerulean for why COPIES_NONE alone cannot do
    // this. The id lives under our own namespace so the future dimension can claim it directly.
    private static final ResourceLocation PLANET_CEREAL = new ResourceLocation("dmz_ragnarok", "planet_cereal");
    private static final Set<ResourceLocation> CEREAL_ONLY = Set.of(PLANET_CEREAL);

    // Black Star scatters and is radar-gathered ONLY in the shared generated-planet surface dimension, never the
    // overworld: its seven balls are seeded onto seven random generated planets by ApophisSummonModule (using DMZ's
    // native pending model), and the red dragon may be summoned only there. Two consequences flow from this being the
    // SET's dimension list: (1) buildRadarPacket gathers Black Star ball positions from planet_surface, so the radar
    // sees them; (2) DMZ's own first-spawn/re-scatter targets planet_surface, which is why the set's copies are 0
    // (COPIES_NONE) so DMZ never scatters near that dimension's origin, leaving placement entirely to our module.
    // Super shares this set dimension: its seven balls are dropped by a Destroyer God on the Super Dragon Ball planets
    // (SuperPlanetGod places the matching block at the planet surface centre on death), so planet_surface is what lets
    // buildRadarPacket gather those god-dropped balls, and with copies 0 it also keeps DMZ from scattering a second set.
    private static final Set<ResourceLocation> PLANET_SURFACE_ONLY = Set.of(
            SpaceKeys.SURFACE_ID,
            LEGACY_PLANET_SURFACE);

    // The radars that reach beyond the overworld. Both the Black Star and Super radars reach space (the travel
    // dimension players cross) and the planet surface where their balls sit, so each radar item stays usable wherever a
    // ball can actually be. Black Star needs space in particular so its HUD can paint while a player flies between the
    // seven scattered planets: our planet-aware HUD (SuRadarHud) only runs once DMZ resolves the radar for the current
    // dimension, which requires that dimension to be in this list.
    //
    // The overworld and the rest USED to be left out on purpose, with the reasoning "the radar must not ping on Earth
    // where none of these balls exist". That premise no longer holds. A player who dies or logs out holding balls
    // leaves them in a grave totem (DragonBallTotem), and they can die anywhere, so a Super ball really can sit in the
    // overworld now. This list gates DRAWING only (DragonRadarDefinition.supportsDimension, client side); it does NOT
    // affect where balls scatter, which is the SET's list and stays planet-surface only. A radar that refuses to draw
    // in the dimension the player is standing in is simply broken from the player's point of view, so these now cover
    // everywhere a player can be.
    private static final Set<ResourceLocation> SPACE_AND_PLANET_SURFACE = Set.of(
            new ResourceLocation("minecraft", "overworld"),
            new ResourceLocation("minecraft", "the_nether"),
            new ResourceLocation("minecraft", "the_end"),
            new ResourceLocation("dragonminez", "namek"),
            new ResourceLocation("dragonminez", "otherworld"),
            new ResourceLocation("dragonminez", "sacredkaiplanet"),
            new ResourceLocation("dragonminez", "time_chamber"),
            SpaceKeys.SPACE_ID,
            SpaceKeys.SURFACE_ID,
            LEGACY_SPACE,
            LEGACY_PLANET_SURFACE);

    /**
     * Register all three of our sets into DMZ's bootstrap maps. Called from the tail of
     * {@code DragonBallDefinitions.loadExternalBootstrapDefinitions()}. Order within a set mirrors DMZ's own static
     * block: asset defs first (they are referenced by id from the ball set / radar / dragon defs that follow).
     * May throw; the calling mixin catches.
     */
    public static void registerBootstrapDefinitions()
    {
        // Held back for this release (see ReleaseToggles). Only the three CUSTOM sets are skipped; the earth
        // override below still runs, because it clamps DMZ's OWN set and switching it off would be a change to
        // stock behaviour rather than the removal of ours.
        if (net.shurui.shuruisutilities.core.ReleaseToggles.CUSTOM_DRAGON_BALL_SETS)
        {
            registerBlackStar();
            registerSuper();
            registerCerulean();
        }
        // Earth LAST so that if this single re-registration ever throws (a DMZ API shift in the constructor), the
        // three sets above are already in the bootstrap map and survive; only our earth clamp is lost and DMZ keeps
        // its own untouched earth set. Re-registering by the "earth" id REPLACES DMZ's own earth ball set.
        registerEarthOverride();
    }

    /**
     * Re-register DMZ's own {@code earth} ball SET with every field mirrored EXACTLY from DMZ's static block, the one
     * and only exception being the spawn range, which we clamp to the overworld map bound so Earth balls stay inside
     * the imported custom map. We touch ONLY the ball set: earth's asset, radar, dragon and recipe definitions are
     * left as DMZ registered them.
     *
     * <p>WHY this is safe against DMZ's block wiring. DMZ attaches the actual {@code Block} objects to whichever
     * earth {@code DragonBallSetDefinition} sits in the bootstrap map at the time {@code MainBlocks} iterates
     * {@code getBootstrapBallSets()}, and that iteration happens AFTER this static-init replacement, so DMZ wires its
     * blocks onto OUR earth object using the same star -> registry-name map (dball1..dball7). The datapack-reload
     * path then copies those same registered blocks back off the bootstrap object. Nothing here changes the block
     * names, so the wiring is identical to DMZ's.
     *
     * <p>The two suppliers are pulled by DMZ once PER SCATTER, not now, so they read live config at scatter time and
     * never touch {@code ConfigManager} during this static initialiser.
     */
    private static void registerEarthOverride()
    {
        DragonBallDefinitions.registerBootstrapBallSet(new DragonBallSetDefinition(
                "earth",
                OVERWORLD_ONLY,
                // copies: mirrored VERBATIM from DMZ (Math.max(1, worldGen.getDragonBallSets())). We are not
                // changing how many earth sets exist, only where they land.
                () -> Math.max(1, ConfigManager.getServerConfig().getWorldGen().getDragonBallSets()),
                // spawn range: the ONE field we change. Clamped so it can never exceed the map bound (see helper).
                SuDragonBallDefinitions::boundedEarthRange,
                SUMMON_RADIUS,
                earthStarBlocks(),
                "earth_ballset",
                "Earth Dragon Ball"));
    }

    private static void registerBlackStar()
    {
        // Black Star reuses DMZ's own ball geo/animation and only reskins it; its block and item textures live in
        // our assets. Its dragon is Apophis, a reskin of DMZ's shenron model.
        DragonBallDefinitions.registerBootstrapBallSetAsset(new DragonBallSetAssetDefinition(
                "blackstar_ballset",
                "dmz_ragnarok:block/custom/dballblock_blackstar",
                "dmz_ragnarok:item/dball_blackstar%d",
                "dragonminez:geo/block/dball.geo.json",
                "dragonminez:animations/block/dball.animation.json",
                "dmz_ragnarok:textures/block/custom/dballblock_blackstar",
                null,
                null));
        DragonBallDefinitions.registerBootstrapRadarAsset(new DragonRadarAssetDefinition(
                "blackstar_radar",
                "dmz_ragnarok:item/dball_radar",
                "dmz_ragnarok:item/dball_radar",
                "dragonminez:textures/gui/radar.png",
                // no dot texture: our HUD (SuRadarHud, via MixinDmzRadarDraw) draws blips procedurally, and DMZ's own
                // radar HUD sources its dot sprite from radar.png, so the old dragonminez:textures/gui/radar_dot.png
                // reference pointed at a file the DMZ jar does not ship. Dropped to null (blankToNull keeps it absent).
                null,
                null));
        DragonBallDefinitions.registerBootstrapDragonAsset(new DragonAssetDefinition(
                "apophis",
                "default",
                "dragonminez:geo/entity/dragon/shenron.geo.json",
                "dmz_ragnarok:textures/entity/dragon/shenron_apophis.png",
                "dragonminez:animations/entity/dragon/shenron.animation.json"));
        DragonBallDefinitions.registerBootstrapBallSet(new DragonBallSetDefinition(
                "blackstar",
                PLANET_SURFACE_ONLY,
                // copies 0: our ApophisSummonModule owns Black Star placement (seven balls seeded across seven random
                // generated planets via DMZ's pending model), so DMZ must never auto-scatter this set. A non-zero
                // count would have DMZ's first-spawn/re-scatter cluster the balls at the planet_surface origin instead.
                fixed(COPIES_NONE),
                SuDragonBallDefinitions::boundedOverworldRange,
                SUMMON_RADIUS,
                sevenStarBlocks("blackstar"),
                "blackstar_ballset",
                "Black Star Dragon Ball"));
        DragonBallDefinitions.registerBootstrapRadar(new DragonRadarDefinition(
                "blackstar_radar",
                "blackstar_dball_radar",
                SPACE_AND_PLANET_SURFACE,
                "blackstar",
                "item.dragonminez.blackstar_dball_radar.tooltip",
                RADAR_RANGES,
                null,
                "blackstar_radar",
                "Black Star Radar"));
        DragonBallDefinitions.registerBootstrapDragon(new DragonDefinition(
                "apophis",
                "apophis",
                3.0f,
                17.0f,
                DRAGON_DIMENSIONS,
                "blackstar",
                // wishScreenId. NOT screen art: GrantWishC2S does WishManager.getAllWishes().get(this),
                // so it is the KEY OF THE WISH LIST this dragon grants from. It said "shenron", so every
                // custom set showed Earth Shenron's stock wishes and granted from them, while the list
                // seeded for this dragon was never displayed to anybody.
                "apophis",
                1,
                "apophis"));
    }

    /**
     * Our own 48-bone serpent rig, shared by Super and Cerulean. It is NOT DMZ's shenron rig: the bones are named
     * {@code supershenron / neck / segment1..14 / tail1..4 / arm_left / arm_right}, so DMZ's
     * {@code shenron.animation.json} (whose bones are {@code bone1..41 / brazoizquierdo}) drives nothing on it and
     * the dragon would hang frozen in its bind pose. {@link #HD_DRAGON_ANIM} is the matching idle, and the two must
     * always be named together.
     */
    private static final String HD_DRAGON_ANIM = "dmz_ragnarok:animations/entity/dragon/shenron_hd.animation.json";

    private static void registerSuper()
    {
        // Super uses our own 4x-scaled ball geo (dball_super4x) because the Super balls are drawn larger, and our
        // own HD serpent geo for the dragon itself.
        DragonBallDefinitions.registerBootstrapBallSetAsset(new DragonBallSetAssetDefinition(
                "super_ballset",
                "dmz_ragnarok:block/custom/dballblock_super",
                "dmz_ragnarok:item/dball_super%d",
                "dmz_ragnarok:geo/block/dball_super4x.geo.json",
                "dragonminez:animations/block/dball.animation.json",
                "dmz_ragnarok:textures/block/custom/dballblock_super",
                null,
                null));
        DragonBallDefinitions.registerBootstrapRadarAsset(new DragonRadarAssetDefinition(
                "super_radar",
                "dmz_ragnarok:item/super_dball_radar",
                "dmz_ragnarok:item/super_dball_radar",
                "dragonminez:textures/gui/radar.png",
                // no dot texture: our HUD (SuRadarHud, via MixinDmzRadarDraw) draws blips procedurally, and DMZ's own
                // radar HUD sources its dot sprite from radar.png, so the old dragonminez:textures/gui/radar_dot.png
                // reference pointed at a file the DMZ jar does not ship. Dropped to null (blankToNull keeps it absent).
                null,
                null));
        DragonBallDefinitions.registerBootstrapDragonAsset(new DragonAssetDefinition(
                "super_shenron",
                "default",
                "dmz_ragnarok:geo/entity/dragon/shenron_super.geo.json",
                "dmz_ragnarok:textures/entity/dragon/shenron_super.png",
                HD_DRAGON_ANIM));
        DragonBallDefinitions.registerBootstrapBallSet(new DragonBallSetDefinition(
                "super",
                PLANET_SURFACE_ONLY,
                // copies 0: the seven Super balls are placed by the Destroyer Gods on the Super Dragon Ball planets
                // (SuperPlanetGod drops the matching block at the planet surface centre on death), so DMZ must never
                // auto-scatter a second Super set onto the overworld. The planet_surface set dimension is also what lets
                // buildRadarPacket gather those god-dropped balls for the radar.
                fixed(COPIES_NONE),
                SuDragonBallDefinitions::boundedOverworldRange,
                SUPER_SUMMON_RADIUS,
                sevenStarBlocks("super"),
                "super_ballset",
                "Super Dragon Ball"));
        DragonBallDefinitions.registerBootstrapRadar(new DragonRadarDefinition(
                "super_radar",
                "super_dball_radar",
                SPACE_AND_PLANET_SURFACE,
                "super",
                "item.dragonminez.super_dball_radar.tooltip",
                RADAR_RANGES,
                null,
                "super_radar",
                "Super Radar"));
        DragonBallDefinitions.registerBootstrapDragon(new DragonDefinition(
                "super_shenron",
                "super_shenron",
                4.0f,
                24.0f,
                DRAGON_DIMENSIONS,
                "super",
                // wishScreenId. NOT screen art: GrantWishC2S does WishManager.getAllWishes().get(this),
                // so it is the KEY OF THE WISH LIST this dragon grants from. It said "shenron", so every
                // custom set showed Earth Shenron's stock wishes and granted from them, while the list
                // seeded for this dragon was never displayed to anybody.
                "super_shenron",
                1,
                "super_shenron"));
    }

    private static void registerCerulean()
    {
        // Cerulean is a genuine TWO-ball set (only stars 1 and 2), so it needs our half-size geo. The Cerulean dragon
        // wears our own cyan skin on the shared HD serpent rig, which differs from Super's only in the arms.
        //
        // Cerulean must NOT spawn in the world yet (Shurui's intent: it becomes real when Planet Cereal ships).
        // COPIES_NONE = 0 does NOT achieve that on its own, and the old comment here claimed it did: it does not,
        // because DragonBallSetDefinition.getCopies() returns Math.max(1, copies) and floors 0 to 1, so DMZ scatters
        // one copy per star regardless. The live shards prove it: cerulean balls are physically placed on both open
        // world twins today. The real suppression is CEREAL_ONLY below: the set is confined to a dimension that does
        // not exist yet, so DMZ's first-spawn finds no level and skips it, and nothing scatters it anywhere real. See
        // the CEREAL_ONLY declaration for the full mechanism. COPIES_NONE is kept only so the count is right the day
        // Planet Cereal exists.
        DragonBallDefinitions.registerBootstrapBallSetAsset(new DragonBallSetAssetDefinition(
                "cerulean_ballset",
                "dmz_ragnarok:block/custom/dballblock_cerulean",
                "dmz_ragnarok:item/dball_cerulean%d",
                "dmz_ragnarok:geo/block/dball_cerulean_half.geo.json",
                "dragonminez:animations/block/dball.animation.json",
                "dmz_ragnarok:textures/block/custom/dballblock_cerulean",
                null,
                null));
        DragonBallDefinitions.registerBootstrapRadarAsset(new DragonRadarAssetDefinition(
                "cerulean_radar",
                "dmz_ragnarok:item/cerulean_dball_radar",
                "dmz_ragnarok:item/cerulean_dball_radar",
                "dragonminez:textures/gui/radar.png",
                // no dot texture: our HUD (SuRadarHud, via MixinDmzRadarDraw) draws blips procedurally, and DMZ's own
                // radar HUD sources its dot sprite from radar.png, so the old dragonminez:textures/gui/radar_dot.png
                // reference pointed at a file the DMZ jar does not ship. Dropped to null (blankToNull keeps it absent).
                null,
                null));
        DragonBallDefinitions.registerBootstrapDragonAsset(new DragonAssetDefinition(
                "cerulean_shenron",
                "default",
                "dmz_ragnarok:geo/entity/dragon/shenron_cerulean.geo.json",
                "dmz_ragnarok:textures/entity/dragon/shenron_cerulean.png",
                HD_DRAGON_ANIM));
        DragonBallDefinitions.registerBootstrapBallSet(new DragonBallSetDefinition(
                "cerulean",
                CEREAL_ONLY,
                fixed(COPIES_NONE),
                SuDragonBallDefinitions::boundedOverworldRange,
                SUMMON_RADIUS,
                twoStarBlocks("cerulean"),
                "cerulean_ballset",
                "Cerulean Dragon Ball"));
        DragonBallDefinitions.registerBootstrapRadar(new DragonRadarDefinition(
                "cerulean_radar",
                "cerulean_dball_radar",
                OVERWORLD_ONLY,
                "cerulean",
                "item.dragonminez.cerulean_dball_radar.tooltip",
                RADAR_RANGES,
                null,
                "cerulean_radar",
                "Cerulean Radar"));
        DragonBallDefinitions.registerBootstrapDragon(new DragonDefinition(
                "cerulean_shenron",
                "cerulean_shenron",
                3.0f,
                17.0f,
                DRAGON_DIMENSIONS,
                "cerulean",
                // wishScreenId. NOT screen art: GrantWishC2S does WishManager.getAllWishes().get(this),
                // so it is the KEY OF THE WISH LIST this dragon grants from. It said "shenron", so every
                // custom set showed Earth Shenron's stock wishes and granted from them, while the list
                // seeded for this dragon was never displayed to anybody.
                "cerulean_shenron",
                1,
                "cerulean_shenron"));
    }

    /**
     * SEED our three dragons' wishes IF ABSENT, never overwrite. Called from {@link SuDragonWishBridge} on
     * {@code OnDatapackSyncEvent}, which fires on every login and every datapack reload. Runs BEFORE DMZ's own
     * default-priority sync handler on that event, so the map we leave behind is what gets synced to clients.
     *
     * <p>WHY seed-if-absent and not overwrite. An unconditional {@code put} clobbers whatever is in the map,
     * including a wish list an operator just saved through the wish editor (SDU writes it to
     * {@code <world>/dragonminez/wishes/<dragon>.json}). We must only install our hardcoded first-run defaults
     * where there is genuinely no wish data yet. "No data" means the runtime map has NO entry for the id OR an
     * EMPTY list: DMZ's reload path ({@code DragonWishRegistry.apply()}) rebuilds the map from the dragonballs
     * disk pack, which has no entry for our dragons, so it inserts {@code putIfAbsent(id, List.of())} for each,
     * i.e. an empty list means "the reload wiped this", not "deliberately empty". See {@link #hasNoWishes}.
     *
     * <p>WHY a bare seed-if-absent is not enough on a {@code /reload}. A vanilla datapack reload runs
     * {@code apply()} (which empties our ids) but does NOT re-read the per-world wish files, so a saved edit would
     * be gone from the runtime map and we would wrongly re-seed the hardcoded default over it. Before seeding we
     * therefore ask DMZ to re-overlay those files itself via {@code WishManager.loadWishes(server)} whenever any of
     * our ids is empty. That is exactly what DMZ already does at {@code ServerStartingEvent}: it merges every
     * {@code <world>/dragonminez/wishes/*.json} onto the current map, so a saved edit is restored before we look at
     * seeding. We do not hardcode or derive the wishes directory ourselves; DMZ owns that path and resolves it from
     * the server world directory. The overlay is skipped on a normal login, where {@code apply()} did not run and
     * our ids are still populated, so a login does no file I/O. May throw; the calling handler catches.
     */
    public static void reapplyWishes()
    {
        // Every id seeded below belongs to one of the three custom sets, so with those held back there is nothing
        // here to reapply.
        if (!net.shurui.shuruisutilities.core.ReleaseToggles.CUSTOM_DRAGON_BALL_SETS)
            return;
        Map<String, List<Wish>> current = DragonWishRegistry.getServerWishes();

        // If a datapack reload just emptied any of our ids, have DMZ re-overlay the per-world wish files so a saved
        // edit is back in the map before we consider seeding. No-op on a login (our ids are still populated then).
        if (hasNoWishes(current, "apophis")
                || hasNoWishes(current, "super_shenron")
                || hasNoWishes(current, "cerulean_shenron"))
        {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null)
            {
                WishManager.loadWishes(server);
                current = DragonWishRegistry.getServerWishes();
            }
        }

        // Seed the hardcoded first-run defaults ONLY where there is still no wish data (a brand new world with no
        // saved file). Any non-empty list, whether a live edit, a login carrying the last value, or a file the
        // overlay above just restored, is left untouched so an operator's edit is never clobbered.
        Map<String, List<Wish>> merged = new LinkedHashMap<>(current);
        seedIfAbsent(merged, "apophis", 5000);
        seedIfAbsent(merged, "super_shenron", 20000);
        seedIfAbsent(merged, "cerulean_shenron", 20000);
        // Two of our wishes ride on DMZ's own SHENRON list rather than one of ours, because both belong to Earth.
        // Neither touches Porunga, or any dragon other than shenron.
        //
        // ORDER IS LOAD BEARING. Both are appended rather than seeded, because they must be re-added after every
        // reload. SSJ5 is the only one that is hidden per player, and DMZ grants a wish by INDEX, so SSJ5 has to end
        // up the LAST row: a hidden row anywhere else would shift every wish beneath it for the players who cannot
        // see it. So the always-visible knowledge wish goes on first and SSJ5 goes on after it.
        merged = SuSsgKnowledgeWish.withSsgKnowledge(merged);
        merged = SuSsj5Wish.withSsj5(merged);
        // Super Shenron's three standing wishes, appended for the same reason the two above are: seeding only
        // reaches a world that has never written a wish file, so a wish added after a server has run would never
        // appear there. Its list carries no per-player filtering, so appending to the end is safe. See SuperWishes.
        merged = SuperWishes.withSuperWishes(merged);
        DragonWishRegistry.setServerWishes(merged);
    }

    // True when the runtime map carries no meaningful wish data for this dragon: either no entry at all, or an
    // empty list (which is what DMZ's reload inserts for a dragon the disk pack does not mention).
    private static boolean hasNoWishes(Map<String, List<Wish>> map, String dragonId)
    {
        List<Wish> existing = map.get(dragonId);
        return existing == null || existing.isEmpty();
    }

    // Install the hardcoded default wishes for this dragon only when the map has no meaningful data for it.
    private static void seedIfAbsent(Map<String, List<Wish>> map, String dragonId, int trainingPoints)
    {
        if (hasNoWishes(map, dragonId))
        {
            map.put(dragonId, wishesFor(dragonId, trainingPoints));
        }
    }

    // Each of our dragons grants the same shape of two wishes: a lump of training points, then a handful of senzu.
    // The training amount is the only thing that differs between dragons. Name / description are translation keys
    // that live in our lang files.
    private static List<Wish> wishesFor(String dragonId, int trainingPoints)
    {
        List<Wish> wishes = new ArrayList<>(2);
        wishes.add(new TPSWish(
                "wish." + dragonId + ".tps.name",
                "wish." + dragonId + ".tps.desc",
                trainingPoints));
        wishes.add(new ItemWish(
                "wish." + dragonId + ".senzu.name",
                "wish." + dragonId + ".senzu.desc",
                SENZU_ITEM_ID,
                SENZU_WISH_COUNT));
        return wishes;
    }

    // A constant IntSupplier. DMZ takes suppliers here so a set can be config-driven; our pack values were plain
    // literals, so we hand back a fixed value. Still used for the COPIES counts, which are genuine literals.
    private static IntSupplier fixed(int value)
    {
        return () -> value;
    }

    // The overworld scatter bound, read LIVE from SU config each time DMZ scatters a set. This is the spawn range
    // used AS-IS by our three overworld sets (Black Star, Super, Cerulean): the old pack hardcoded 3000, this makes
    // that same number an operator-tunable bound so nothing scatters past the edge of the imported custom map.
    // Used via method reference as an IntSupplier, so it is evaluated per scatter, not at registration time.
    private static int boundedOverworldRange()
    {
        return SUConfig.overworldDragonBallScatterRange;
    }

    // The spawn range for DMZ's Earth set. DMZ's own Earth pulls its range from DMZ's server config
    // (getDBSpawnRange, default 1000, an operator may raise it). We keep that value but CLAMP it to the overworld
    // map bound so a raised DMZ range can never fling Earth balls past the edge of the imported map where they would
    // be lost: a smaller DMZ range is respected untouched, a larger one is capped to the bound.
    private static int boundedEarthRange()
    {
        int dmzRange = ConfigManager.getServerConfig().getWorldGen().getDBSpawnRange();
        return Math.min(dmzRange, boundedOverworldRange());
    }

    // The star -> block registry name map for DMZ's Earth set (plain dball1..dball7, NO set suffix). Mirrors the
    // Map.of(1,"dball1", .. ,7,"dball7") in DMZ's static block; order is irrelevant for a lookup map.
    private static Map<Integer, String> earthStarBlocks()
    {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        for (int star = 1; star <= 7; star++)
        {
            blocks.put(star, "dball" + star);
        }
        return blocks;
    }

    // The star -> block registry name map for a full seven-ball set (dball1_<suffix> .. dball7_<suffix>).
    private static Map<Integer, String> sevenStarBlocks(String suffix)
    {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        for (int star = 1; star <= 7; star++)
        {
            blocks.put(star, "dball" + star + "_" + suffix);
        }
        return blocks;
    }

    // The star -> block registry name map for a two-ball set (Cerulean: only stars 1 and 2 exist).
    private static Map<Integer, String> twoStarBlocks(String suffix)
    {
        Map<Integer, String> blocks = new LinkedHashMap<>();
        blocks.put(1, "dball1_" + suffix);
        blocks.put(2, "dball2_" + suffix);
        return blocks;
    }
}
