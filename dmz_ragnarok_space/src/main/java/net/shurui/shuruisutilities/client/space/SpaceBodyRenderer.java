package net.shurui.shuruisutilities.client.space;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.PoseStack.Pose;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.client.dball.DragonBallHull;
import net.shurui.shuruisutilities.client.dball.DragonBallShell;
import net.shurui.shuruisutilities.compat.SodiumSpriteAnimation;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.space.BlackHolePositions;
import net.shurui.shuruisutilities.space.GeneratedPlanets;
import net.shurui.shuruisutilities.space.GeneratedSystems;
import net.shurui.shuruisutilities.space.FixedBody;
import net.shurui.shuruisutilities.space.OrbitClock;
import net.shurui.shuruisutilities.space.Orbits;
import net.shurui.shuruisutilities.space.MoonBody;
import net.shurui.shuruisutilities.space.PlanetPositions;
import net.shurui.shuruisutilities.space.PlanetRings;
import net.shurui.shuruisutilities.space.SpaceDimension;
import net.shurui.shuruisutilities.space.SurfaceDimension;
import net.shurui.shuruisutilities.space.SpaceLayout;
import net.shurui.shuruisutilities.space.StarPositions;
import net.shurui.shuruisutilities.space.SuperPlanetPositions;

/**
 * Draws every space BODY (fixed planets, generated planets, stars, black holes) client-side with NO entity, no tracking
 * range, no chunk dependency. The client re-derives them from the same position hash the server uses
 * ({@link GeneratedPlanets}, {@link StarPositions}, {@link BlackHolePositions}, fixed {@link SpaceLayout#fixedBodies},
 * all synced by PacketSpaceLayoutSync) and draws them at their true camera-relative positions, visible as far as the
 * draw distance allows whatever the player's render distance.
 *
 * <p>Hooks {@link RenderLevelStageEvent} at {@code AFTER_SKY} (the procedural star field in
 * {@link SpaceDimensionEffects} is already drawn) so bodies lay over it and under the terrain. SPACE dimension only.
 *
 * <p>Far-plane handling: the vanilla projection clips at a far plane from render distance, so a body thousands of blocks
 * out would be culled. Past {@link #SHELL} blocks we keep the true DIRECTION but pull the drawn position onto the shell
 * and scale drawn size by the same factor, preserving angular size so it never crosses the far plane. Inside the shell a
 * body draws at its true camera-relative position with full parallax (the near body you fly to and land on). Bodies are
 * drawn BACK TO FRONT by true camera distance and each flushed before the next. Full-bright.
 *
 * <p>Bounded per-frame work: the cell walk in the *Near derivations is NOT run every frame. {@link #cache} holds the
 * derived near-set, refreshed at most {@link #REFRESH_INTERVAL_MS} apart or when the camera moves more than
 * {@link #REFRESH_MOVE}. At the default draw distance (8000) and default sectors the worst case is generated planets: a
 * 2048-block sector gives ~8 cells per axis across 16000 blocks, ~8^3 = 512 cells at density 0.5 = ~256 bodies, plus a
 * handful of fixed planets, a few dozen stars (6144 sector) and a couple of black holes (14336 sector).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class SpaceBodyRenderer
{
    private SpaceBodyRenderer()
    {
    }

    private static final Logger LOGGER = LogUtils.getLogger();

    // grayscale placeholder set (tinted per body); a body with no dedicated art picks ONE of these three deterministically
    // from its hash (resolvePlanet) for variety. All three are 64x64 box unwraps. PLANET_TEXTURE_DIR holds real per-planet
    // art (Namek, Earth), drawn UNTINTED.
    private static final ResourceLocation[] PLANET_PLACEHOLDERS = {
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/planet/placeholder_0.png"),
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/planet/placeholder_1.png"),
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/planet/placeholder_2.png")
    };
    private static final String PLANET_TEXTURE_DIR = "textures/entity/space/planet/";

    // The artist's Earf earth model: two 64x64 box-unwrap sheets sharing ONE layout, body at uv[0,0] (PLANET_BOX_UV),
    // Clouds shell at uv[32,0] (CLOUDS_BOX_UV). Every FIXED body draws these on the hand-cube path (drawFixedBody).
    // EARF_COLOUR is full colour (Earth wears it untinted). EARF_GRAYSCALE is the body in grayscale so every OTHER fixed
    // body multiplies it by FIXED_BODY_TINT and keeps continent luminance. The grayscale sheet has NO cloud pixels in its
    // [32,0] region, so the cloud shell always samples EARF_COLOUR's cloud region drawn white, keeping clouds white over a
    // green Namek. Grayscale png was converted LA -> RGBA on copy: the binder expects RGBA, an LA sheet can silently fail
    // to load.
    private static final ResourceLocation EARF_COLOUR =
            new ResourceLocation(ShuruisUtilities.MODID, PLANET_TEXTURE_DIR + "earf.png");
    private static final ResourceLocation EARF_GRAYSCALE =
            new ResourceLocation(ShuruisUtilities.MODID, PLANET_TEXTURE_DIR + "earf_grayscale.png");

    // BOX UV: standard Minecraft box unwrap, one 8x8x8 cube at uv[0,0] on a 64x64 sheet, so each face samples its OWN
    // region and adjacent faces line up across the corner seams. Pixel regions come from the reference model
    // Earf.geo.json (single cube, uv[0,0], 64x64), reproduced as UV fractions (pixel/64); we do NOT read the .geo.json at
    // runtime, only borrow its layout. Rows are {u0,v0,u1,v1} in drawCube's face order (-Z,+Z,-X,+X,-Y,+Y). Geo names map
    // by outward normal: north=-Z, south=+Z, east=-X, west=+X, top=+Y, bottom=-Y.
    private static final float BOX = 64.0F;
    private static final float[][] PLANET_BOX_UV = {
            { 8.0F / BOX,  8.0F / BOX, 16.0F / BOX, 16.0F / BOX}, // -Z  (geo north  (8,8)-(16,16))
            {24.0F / BOX,  8.0F / BOX, 32.0F / BOX, 16.0F / BOX}, // +Z  (geo south  (24,8)-(32,16))
            { 0.0F / BOX,  8.0F / BOX,  8.0F / BOX, 16.0F / BOX}, // -X  (geo east   (0,8)-(8,16))
            {16.0F / BOX,  8.0F / BOX, 24.0F / BOX, 16.0F / BOX}, // +X  (geo west   (16,8)-(24,16))
            {16.0F / BOX,  0.0F / BOX, 24.0F / BOX,  8.0F / BOX}, // -Y  (geo bottom (16,0)-(24,8))
            { 8.0F / BOX,  0.0F / BOX, 16.0F / BOX,  8.0F / BOX}  // +Y  (geo top    (8,0)-(16,8))
    };

    // the dedicated cloud sheet every main planet's cloud shell samples (CloudTexture.png). A 256x256 RGBA sheet whose
    // cloud content is a soft semi-transparent cube net (alpha 120..254, near-white) in the top-left 128x64, rest
    // transparent. Its alpha is a soft gradient, so the cloud shell MUST use a TRANSLUCENT (alpha-blended) type, not a
    // cutout one, or the softness thresholds away (drawCloudShell).
    private static final ResourceLocation CLOUD_SHEET =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/cloud_texture.png");

    // CLOUD_SHEET box unwrap: the SAME six-face cube net as CLOUDS_BOX_UV, rescaled onto CLOUD_SHEET. The old net was a
    // 32x16 block at pixel (32,0) on the 64x64 Earf sheet; this holds the identical net at 4x (128x64) anchored at the
    // origin of a 256x256 sheet, so each face fraction is (oldPixel-32)/64 across, oldPixel/64 down. All six land on cloud
    // pixels (27..43% soft coverage per face), none on the empty part. Face order matches drawBoxCube: -Z,+Z,-X,+X,-Y,+Y.
    private static final float[][] CLOUD_SHEET_UV = {
            { 8.0F / 64.0F,  8.0F / 64.0F, 16.0F / 64.0F, 16.0F / 64.0F}, // -Z
            {24.0F / 64.0F,  8.0F / 64.0F, 32.0F / 64.0F, 16.0F / 64.0F}, // +Z
            { 0.0F / 64.0F,  8.0F / 64.0F,  8.0F / 64.0F, 16.0F / 64.0F}, // -X
            {16.0F / 64.0F,  8.0F / 64.0F, 24.0F / 64.0F, 16.0F / 64.0F}, // +X
            {16.0F / 64.0F,  0.0F / 64.0F, 24.0F / 64.0F,  8.0F / 64.0F}, // -Y
            { 8.0F / 64.0F,  0.0F / 64.0F, 16.0F / 64.0F,  8.0F / 64.0F}  // +Y
    };

    // CLOUDS box unwrap (geo cube "Clouds", 8x8x8, uv[32,0], inflate 0.5). Same face order and mapping as above. Drawn as
    // a translucent overlay shell around the body (drawPlanet); a transparent cloud pixel lets the body show through.
    private static final float[][] CLOUDS_BOX_UV = {
            {40.0F / BOX,  8.0F / BOX, 48.0F / BOX, 16.0F / BOX}, // -Z  (geo north  (40,8)-(48,16))
            {56.0F / BOX,  8.0F / BOX, 64.0F / BOX, 16.0F / BOX}, // +Z  (geo south  (56,8)-(64,16))
            {32.0F / BOX,  8.0F / BOX, 40.0F / BOX, 16.0F / BOX}, // -X  (geo east   (32,8)-(40,16))
            {48.0F / BOX,  8.0F / BOX, 56.0F / BOX, 16.0F / BOX}, // +X  (geo west   (48,8)-(56,16))
            {48.0F / BOX,  0.0F / BOX, 56.0F / BOX,  8.0F / BOX}, // -Y  (geo bottom (48,0)-(56,8))
            {40.0F / BOX,  0.0F / BOX, 48.0F / BOX,  8.0F / BOX}  // +Y  (geo top    (40,0)-(48,8))
    };

    // a star is a SOLID fully bright cube in its own hashed stellar colour: a plain 8x8 all-white sheet with the star tint
    // multiplied over it, so every face and corner is that colour with no dark. The vanilla sun sheet was dropped: its
    // disc only fills the sheet centre, so a square face left black corners. Tint from StarPositions.colourTint (warm
    // whites, yellows, oranges, reds, some blue-white).
    private static final ResourceLocation STAR_BODY_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/star_body.png");
    // soft round radial-falloff sheet (white core fading to transparent at the edge), tinted to the star colour and drawn
    // as a camera-facing billboard around the cube. Additive-emissive so it only adds light, never darkens.
    private static final ResourceLocation STAR_HALO_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/star_halo.png");
    // black hole disc: a radial dark disc, camera-facing billboard.
    private static final ResourceLocation BLACK_HOLE_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/black_hole.png");
    // a faint orange spiral streak spun over the horizon. An APPROXIMATION of lensing, not real screen-space distortion:
    // suggests swept lensed light without a framebuffer pass (see drawBlackHole).
    private static final ResourceLocation BLACK_HOLE_SWIRL_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/black_hole_swirl.png");
    // the REAL artist ring sheets from the solar-system pack, bound STANDALONE (not off the block atlas) so the procedural
    // ring pass samples genuine ring art. Both 32x32; in saturn.json/uranus.json the ring plane's up/down face samples UV
    // [0,0]..[16,16], i.e. the WHOLE sheet is the annulus, so drawFlatRing's 0..1 map is exactly right, no crop needed. We
    // use the GRAYSCALE variants and multiply the ring's own dust tint over them, so the ring keeps its independent colour
    // (never the planet's) while showing real ring structure as luminance.
    private static final ResourceLocation SATURN_RINGS_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/solar_system_pack/saturn_rings_grayscale.png");
    private static final ResourceLocation URANUS_RINGS_TEXTURE =
            new ResourceLocation(ShuruisUtilities.MODID, "textures/solar_system_pack/uranus_rings_grayscale.png");
    // measured opaque-pixel mean luminance of each ring sheet (saturn 0.82 over 56% coverage, uranus 0.95 over 26%).
    // LIGHTER than the body sheets (0.53..0.74), so the ring lift uses THESE, not a body mean: a 0.95 sheet through a
    // body's ~0.6 mean would over-lift and blow the rings out to near white.
    private static final float SATURN_RINGS_MEAN = 0.822F;
    private static final float URANUS_RINGS_MEAN = 0.954F;

    // beyond this camera-relative distance a body draws on a shell of this radius (direction kept, size scaled) so it
    // never crosses the far plane. Larger than the near band a player flies through (landing bodies keep parallax), small
    // enough to sit well inside the far plane at any render distance.
    private static final double SHELL = 256.0;

    // FIXED main planets (Earth, Namek, Sacred Kai, ...) and the central SUN sit tens of thousands of blocks apart, far
    // past the general bodyDrawDistance (default 8000) that bounds the numerous generated planets/stars/black holes.
    // Culling them at that distance would empty the sky of the other main bodies, so they get their OWN much larger
    // distance: only a handful, so always considering them is trivial, and shellDrawScale keeps a distant one drawn at
    // true angular size as a visible landmark. Since B2 the bodies orbit the sun on concentric rings, so the outermost
    // ring can sit well past 100k blocks (inner floor 6000 + up to a dozen 15000-spaced rings); sized generously to keep
    // the sun and every ring visible from anywhere in the system.
    private static final double FIXED_BODY_DRAW_DISTANCE = 400000.0;

    // HONEST PERSPECTIVE SIZING. Drawn size is the true radius shrunk by the SAME 1/dist factor as the drawn position
    // (drawBody): past the shell, true DIRECTION kept, pulled onto the shell and scaled by SHELL/dist, so angular size is
    // preserved. An earlier experiment decoupled size from distance (FAR_SIZE_FALLOFF + MIN_DRAWN_RADIUS floor); it made
    // every planet read as HUGE and roughly one size, so it was reverted. Size spread now comes from the bodies' REAL
    // radii: a generated planet's radius scales from its stamped block surface (100..500 -> 32..96, see
    // GeneratedPlanets.bodyRadiusForSurfaceSize), a fixed body from PlanetPositions.radius.

    // STAR DRAWN SIZE (defect 4): a star's cube and corona are multiplied by this over the shell size, so stars read as
    // beacons. Purely visual: the TRUE radius (overlap footprint, burn-field origin in SpaceHazardModule) is unchanged,
    // and the burn field reaches past even this enlarged draw, so a player still burns before the visible surface.
    private static final float STAR_DRAW_SCALE = 3.0F;

    // DISTANT SYSTEM SUNS ("space seems super empty"). A generated star system is only ONE per ~20000-block sector, so the
    // near draw distance (~8000) rarely contains one and the sky read empty. Every system's SUN is now drawn as a cheap
    // far beacon from ANY distance out to this limit (the whole charted universe), so systems are always visible as distant
    // stars even when none is within the near range. The enumeration is the cached deterministic system grid
    // (GeneratedSystems.allSystems), so this adds no per-frame cell walking; it is capped and drawn as a couple of additive
    // discs each. A sun within the NEAR range is already drawn full (drawStar via StarPositions.starsNear) and is skipped
    // here, so nothing draws twice.
    private static final double SYSTEM_SUN_DRAW_DISTANCE = GeneratedSystems.UNIVERSE_HALF_EXTENT;
    // Hard cap on far suns added per refresh, nearest first, so an enormous universe can never make the cached list or the
    // per-frame draw loop unbounded. The nearest systems win; farther ones are simply off the beacon field.
    private static final int MAX_FAR_SUNS = 220;
    // A floor on a far body's DRAWN half-extent (on the ~256-unit shell), so a system tens of thousands of blocks away still
    // reads as a small point rather than shrinking to nothing. IMPORTANT (the "planets intersecting at distance" fix): the
    // floor never GROWS the drawn disc past the honest angular size and then reads as a big bright ball covering nearer
    // bodies. Instead, once a body's honest angular size drops below the floor, the disc is pinned at the floor but its
    // BRIGHTNESS fades toward FAR_MIN_ALPHA_FRAC (see farFadeAlpha), so a very distant body reads as a dim faint point, not
    // an inflated disc. That, plus every far body being an additive billboard that never writes depth and being drawn
    // strictly far-to-near, is what stops a floored far body from ever covering a nearer one.
    private static final float MIN_FAR_SUN_DRAW = 0.9F;
    private static final float MIN_FAR_PLANET_DRAW = 0.45F;
    // the dimmest a fully-floored far body fades to, as a fraction of its base alpha: a faint point that still reads,
    // never a bright disc.
    private static final float FAR_MIN_ALPHA_FRAC = 0.28F;

    // DISTANT SYSTEMS AS SUN + PLANETS ("the glowing orbs isn't doing it for me"). Beyond the near range a system is drawn
    // as its sun beacon PLUS, for the nearest few far systems, its planets as small lit points at their real current
    // orbital positions, so a distant system reads as a solar system rather than a lone ball. Only the nearest
    // MAX_FAR_SYSTEMS_WITH_PLANETS get planets (nearest-first), so the far-planet count stays bounded (at most that many
    // systems times nine planets) however large the universe is; farther systems keep just the sun.
    private static final int MAX_FAR_SYSTEMS_WITH_PLANETS = 48;

    // slow drifts, matching the old renderers.
    private static final float PLANET_DEG_PER_TICK = 0.25F;
    private static final float STAR_DEG_PER_TICK = 0.15F;
    private static final float BLACK_HOLE_DEG_PER_TICK = 0.6F;
    // a super dragon ball turns a touch faster than a planet so its painted stars visibly sweep across the face.
    private static final float SUPER_DEG_PER_TICK = 0.4F;

    // moon orbit period, radius, direction and size all live in MoonBody (ORBIT_DEG_PER_TICK, ORBIT_FACTOR,
    // RADIUS_FACTOR), the single source of truth shared with the server's landing volume; drawMoon reads them straight
    // from there so drawn moon and landing volume never drift. At ORBIT_DEG_PER_TICK (0.05 deg/tick) a full orbit takes
    // 7200 ticks = SIX MINUTES. RADIUS_FACTOR/ORBIT_FACTOR are fractions of the body half-extent, so the look holds at
    // every size.

    // a moon is the SAME Earf body model as its parent but ALL GREY whatever planet it orbits, so it reads as bare
    // airless rock. This mid-light grey multiplies the grayscale Earf sheet: a multiply preserves luminance ratios so the
    // surface detail stays legible; kept light so the moon still reads as a lit body. No cloud shell ever (no atmosphere).
    private static final int MOON_GREY = 0xB0B0B0;

    // cloud shell MUST spin at exactly PLANET_DEG_PER_TICK, the body's own rate, so the two cubes stay face-aligned. These
    // shells are concentric CUBES: any relative yaw pushes the shell's corners through the body's flat faces and it reads
    // as a separate box rotated inside the world. An earlier version spun the clouds faster to drift weather; that is
    // impossible on a cube and must not be restored by re-splitting the spin rates.
    // scale: the geo Clouds cube inflates the 8-unit body by 0.5 per side, so (8+0.5+0.5)/8 = 1.125, like a player skin's
    // outer layer. This concentric-cube tilt/spin constraint binds the body, cloud shell and gas-giant envelope, which is
    // why a FIXED body keeps all three (and its moon) at a flat 20 degree tilt. Its RING is the ONE exception: a single
    // flat plane, no corners to push through, so it draws from the baked uranus geometry at that model's 45 degree tilt
    // (drawFixedBodyRings) while everything else stays at 20.
    private static final float CLOUD_INFLATE_FACTOR = 1.125F;

    // gas-giant atmosphere shell scale: a shade larger than the cloud shell (1.16x vs 1.125x) so the envelope sits just
    // outside any cloud layer and reads as thick hazy atmosphere, not a decal welded to the surface.
    private static final float GAS_GIANT_INFLATE_FACTOR = 1.16F;
    // gas-giant opacity: half-transparent so the recoloured body shows through as banding. Distinct from Earth's
    // near-opaque white cloud sprite.
    private static final float GAS_GIANT_ALPHA = 0.5F;
    // the gas-giant shell spins at PLANET_DEG_PER_TICK with no rate of its own: it is a concentric cube, so any relative
    // yaw pushes its corners through the body's faces (see cloud shell above). It samples the opaque body region, so a
    // mismatch shows plainly; keep it face-aligned.

    // how many cube shards a destroyed planet breaks into: enough to read as a body coming apart, few enough that the
    // burst is one small batch per planet.
    private static final int SHARD_COUNT = 24;
    // how far a shard travels by end of window, as a multiple of the planet's drawn half-extent. At 2.5 the debris field
    // ends about five body-widths across.
    private static final float SHARD_SPREAD = 2.5F;
    // a shard's starting size as a fraction of the planet's half-extent. Chunks of a world, not gravel.
    private static final float SHARD_SIZE = 0.22F;
    // independent salts so each per-shard value comes from its own full-width hash. Deriving them by shifting ONE hash
    // pinned every shard to a single direction and speed (see drawShatter).
    private static final int SALT_AZIMUTH = 0x5A1;
    private static final int SALT_ELEVATION = 0xE1E;
    private static final int SALT_SPEED = 0x59D;
    private static final int SALT_SPIN = 0x591;
    // fraction of the shatter window the detonation flash occupies. A punch, not a lingering glow.
    private static final float FLASH_WINDOW = 0.45F;
    // how far the flash expands, as a multiple of the planet's drawn half-extent.
    private static final float FLASH_GROWTH = 2.0F;

    private static final int FULL_BRIGHT = 0x00F000F0;

    // the nameplate gate distance lives in SpaceLayout, NOT here, because SpaceLayout.LABEL_DISTANCE is BOTH this gate and
    // the planet-buster destruction range and the two must not drift ("you may only destroy a planet from the same
    // distance its name shows"). Measured to the body's SURFACE (centre distance minus radius).

    private static final long REFRESH_INTERVAL_MS = 1000L;
    private static final double REFRESH_MOVE = 64.0;

    private static long lastRefresh = 0L;
    private static Vec3 cacheOrigin = null;
    private static List<Body> cache = new ArrayList<>();

    // a resolved texture plus whether it is tinted (placeholder) or untinted (real per-planet art). Cached per key.
    private record Resolved(ResourceLocation texture, boolean tinted)
    {
    }

    private static final Map<String, Resolved> RESOLVED = new ConcurrentHashMap<>();

    private enum Kind { PLANET, STAR, BLACK_HOLE, SUPER, FAR_SUN, FAR_PLANET }

    // an UNCLAIMED super body reads as stone grey (ball not taken): the dragon ball geometry multiplied by this grey over
    // the plain white star sheet. A CLAIMED body draws the per-star texture untinted (dballblock_super<N>.png), whose
    // painted stars show the count. No orange tint; the claimed texture carries colour.
    private static final int SUPER_GREY = 0x9A9A9A;

    // A body to draw. For an ORBITING body (a fixed main planet, a generated system planet) the {@code orbit} field carries
    // its orbital CONSTANTS (centre, ring radius, start phase); its drawn position is recomputed EVERY FRAME from the shared
    // clock via {@link #livePos}, so it advances smoothly instead of snapping to the position captured when the ~1s body
    // cache was last rebuilt (which read as the body jumping). A non-orbiting body (a sun, a star, a black hole, a legacy
    // static planet, a super body) leaves {@code orbit} null and draws at its fixed {@code pos}. {@code pos} is still kept
    // for orbiting bodies too, as the snapshot the cache gathers/culls against.
    private record Body(Kind kind, Vec3 pos, float radius, int tint, String planetKey, OrbitParams orbit)
    {
        Body(Kind kind, Vec3 pos, float radius, int tint, String planetKey)
        {
            this(kind, pos, radius, tint, planetKey, null);
        }

        // the body's position RIGHT NOW: an orbiting body is recomputed from its constants and the clock, everything else is
        // its fixed pos. The maths matches PlanetPositions.orbitPositionAt / GeneratedSystems.planetPositionAt exactly, so a
        // rendered body sits where the server lands it.
        Vec3 livePos(long epoch)
        {
            if (orbit == null)
            {
                return pos;
            }
            // inclined orbit (a generated system planet, tilted plane) or flat (a fixed main planet, inclination 0). The
            // maths is exactly GeneratedSystems.planetPositionAt / PlanetPositions.orbitPositionAt, so the drawn body sits
            // where the server lands it.
            double[] xyz = Orbits.inclinedPosition(orbit.cx(), orbit.cy(), orbit.cz(), orbit.ring(), orbit.phase0(), epoch,
                    orbit.inclination(), orbit.nodeAngle());
            return new Vec3(xyz[0], xyz[1], xyz[2]);
        }
    }

    // orbital constants for an orbiting body: the centre it orbits (a sun), the ring radius, the start phase, and the
    // orbital-plane tilt (inclination + line of nodes; both 0 for a flat fixed-planet ring).
    private record OrbitParams(double cx, double cy, double cz, double ring, double phase0, double inclination,
                               double nodeAngle)
    {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        // one-shot per reload: whether each pack body baked and its body sprite made the block atlas, so a "pink missing"
        // regression is one log line (missing atlas source -> sprite=false, bad model -> baked=false).
        logPackDiagnostics(mc);
        if (mc.level == null || !SpaceDimension.isSpace(mc.level))
        {
            // not in space: drop doom state so a sequence never carries into a fresh visit or another dimension.
            PlanetDoomEffects.clear();
            // B1: on a generated-planet SURFACE, draw the sun and the sibling planets over the per-planet sky
            // (PlanetSurfaceEffects). This reuses the SAME body drawing (drawStar for the sun, drawPlanet for the
            // siblings), just placed on the sky by direction rather than at a true camera-relative position. It runs
            // only once the client has been told which planet it is on (SurfaceSkyState has a space-body anchor).
            if (mc.level != null && SurfaceDimension.isSurface(mc.level) && SurfaceSkyState.hasBody())
            {
                drawSurfaceSkyBodies(event, mc);
            }
            return;
        }

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        float partialTick = event.getPartialTick();

        refreshIfNeeded(cam);
        if (cache.isEmpty())
        {
            return;
        }

        // The event pose stack is already in view space (camera at origin, rotated by the view), so translating by
        // (body - camera) places a body at its true world position relative to the player.
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();

        // REAL LENSING: warp the sky/starfield around the nearest black hole BEFORE any body is drawn, so the void's
        // sphere, rim, discs and arcs paint over the warped sky. At AFTER_SKY the framebuffer holds only sky + starfield
        // (no terrain/entities), which is exactly the light to bend. Self-gates on config toggle, shader-mod presence and
        // a first-failure self-disable (BlackHoleLensing). When it does not run the black hole is still sphere + rim +
        // discs + arcs.
        runLensing(event, cam);

        // DEPTH POLICY (one policy for the whole pass). An opaque body draws through RenderType.entityCutout (write mask
        // COLOR_DEPTH_WRITE), so it writes depth regardless of any pass-level mask; that is why a solid convex planet cube
        // never rendered inside-out (it culls back faces AND writes its own depth). We state that as the policy:
        //   1. depth writes ON for the pass, so each OPAQUE body (entityCutout: cull + LEQUAL + depth write) is the depth
        //      authority: its faces occlude each other and nearer bodies occlude farther ones by TRUE depth.
        //   2. every TRANSLUCENT shell (cloud, gas, rings, rim, corona, nameplate) uses a COLOR_WRITE-only type, so it
        //      TESTS (LEQUAL) the committed body depth but never writes, submitted AFTER its body via flushInOrder. That
        //      splits a ring's near/far halves and clips the rim to the edge.
        //   3. bodies submitted BACK TO FRONT, so a nearer body's translucent shells paint over farther bodies.
        // Depth is COMPRESSED by shellDrawScale (a far body writes depth as though ~200 blocks out), so terrain drawn
        // later at TRUE depth would win LEQUAL and paint rocks over nearer-looking planets. We do NOT narrow the depth
        // range: shellDrawScale already piles distant bodies within ~1e-4 of NDC z=1.0, and squeezing the pass into
        // [0.99,1.0] collapsed that into exact 24-bit ties, on which LEQUAL passes and the last-drawn shatter debris
        // painted over planets it should sit behind. Instead we keep FULL depth precision (so body self-occlusion, rings
        // intersecting the body, stays exact) and CLEAR THE DEPTH BUFFER in the finally below. By then every body's COLOUR
        // is committed, so only scratch depth is wiped. Terrain and Distant Horizons LOD passes both run strictly after
        // AFTER_SKY (vanilla dispatches AFTER_SKY before the first renderChunkLayer, DH at the head of the solid layer),
        // so they start from a clean depth buffer and draw over the bodies: the conventional skybox treatment.
        RenderSystem.depthMask(true);
        try
        {
            logDepthPolicy();
            // Cast the long to DOUBLE before adding partialTick, not after: this is why the sky used to judder. On a
            // long-lived world getGameTime() is huge (production is past 41,500,000 ticks) and a float's 24-bit mantissa
            // resolves that to FOUR ticks apart, so "getGameTime() + partialTick" evaluated entirely in float (long+float
            // promotes to float) threw the partial away AND quantised whole ticks, advancing rotations ~5x a second
            // whatever the framerate. double's 53 bits represents this cleanly for longer than any server will run.
            double tickCount = (double) mc.level.getGameTime() + partialTick;

            // the shared orbit clock, read ONCE per frame: every orbiting body's live position is computed from it, so a
            // frame is internally consistent and orbits advance every frame (not once per ~1s cache rebuild).
            long epoch = OrbitClock.epochMillis();
            List<Body> ordered = new ArrayList<>(cache);
            ordered.sort((a, b) -> Double.compare(b.livePos(epoch).distanceToSqr(cam), a.livePos(epoch).distanceToSqr(cam)));
            logDepthBand(ordered, cam);
            for (Body body : ordered)
            {
                drawBody(poseStack, buffer, body, cam, tickCount, epoch);
                // flush AFTER EACH body so the BufferSource cannot reorder a nearer body's shells behind a farther body:
                // each body (opaque core then translucent shells) is fully drawn, back to front, before the next.
                buffer.endBatch();
            }

            // SHATTER: debris of a just-destroyed planet. From the doom state, NOT the body list, because the destroy
            // resyncs the layout the instant the planet dies, so there is no body left to hang shards off. Drawn last so
            // the translucent shards blend over the bodies behind them.
            drawShatters(poseStack, buffer, cam, tickCount);
        }
        finally
        {
            // Neutralise the pass's depth on every exit path. Bodies wrote COMPRESSED depth via shellDrawScale, scratch
            // state that must not outlive this pass or it competes with terrain at TRUE depth. Clearing ONLY the depth
            // buffer (mask GL_DEPTH_BUFFER_BIT, not colour) leaves every body's committed colour intact, so the following
            // terrain and DH LOD passes start clean and draw over the bodies. depthMask stays on.
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.depthMask(true);
        }
    }

    // ==== B1 per-planet surface sky bodies ====
    //
    // On a planet SURFACE the sun and the sibling planets are drawn on the sky by DIRECTION, not at a true
    // camera-relative position: a body infinitely far away sits at a fixed bearing whatever the player's coordinate. We
    // reuse the exact same body drawing the space view uses (drawStar for the sun, drawPlanet for a sibling) by placing
    // the body at (direction * SKY_BODY_SHELL_DIST) with the camera at the origin, so drawBody's shell map draws it at a
    // controlled angular size with no nameplate. This keeps ONE set of body art and geometry for both views.

    // How far out a sky body is placed. Its screen angular size is (drawn radius / this), and it must exceed
    // LABEL_DISTANCE + radius so drawBody never shows a nameplate on a sky body.
    private static final double SKY_BODY_SHELL_DIST = 2000.0;

    // ==== Surface-sky body ANGULAR SIZING (the one tuning block) ====
    // Owner report (real client test, e55ca28a): "the planets are huge when on the planet." The old model clamped a
    // sibling's drawn radius/shell ratio to at most 0.09 (an angular RADIUS of ~5.2 degrees, a ~10.4 degree disc) and
    // fixed the sun at the same ceiling, so every sky body filled a tenth of the view. We now size each body from its
    // REAL angular size (its true radius over its true distance) lifted by a gain into visibility and clamped to a
    // believable band, so a near sibling is a small disc, a far one shrinks to a star-like dot and the very distant ones
    // fade out. Everything a tuner would touch lives here; the drawn radius at the shell is SKY_BODY_SHELL_DIST * tan(
    // angularRadius), which is why these are angular RADII (half the disc), expressed in degrees for readability.
    //
    // The sun (drawn as a STAR, so drawStar multiplies the base radius by STAR_DRAW_SCALE and wraps it in a corona): a
    // warm disc a few degrees across from every planet, sized directly rather than from the ring distance so the "sun"
    // reads the same wherever you stand. Its base radius is derived from the target angular size and STAR_DRAW_SCALE so
    // changing either keeps the disc at the intended size.
    private static final double SKY_SUN_ANGULAR_DEG = 1.7;   // angular RADIUS: a ~3.4 degree sun disc, plus its corona
    private static final float SKY_SUN_RADIUS =
            (float) (SKY_BODY_SHELL_DIST * Math.tan(Math.toRadians(SKY_SUN_ANGULAR_DEG)) / STAR_DRAW_SCALE);
    private static final int SKY_SUN_TINT = PlanetPositions.SUN_TINT;
    // The synthetic surface day length, in ticks. MUST match PlanetSurfaceEffects.DAY_LENGTH_TICKS so the sun's elevation
    // and the dome brightness/star fade stay in phase (the sun is highest exactly when the dome is at full noon).
    private static final long SKY_DAY_LENGTH_TICKS = 24000L;
    // The sun's peak elevation above the horizon at noon, radians (~74 degrees). It falls to the negative of this at
    // midnight, so the sun is genuinely below the horizon at night, matching the dark dome.
    private static final double SKY_SUN_MAX_ELEVATION = 1.30D;
    // Below this drawn elevation the sun is not drawn (it has set): its bearing is below the horizon, where the terrain
    // covers it anyway, so drawing it only risks it peeking through a void gap.
    private static final double SKY_SUN_MIN_VISIBLE = -0.05D;

    // Sibling angular sizing: a body's apparent angular radius is its TRUE angular size (true radius / true distance)
    // lifted by SKY_SIBLING_GAIN into visibility, then clamped into a believable band. MIN keeps a far body a bright
    // dot (near star size, as the owner asked) rather than vanishing at its real sub-arc-minute size; MAX stops the
    // nearest neighbour from filling the sky (the whole point of the rescale). Angular RADII in degrees.
    private static final double SKY_SIBLING_GAIN = 5.0;
    private static final double SKY_SIBLING_MIN_ANGULAR_DEG = 0.15;   // farthest kept sibling: a ~0.3 degree dot
    private static final double SKY_SIBLING_MAX_ANGULAR_DEG = 1.0;    // nearest sibling: a ~2 degree small disc
    // Very distant bodies FADE by shrinking their drawn size to nothing across this band (blocks): below FADE_START a
    // body keeps its clamped size, from FADE_START to FADE_END it scales linearly to zero, past FADE_END it is skipped.
    // The known solar-system rings reach ~36k (Sacred Kai) and unknown bodies orbit further out, so this dims the far
    // outer system to faint dots and drops anything beyond the far edge rather than pinning it at the min size forever.
    private static final double SKY_SIBLING_FADE_START = 50000.0;
    private static final double SKY_SIBLING_FADE_END = 130000.0;
    // below this drawn radius (blocks at the shell) a faded body is not worth a draw call: sub-pixel, so skip it.
    private static final double SKY_SIBLING_MIN_DRAWN_RADIUS = 0.25;
    // SIBLING SKY ELEVATION. A system's planets (and the main system's fixed planets) all share ONE orbital plane, so from a
    // planet's surface a sibling's TRUE elevation is ~0: it sits on the horizon and the old ~13 degree horizon cull dropped
    // every one, which is why the owner "cannot see any other planets". A sibling is instead LIFTED to a stable synthetic
    // elevation (a per-id value in this band) so it reads as a body scattered across the sky, well clear of the treeline,
    // while its AZIMUTH stays the true horizontal bearing and drifts as the orbit advances (so it visibly moves). The band
    // floor keeps every sibling a few degrees clear of the horizon (the "float on the treetops" guard, now a few degrees not
    // 13), the ceiling keeps them below the zenith so they never stack on the sun.
    private static final double SKY_SIBLING_ELEV_MIN_DEG = 14.0;
    private static final double SKY_SIBLING_ELEV_MAX_DEG = 58.0;
    // How far from the planet to gather GENERATED sibling planets (fixed bodies are always considered, they are few),
    // and the most to draw, so a busy region never floods the sky. Widened past the old 12k so a neighbouring generated
    // world on the next ring is still a visible dot; the per-frame cost is bounded by SKY_SIBLING_MAX.
    private static final double SKY_SIBLING_RANGE = 60000.0;
    private static final int SKY_SIBLING_MAX = 16;

    // The DIRECTION to the sun for the planet at planetPos, at the given surface game time. Since B2 the sun is a REAL
    // central body at the layout centre (PlanetPositions.sunPosition()), so the bearing is the true horizontal direction
    // from this planet toward that body (toward the space origin). The ELEVATION follows the synthetic surface day/night
    // cycle: it peaks at SKY_SUN_MAX_ELEVATION at noon and drops to its negative at midnight, so the sun is above the
    // horizon by day and genuinely below it at night, in phase with the dome brightness and star fade (both keyed on the
    // SAME 24000-tick cycle, see PlanetSurfaceEffects). One method, one source of truth for where the sun is in the sky.
    private static Vec3 sunDirection(Vec3 planetPos, Vec3 sunCenter, double tickCount)
    {
        // horizontal bearing from this planet toward its OWN sun (the central sun for a main-system body, the system's own
        // star for a generated system planet), so a system planet's sky shows ITS star overhead, not the distant central one.
        double hx = sunCenter.x - planetPos.x;
        double hz = sunCenter.z - planetPos.z;
        double hlen = Math.sqrt(hx * hx + hz * hz);
        if (hlen < 1.0E-3)
        {
            // planet essentially at the layout centre: pick a stable default bearing.
            hx = 1.0;
            hz = 0.0;
            hlen = 1.0;
        }
        // day phase 0..1 (0 = midnight, 0.5 = noon), then sun height -1..1 rising to +1 at noon: this is exactly the
        // cosine PlanetSurfaceEffects.dayFactor uses, so the sun sits highest when the dome is brightest.
        double phase = (double) Math.floorMod((long) tickCount, SKY_DAY_LENGTH_TICKS) / (double) SKY_DAY_LENGTH_TICKS;
        double sunHeight = -Math.cos(phase * Math.PI * 2.0);
        // harness/debug override: pin the sun height to the forced day factor so a scripted daytime shot has the sun up
        // (0 = midnight, 1 = noon). Matches PlanetSurfaceEffects.dayFactor, which uses the same override.
        float dayOverride = SurfaceWeatherClient.dayFactorOverride();
        if (dayOverride >= 0.0F)
        {
            sunHeight = 2.0 * Math.min(1.0F, dayOverride) - 1.0;
        }
        double elev = SKY_SUN_MAX_ELEVATION * sunHeight;
        double ce = Math.cos(elev);
        double se = Math.sin(elev);
        return new Vec3(hx / hlen * ce, se, hz / hlen * ce);
    }

    // memoised system lookup for the surface the player is on, so the whole-universe scan (GeneratedSystems.systemForPlanetId)
    // runs only when the player changes planet, never per frame. Null value = a main-system fixed body or a lone legacy planet.
    private static String cachedSurfaceKey = null;
    private static GeneratedSystems.System cachedSurfaceSystem = null;

    private static GeneratedSystems.System surfaceSystemFor(String planetKey)
    {
        if (planetKey == null || planetKey.isEmpty() || !planetKey.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            cachedSurfaceKey = planetKey;
            cachedSurfaceSystem = null;
            return null;
        }
        if (planetKey.equals(cachedSurfaceKey))
        {
            return cachedSurfaceSystem;
        }
        cachedSurfaceKey = planetKey;
        cachedSurfaceSystem = GeneratedSystems.systemForPlanetId(null, planetKey);
        return cachedSurfaceSystem;
    }

    // draw the sun and the sibling planets over the per-planet surface sky. Uses the same depth discipline as the space
    // pass: bodies write their own depth, then the depth buffer is cleared so the terrain drawn afterwards paints over
    // them (they are sky, behind everything).
    private static void drawSurfaceSkyBodies(RenderLevelStageEvent event, Minecraft mc)
    {
        Vec3 planetPos = SurfaceSkyState.bodyPos();
        if (planetPos == null || mc.level == null)
        {
            return;
        }
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        double tickCount = (double) mc.level.getGameTime() + event.getPartialTick();

        RenderSystem.depthMask(true);
        try
        {
            String ownKey = SurfaceSkyState.planetKey();
            // which system is this planet part of? A generated system planet's sky must show ITS OWN sun and ITS OWN
            // siblings, not the central sun and whatever generated bodies happen to be near in space. Resolved from the
            // cached system grid and memoised per planet key (surfaceSystemFor), so the scan runs only when the player
            // changes planet, never per frame. Null means the main system (a fixed body) or a lone legacy planet.
            GeneratedSystems.System system = surfaceSystemFor(ownKey);
            long epoch = OrbitClock.epochMillis();

            // the anchor position of the planet we stand on, and the centre its sky revolves around (its sun).
            Vec3 anchor = planetPos;
            Vec3 sunCenter = PlanetPositions.sunPosition();
            if (system != null)
            {
                sunCenter = system.starPos;
                for (GeneratedSystems.SystemPlanet sp : system.planets)
                {
                    if (sp.id.equals(ownKey))
                    {
                        anchor = system.planetPositionAt(sp, epoch);   // live, so the sun bearing tracks the orbit.
                        break;
                    }
                }
            }

            Vec3 sunDir = sunDirection(anchor, sunCenter, tickCount);

            // THIS PLANET'S OWN RINGS, arcing across its sky. Drawn FIRST (behind the sun and siblings), lit by the sun
            // direction with the planet's own shadow cast across the band, from the shared ring parameters (PlanetRings)
            // so the ring the player saw from space is the same ring overhead. Only a ringed planet has any.
            if (PlanetRings.isRinged(ownKey))
            {
                drawOverheadRings(poseStack, buffer, ownKey, sunDir);
                buffer.endBatch();
            }

            // the sun (a star body plus corona), at the real bearing to the planet's own sun and the day-cycle elevation.
            // Skip it once it has set below the horizon (the terrain covers that part of the sky anyway). A system planet's
            // sun takes the system star's tint so its own star reads true; a main-system planet keeps the warm central tint.
            if (sunDir.y > SKY_SUN_MIN_VISIBLE)
            {
                int sunTint = system != null ? system.starTint : SKY_SUN_TINT;
                drawSkyBody(poseStack, buffer, Kind.STAR, sunDir, SKY_SUN_RADIUS, sunTint, null, tickCount);
            }

            if (system != null)
            {
                // SYSTEM SIBLINGS: the other planets of this planet's own system, at their live orbital positions. They share
                // the orbital plane, so their TRUE elevation from the surface is ~0; drawSkySibling lifts each to a stable
                // synthetic elevation so they read as bodies scattered across the sky rather than pinned to the horizon.
                for (GeneratedSystems.SystemPlanet sp : system.planets)
                {
                    if (sp.id.equals(ownKey))
                    {
                        continue;   // never draw ourselves in our own sky.
                    }
                    if (SpaceLayout.isDestroyed(null, sp.id))
                    {
                        continue;
                    }
                    Vec3 sibPos = system.planetPositionAt(sp, epoch);
                    drawSkySibling(poseStack, buffer, anchor, sibPos, sp.radius, sp.tint, sp.id, tickCount);
                }
            }
            else
            {
                // MAIN-SYSTEM or LEGACY planet: the fixed solar-system landmarks are the siblings (few, always considered),
                // plus any nearby legacy generated planet. They are coplanar too, so drawSkySibling lifts their elevation.
                for (FixedBody fb : SpaceLayout.fixedBodies(null))
                {
                    if (fb.position.distanceToSqr(anchor) < 1.0)
                    {
                        continue; // the planet we are standing on.
                    }
                    drawSkySibling(poseStack, buffer, anchor, fb.position, fb.radius, PlanetPositions.tint(fb.key),
                            fb.key, tickCount);
                }
                final Vec3 anchorRef = anchor;
                List<GeneratedPlanets.Generated> gens =
                        new ArrayList<>(GeneratedPlanets.generatedNear(null, anchorRef, SKY_SIBLING_RANGE));
                gens.sort((a, b) -> Double.compare(a.position.distanceToSqr(anchorRef),
                        b.position.distanceToSqr(anchorRef)));
                int drawn = 0;
                for (GeneratedPlanets.Generated g : gens)
                {
                    if (g.position.distanceToSqr(anchor) < 1.0)
                    {
                        continue; // ourselves.
                    }
                    drawSkySibling(poseStack, buffer, anchor, g.position, g.radius, g.tint, g.id, tickCount);
                    if (++drawn >= SKY_SIBLING_MAX)
                    {
                        break;
                    }
                }
            }
        }
        finally
        {
            // discard the sky bodies' depth so the terrain (drawn after AFTER_SKY) paints over them, exactly as the
            // space body pass does.
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.depthMask(true);
        }
    }

    // one sibling planet on the sky: its bearing from the planet we are on, at an angular size that grows with its true
    // radius and shrinks with distance (clamped), then drawn with the SAME drawPlanet path the space view uses.
    private static void drawSkySibling(PoseStack poseStack, MultiBufferSource.BufferSource buffer, Vec3 planetPos,
                                       Vec3 bodyPos, float bodyRadius, int tint, String key, double tickCount)
    {
        double dx = bodyPos.x - planetPos.x;
        double dy = bodyPos.y - planetPos.y;
        double dz = bodyPos.z - planetPos.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-3 || dist > SKY_SIBLING_FADE_END)
        {
            return;   // ourselves (guarded by the caller) or past the far edge of the fade: not drawn at all.
        }
        // true angular RADIUS of the body (radius over distance), lifted into visibility and clamped to the believable
        // band. This is the whole "planets are huge" fix: the drawn size now tracks real geometry instead of pinning to
        // a near-max disc.
        double angular = Math.atan(bodyRadius / dist) * SKY_SIBLING_GAIN;
        angular = Math.max(Math.toRadians(SKY_SIBLING_MIN_ANGULAR_DEG),
                Math.min(Math.toRadians(SKY_SIBLING_MAX_ANGULAR_DEG), angular));
        // fade very distant bodies by scaling the drawn size to zero across the fade band, so the far outer system dims
        // to faint dots rather than hanging at the min size.
        double fade = dist <= SKY_SIBLING_FADE_START ? 1.0
                : (SKY_SIBLING_FADE_END - dist) / (SKY_SIBLING_FADE_END - SKY_SIBLING_FADE_START);
        float radius = (float) (SKY_BODY_SHELL_DIST * Math.tan(angular) * Math.max(0.0, fade));
        if (radius < SKY_SIBLING_MIN_DRAWN_RADIUS)
        {
            return;   // faded to sub-pixel: not worth a draw call.
        }
        // AZIMUTH is the true horizontal bearing (so the body points the right way and drifts as it orbits); ELEVATION is a
        // stable synthetic lift, because siblings share the orbital plane and would otherwise all sit on the horizon and be
        // culled (the "cannot see any other planets" report). See SKY_SIBLING_ELEV_*.
        double hlen = Math.sqrt(dx * dx + dz * dz);
        if (hlen < 1.0E-3)
        {
            dx = 1.0;
            dz = 0.0;
            hlen = 1.0;   // directly overhead in space: pick a stable bearing.
        }
        double elevDeg = SKY_SIBLING_ELEV_MIN_DEG
                + skyElevFraction(key) * (SKY_SIBLING_ELEV_MAX_DEG - SKY_SIBLING_ELEV_MIN_DEG);
        double elev = Math.toRadians(elevDeg);
        double ce = Math.cos(elev);
        double se = Math.sin(elev);
        Vec3 dir = new Vec3(dx / hlen * ce, se, dz / hlen * ce);
        drawSkyBody(poseStack, buffer, Kind.PLANET, dir, radius, tint, key, tickCount);
    }

    // a stable 0..1 fraction from a sibling's key, used to scatter siblings across the sky's elevation band so they do not
    // all sit at one height. Deterministic per body, so a sibling keeps its height while its azimuth drifts with its orbit.
    private static double skyElevFraction(String key)
    {
        if (key == null)
        {
            return 0.5;
        }
        return (Math.abs(key.hashCode()) % 1000) / 1000.0;
    }

    // place a body at (direction * shell distance) with the camera at the origin and draw it through the shared drawBody,
    // so it reuses drawStar / drawPlanet for real body art on the sky. Flushed immediately, like each space body.
    private static void drawSkyBody(PoseStack poseStack, MultiBufferSource.BufferSource buffer, Kind kind, Vec3 dir,
                                    float radius, int tint, String key, double tickCount)
    {
        Vec3 pos = new Vec3(dir.x * SKY_BODY_SHELL_DIST, dir.y * SKY_BODY_SHELL_DIST, dir.z * SKY_BODY_SHELL_DIST);
        Body body = new Body(kind, pos, radius, tint, key);
        // a sky body is placed by direction and never orbits (orbit == null), so the epoch is irrelevant here.
        drawBody(poseStack, buffer, body, Vec3.ZERO, tickCount, 0L);
        buffer.endBatch();
    }

    // ==== The planet's OWN rings arcing across its sky (seen from the surface) ====
    //
    // A ringed planet's ring system is drawn as a broad banded BAND arcing all the way across the sky, from one horizon up
    // through near the zenith and down to the opposite horizon, leaned from the vertical by the planet's axial tilt (from
    // PlanetRings, the shared source of truth). It is built as a wide strip following a great circle centred on the
    // observer, so it fills the sky like the rings seen from inside a ring system rather than a small distant disc. The
    // real artist ring sheet is sampled ACROSS the band width, so its concentric ring structure reads as the bands. It is
    // lit on the sun-facing half and darkened toward the anti-sun half (the planet's shadow crossing the ring), and stays
    // softly lit at night so it is visible day and night.

    // segments along the great circle: enough for a smooth arc and a smooth lit/shadow gradient, few enough to stay a
    // small batch.
    private static final int SKY_RING_ARC_SEGMENTS = 96;
    // strips ACROSS the band width, so the band carries several soft procedural sub-bands of differing opacity with gaps,
    // rather than one hard edge.
    private static final int SKY_RING_ACROSS = 14;
    // the draw radius of the band on the sky shell. Comfortably inside the projection far plane; the pass clears depth
    // afterwards so terrain paints over the below-horizon half.
    private static final float SKY_RING_SHELL = 80.0F;
    // overall opacity multiplier for the whole ring (the per-sub-band profile is multiplied by this). Kept below 1 so the
    // ring is semi-transparent and the sky shows through it.
    private static final float SKY_RING_ALPHA = 0.85F;
    // a plain white sheet (drawn with entityTranslucentEmissive, which is alpha-blended and full-bright), so the ring's
    // colour and its soft banding come entirely from the per-vertex colour and alpha, not from a texture. This is what
    // removes the old hard-edged look and the sky showing through an annulus hole down the band's middle.
    private static final ResourceLocation SKY_RING_WHITE = STAR_BODY_TEXTURE;

    private static void drawOverheadRings(PoseStack poseStack, MultiBufferSource buffer, String key, Vec3 sunDir)
    {
        PlanetRings.Rings rings = PlanetRings.of(key);
        if (rings == null)
        {
            return;
        }
        // a stable per-key azimuth for the plane, and the lean of the arc's peak away from the zenith (the axial tilt,
        // 15..40 degrees). The band widens with the band count so a 3-band ring reads broader across the sky.
        double nodeAz = Math.toRadians((rings.bandSeed & 0xFFFFL) / 65535.0 * 360.0);
        double lean = Math.toRadians(rings.tiltDegrees);
        double halfWidth = 0.17 + 0.05 * rings.bands;   // radians of angular half-width across the band (broad)

        // the great-circle frame, in WORLD axes (the surface-sky pose is world-aligned with the camera at the origin):
        // hA and hB are two perpendicular horizontal directions set by the node azimuth, and axis is the vertical tipped
        // toward hB by the lean so the arc's peak leans off the zenith by the axial tilt.
        double[] hA = { Math.cos(nodeAz), 0.0, Math.sin(nodeAz) };
        double[] hB = { -Math.sin(nodeAz), 0.0, Math.cos(nodeAz) };
        double[] axis = { hB[0] * Math.sin(lean), Math.cos(lean), hB[2] * Math.sin(lean) };
        double an = Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
        axis[0] /= an;
        axis[1] /= an;
        axis[2] /= an;

        PoseStack.Pose pose = poseStack.last();
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(SKY_RING_WHITE));
        for (int i = 0; i < SKY_RING_ARC_SEGMENTS; ++i)
        {
            double t0 = (double) i / SKY_RING_ARC_SEGMENTS * Math.PI * 2.0;
            double t1 = (double) (i + 1) / SKY_RING_ARC_SEGMENTS * Math.PI * 2.0;
            for (int j = 0; j < SKY_RING_ACROSS; ++j)
            {
                double f0 = (double) j / SKY_RING_ACROSS;
                double f1 = (double) (j + 1) / SKY_RING_ACROSS;
                emitRingVertex(pose, vc, axis, hA, hB, t0, f0, halfWidth, sunDir);
                emitRingVertex(pose, vc, axis, hA, hB, t0, f1, halfWidth, sunDir);
                emitRingVertex(pose, vc, axis, hA, hB, t1, f1, halfWidth, sunDir);
                emitRingVertex(pose, vc, axis, hA, hB, t1, f0, halfWidth, sunDir);
            }
        }
    }

    // a unit direction on the ring band: along the great circle by angle t (in the axis/hA plane) and offset out of that
    // plane by w along hB to give the band its width. Normalised so every vertex sits on the sky shell.
    private static double[] ringDir(double[] axis, double[] hA, double[] hB, double t, double w)
    {
        double ct = Math.cos(t);
        double st = Math.sin(t);
        double x = axis[0] * ct + hA[0] * st + hB[0] * w;
        double y = axis[1] * ct + hA[1] * st + hB[1] * w;
        double z = axis[2] * ct + hA[2] * st + hB[2] * w;
        double n = Math.sqrt(x * x + y * y + z * z);
        return new double[] { x / n, y / n, z / n };
    }

    // emit one ring-band vertex at arc angle t and across-fraction f (0 = inner edge, 1 = outer edge). Colour is a gentle
    // dust tint that eases across the band, dimmed smoothly toward the anti-sun side (the planet's shadow crossing the
    // ring); alpha is the soft sub-band profile times a horizon fade so the band fades out toward the horizon.
    private static void emitRingVertex(PoseStack.Pose pose, VertexConsumer vc, double[] axis, double[] hA, double[] hB,
                                       double t, double f, double halfWidth, Vec3 sunDir)
    {
        double w = (f * 2.0 - 1.0) * halfWidth;
        double[] d = ringDir(axis, hA, hB, t, w);
        double sd = d[0] * sunDir.x + d[1] * sunDir.y + d[2] * sunDir.z;
        // smooth lit/shadow: bright on the sun side, dimmer (never black) on the far side; floor keeps a night ring pale.
        float bright = 0.55F + 0.45F * smooth01(-0.5F, 0.7F, (float) sd);
        float[] tint = ringBandTint((float) f);
        float alpha = ringBandProfile((float) f) * horizonFade((float) d[1]) * SKY_RING_ALPHA;
        vc.vertex(pose.pose(), (float) (d[0] * SKY_RING_SHELL), (float) (d[1] * SKY_RING_SHELL),
                        (float) (d[2] * SKY_RING_SHELL))
                .color(tint[0] * bright, tint[1] * bright, tint[2] * bright, alpha)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    // the across-band opacity profile: several soft sub-bands of differing opacity with transparent gaps between them, so
    // the ring reads as banded ring dust rather than a solid strip. Pure function of the across-fraction f in 0..1.
    private static float ringBandProfile(float f)
    {
        float a = 0.0F;
        a = Math.max(a, 0.58F * bump(f, 0.12F, 0.07F));
        a = Math.max(a, 0.34F * bump(f, 0.30F, 0.05F));
        a = Math.max(a, 0.62F * bump(f, 0.48F, 0.08F));
        a = Math.max(a, 0.24F * bump(f, 0.64F, 0.045F));
        a = Math.max(a, 0.48F * bump(f, 0.82F, 0.07F));
        return a;
    }

    // a soft bump (0..1) centred at c with half-width hw, so sub-band edges are smooth (no hard steps).
    private static float bump(float x, float c, float hw)
    {
        float d = (x - c) / hw;
        float v = 1.0F - d * d;
        return v <= 0.0F ? 0.0F : v * v;
    }

    // gentle dust tints that ease across the band: warm tan on the inner side, neutral grey in the middle, cool ice on
    // the outer side. All pale and below 1 so nothing clips to a hard colour.
    private static float[] ringBandTint(float f)
    {
        float[] tan = { 0.86F, 0.80F, 0.66F };
        float[] grey = { 0.80F, 0.82F, 0.85F };
        float[] ice = { 0.80F, 0.88F, 0.96F };
        if (f < 0.5F)
        {
            float u = f / 0.5F;
            return new float[] { lerp(tan[0], grey[0], u), lerp(tan[1], grey[1], u), lerp(tan[2], grey[2], u) };
        }
        float u = (f - 0.5F) / 0.5F;
        return new float[] { lerp(grey[0], ice[0], u), lerp(grey[1], ice[1], u), lerp(grey[2], ice[2], u) };
    }

    // fade the band out as it nears the horizon (dir.y toward 0), so it does not end in a hard line at the horizon.
    private static float horizonFade(float dirY)
    {
        return smooth01(0.02F, 0.22F, dirY);
    }

    private static float smooth01(float edge0, float edge1, float x)
    {
        float t = Math.max(0.0F, Math.min(1.0F, (x - edge0) / (edge1 - edge0)));
        return t * t * (3.0F - 2.0F * t);
    }

    private static float lerp(float a, float b, float t)
    {
        return a + (b - a) * t;
    }

    // hand the nearest cached black hole's drawn (shell-mapped) position and radius to the lensing pass. Nearest only:
    // one lens per frame, never one per hole (BlackHoleLensing). ensureStatus runs unconditionally so the enabled/disabled
    // diagnostic logs the first time the player is in space, even on a frame with no visible hole.
    private static void runLensing(RenderLevelStageEvent event, Vec3 cam)
    {
        BlackHoleLensing.ensureStatus();
        if (!BlackHoleLensing.isActive())
        {
            return;
        }
        Body nearest = null;
        double best = Double.MAX_VALUE;
        for (Body body : cache)
        {
            if (body.kind != Kind.BLACK_HOLE)
            {
                continue;
            }
            double d = body.pos.distanceToSqr(cam);
            if (d < best)
            {
                best = d;
                nearest = body;
            }
        }
        if (nearest == null)
        {
            return;
        }
        double dx = nearest.pos.x - cam.x;
        double dy = nearest.pos.y - cam.y;
        double dz = nearest.pos.z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4)
        {
            return;
        }
        // the SAME shell depth map the body pass uses, so the lens centre and radius land on the drawn sphere.
        double drawScale = shellDrawScale(dist);
        BlackHoleLensing.render(event, dx * drawScale, dy * drawScale, dz * drawScale,
                (float) (nearest.radius * drawScale));
    }

    // one-shot-per-reload log of the depth policy in force, so an "inside out" report is diagnosable from the log: names
    // the opaque body type (writes depth, culls) and the translucent shell type (tests, does not write).
    private static volatile boolean depthPolicyLogged = false;

    private static void logDepthPolicy()
    {
        if (depthPolicyLogged)
        {
            return;
        }
        depthPolicyLogged = true;
        LOGGER.info("[SU] space depth policy: opaque body=entityCutout(cull+LEQUAL+DEPTH_WRITE), shells=COLOR_WRITE-only "
                + "(LEQUAL test, no write), bodies back-to-front, FULL depth precision for the pass, then the depth buffer "
                + "is cleared in the finally. A solid body writes its own depth, so it cannot render inside-out; the "
                + "rim/cloud/ring shells LEQUAL-test against that committed depth; and the end-of-pass depth clear discards "
                + "the compressed body depth so terrain and DH LODs draw over the bodies with no depth ties.");
    }

    // one-shot log of the shell depth MAP: the nearest and farthest drawn body with both true camera distance and the
    // drawn radial distance shellDrawScale maps it onto, proving far bodies sit at greater drawn depth than near ones.
    private static volatile boolean depthBandLogged = false;

    private static void logDepthBand(List<Body> ordered, Vec3 cam)
    {
        if (depthBandLogged || ordered.isEmpty())
        {
            return;
        }
        depthBandLogged = true;
        // ordered is back to front: last entry nearest, first farthest.
        Body far = ordered.get(0);
        Body near = ordered.get(ordered.size() - 1);
        double farDist = Math.sqrt(far.pos.distanceToSqr(cam));
        double nearDist = Math.sqrt(near.pos.distanceToSqr(cam));
        LOGGER.info("[SU] space depth band: {} bodies, map=SHELL*d/(d+SHELL) with SHELL={}. nearest true={} -> drawn={}, "
                        + "farthest true={} -> drawn={}. Drawn depth rises with true distance, so nearer bodies occlude "
                        + "farther ones and cannot intersect.",
                ordered.size(), (long) SHELL, (long) nearDist, (long) (nearDist * shellDrawScale(nearDist)),
                (long) farDist, (long) (farDist * shellDrawScale(farDist)));
    }

    /**
     * A rotation angle in degrees from an absolute tick count, wrapped into 0..360 before narrowing to a float.
     *
     * <p>The wrap is load-bearing: at 41,500,000 ticks a quarter-degree-per-tick body has turned over ten million
     * degrees, and a float that large resolves to ~one degree, so the spin would still step visibly even fed a double.
     * The modulus keeps the value where a float has precision to spare, and a rotation is periodic so the wrap is unseen.
     */
    private static float spinDeg(double tickCount, float degPerTick)
    {
        double deg = (tickCount * degPerTick) % 360.0;
        if (deg < 0.0)
        {
            deg += 360.0;
        }
        return (float) deg;
    }

    // 0 when this body has no destruction sequence, rising to 1 as the red ramp completes. A straight clamp with NO easing
    // of its own: PlanetDoomEffects.rampFactor already applied the cubic ease-in, and easing again compounded to
    // progress^6 (held normal colour for ~85% of the ramp, then snapped red). One ease, owned by rampFactor.
    private static float doomFactor(String planetKey, double tickCount)
    {
        if (planetKey == null || planetKey.isEmpty())
        {
            return 0.0F;
        }
        double t = PlanetDoomEffects.rampFactor(planetKey, tickCount);
        if (t <= 0.0)
        {
            return 0.0F;
        }
        return (float) Math.min(1.0, t);
    }

    // draw the debris of every planet inside its shatter window. Shards are a pure function of the planet id, so every
    // client sees the same break-up. Batched per planet, flushed like the body loop.
    private static void drawShatters(PoseStack poseStack, MultiBufferSource.BufferSource buffer, Vec3 cam,
                                     double tickCount)
    {
        List<PlanetDoomEffects.Shatter> shatters = PlanetDoomEffects.activeShatters(tickCount);
        if (shatters.isEmpty())
        {
            return;
        }
        for (PlanetDoomEffects.Shatter shatter : shatters)
        {
            drawShatter(poseStack, buffer, shatter, cam);
            buffer.endBatch();
        }
    }

    // one planet's debris: SHARD_COUNT small cubes of its texture, each pushed out along its hashed direction at its hashed
    // speed, spinning, shrinking and fading as the window runs out. Uses the same far-plane shell projection as the bodies
    // so distant debris stays where the planet was.
    private static void drawShatter(PoseStack poseStack, MultiBufferSource buffer, PlanetDoomEffects.Shatter shatter,
                                    Vec3 cam)
    {
        double dx = shatter.pos().x - cam.x;
        double dy = shatter.pos().y - cam.y;
        double dz = shatter.pos().z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4)
        {
            return;
        }
        // POSITION and SIZE both use the SAME shellDrawScale as the body pass, so debris matches the scale and view depth
        // the planet drew at just before it died.
        double drawScale = shellDrawScale(dist);
        double drawX = dx * drawScale;
        double drawY = dy * drawScale;
        double drawZ = dz * drawScale;
        float drawRadius = (float) (shatter.radius() * drawScale);

        float progress = (float) Math.max(0.0, Math.min(1.0, shatter.progress()));
        float alpha = 1.0F - progress;
        if (alpha <= 0.0F)
        {
            return;
        }

        Resolved resolved = resolvePlanet(shatter.planetId());
        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        if (resolved.tinted())
        {
            r = ((shatter.tint() >> 16) & 0xFF) / 255.0F;
            g = ((shatter.tint() >> 8) & 0xFF) / 255.0F;
            b = (shatter.tint() & 0xFF) / 255.0F;
        }
        // the ramp ended at full red, so shards START red and cool toward the planet's colour as they fly, so the ramp
        // and break-up read as one event rather than a colour pop when the planet dies.
        float heat = 1.0F - progress;
        r = r + (1.0F - r) * heat;
        g = g * (1.0F - heat);
        b = b * (1.0F - heat);

        // FLASH: the detonation. A stack of camera-facing emissive halo billboards at the planet that punch out bright and
        // fade fast over the first part of the window (shards alone just read as a planet quietly coming apart). Same halo
        // sheet and orientation as the star corona. Vanilla explosion particles are NOT used: their render distance is far
        // shorter than these draw distances, so they would be invisible in exactly the case this exists for.
        float flash = 1.0F - Math.min(1.0F, progress / FLASH_WINDOW);
        if (flash > 0.0F)
        {
            float ease = flash * flash;
            float grow = drawRadius * (1.0F + FLASH_GROWTH * (1.0F - flash));
            var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
            poseStack.pushPose();
            poseStack.translate(drawX, drawY, drawZ);
            poseStack.mulPose(orientation);
            VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_HALO_TEXTURE));
            // white-hot core inside a wider orange bloom.
            drawColouredDisc(poseStack.last(), halo, grow * 3.0F, 1.0F, 0.45F, 0.12F, 0.75F * ease, FULL_BRIGHT);
            drawColouredDisc(poseStack.last(), halo, grow * 1.8F, 1.0F, 0.85F, 0.55F, 0.95F * ease, FULL_BRIGHT);
            drawColouredDisc(poseStack.last(), halo, grow, 1.0F, 1.0F, 1.0F, ease, FULL_BRIGHT);
            poseStack.popPose();
        }

        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(resolved.texture()));
        for (int i = 0; i < SHARD_COUNT; ++i)
        {
            // Each component gets its OWN full-width hash. Do NOT slice windows out of one hash by shifting (h >>> 21,
            // >>> 42, ...): shardUnit already reads the TOP 24 bits, so a pre-shifted value collapses to a near-constant,
            // which pinned elevation at ~-1 (every shard fell straight down) and pinned every shard to one speed.
            long hAzim = shardHash(shatter.planetId(), i, SALT_AZIMUTH);
            long hElev = shardHash(shatter.planetId(), i, SALT_ELEVATION);
            long hSpeed = shardHash(shatter.planetId(), i, SALT_SPEED);
            long hSpin = shardHash(shatter.planetId(), i, SALT_SPIN);

            // unit direction: azimuth around Y plus a COSINE-uniform elevation, so shards spray evenly over the sphere
            // instead of bunching at the poles the way a naive pitch/yaw pair would.
            double azimuth = shardUnit(hAzim) * Math.PI * 2.0;
            double cosEl = shardUnit(hElev) * 2.0 - 1.0;
            double sinEl = Math.sqrt(Math.max(0.0, 1.0 - cosEl * cosEl));
            double ux = Math.cos(azimuth) * sinEl;
            double uy = cosEl;
            double uz = Math.sin(azimuth) * sinEl;

            // per-shard speed so the cloud spreads unevenly, not as one expanding shell.
            double speed = 0.55 + shardUnit(hSpeed) * 0.9;
            double travel = drawRadius * SHARD_SPREAD * progress * speed;
            // shards shrink as they fly, so the field thins out.
            float size = drawRadius * SHARD_SIZE * (1.0F - progress * 0.6F);
            if (size <= 0.0F)
            {
                continue;
            }
            float spin = (float) (shardUnit(hSpin) * 360.0 + progress * 540.0 * speed);

            poseStack.pushPose();
            poseStack.translate(drawX + ux * travel, drawY + uy * travel, drawZ + uz * travel);
            poseStack.mulPose(Axis.YP.rotationDegrees(spin));
            poseStack.mulPose(Axis.XP.rotationDegrees(spin * 0.6F));
            drawBoxCubeAlpha(poseStack.last(), vc, size, r, g, b, alpha, FULL_BRIGHT, PLANET_BOX_UV);
            poseStack.popPose();
        }
    }

    // splitmix64-style hash of a planet id and shard index, hashed locally (like gasGiantColour) rather than reaching into
    // the server derivation: presentation only, nothing has to agree with a server value. Integer arithmetic throughout,
    // so every client derives identical shards.
    private static long shardHash(String planetId, int index, int salt)
    {
        long z = planetId.hashCode() * 0x9E3779B97F4A7C15L
                ^ (index * 0xC2B2AE3D27D4EB4FL)
                ^ (salt * 0x165667B19E3779F9L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // 0..1 from a hash's top 24-bit window, the same extraction the space derivations use, so shard values are stable and
    // JVM-independent.
    private static double shardUnit(long h)
    {
        return ((h >>> 24) & 0xFFFFFFL) / 16777216.0;
    }

    // rebuild the near-set if enough time has passed or the camera has moved far. The ONLY place the expensive cell walks
    // run; per-frame cost is just drawing the cached list.
    private static void refreshIfNeeded(Vec3 cam)
    {
        long now = System.currentTimeMillis();
        boolean moved = cacheOrigin == null || cacheOrigin.distanceToSqr(cam) > REFRESH_MOVE * REFRESH_MOVE;
        if (!moved && now - lastRefresh < REFRESH_INTERVAL_MS)
        {
            return;
        }
        lastRefresh = now;
        cacheOrigin = cam;
        cache = deriveNear(cam);
    }

    // derive every body within the synced draw distance. server == null on the client, so each derivation reads the synced
    // fixed-body set and config via SpaceLayout, giving the same set the server has.
    private static List<Body> deriveNear(Vec3 cam)
    {
        double range = SpaceLayout.clientDrawDistance();
        List<Body> out = new ArrayList<>();

        // fixed planets: from the synced snapshot, on their OWN much larger distance (FIXED_BODY_DRAW_DISTANCE) not the
        // general bodyDrawDistance, so main planets across the wide rings still read as landmarks. Only a handful, so the
        // wider scan is free.
        Vec3 sunPos = PlanetPositions.sunPosition();
        double fixedRangeSq = FIXED_BODY_DRAW_DISTANCE * FIXED_BODY_DRAW_DISTANCE;
        for (FixedBody fb : SpaceLayout.fixedBodies(null))
        {
            if (fb.position.distanceToSqr(cam) <= fixedRangeSq)
            {
                // a fixed main planet ORBITS the central sun: carry its orbital constants so it is redrawn smoothly every
                // frame from the shared clock, not snapped to the ~1s cache snapshot.
                OrbitParams orbit = new OrbitParams(sunPos.x, sunPos.y, sunPos.z,
                        PlanetPositions.orbitRadiusOf(fb.key), PlanetPositions.orbitPhase0Of(fb.key), 0.0, 0.0);
                out.add(new Body(Kind.PLANET, fb.position, fb.radius, PlanetPositions.tint(fb.key), fb.key, orbit));
            }
        }

        // B2: the central SUN, drawn as a large bright star with a corona (Kind.STAR reuses drawStar / its glow). Always
        // considered on the fixed distance so it reads as a landmark from anywhere in the system, never landable (it is
        // never a fixed body, so travel/landing cannot target it), and hazardous separately in SpaceHazardModule.
        Vec3 sun = PlanetPositions.sunPosition();
        if (sun.distanceToSqr(cam) <= fixedRangeSq)
        {
            out.add(new Body(Kind.STAR, sun, PlanetPositions.SUN_RADIUS, PlanetPositions.SUN_TINT, PlanetPositions.SUN_KEY));
        }

        // generated SYSTEM planets: carry live orbital constants (their sun centre, ring radius, start phase) so they orbit
        // their own sun smoothly every frame instead of snapping to the ~1s cache snapshot. Gathered straight from the system
        // grid so the orbit centre is the planet's OWN sun; the ids are collected so the legacy pass below can skip them.
        long epochNow = OrbitClock.epochMillis();
        double rangeSq = range * range;
        java.util.Set<String> systemPlanetIds = new java.util.HashSet<>();
        for (GeneratedSystems.System sys : GeneratedSystems.systemsNear(null, cam, range))
        {
            for (GeneratedSystems.SystemPlanet p : sys.planets)
            {
                Vec3 pos = sys.planetPositionAt(p, epochNow);
                if (pos.distanceToSqr(cam) > rangeSq)
                {
                    continue;
                }
                systemPlanetIds.add(p.id);
                OrbitParams orbit = new OrbitParams(sys.starPos.x, sys.starPos.y, sys.starPos.z, p.ringRadius, p.phase0,
                        sys.inclination, sys.nodeAngle);
                out.add(new Body(Kind.PLANET, pos, p.radius, p.tint, p.id, orbit));
            }
        }

        // LEGACY generated planets (old-scheme, claimed/stamped, UNMOVING): static, so drawn at their fixed position. Skip any
        // id already added as a live system planet above (generatedNear unions system planets in, which we must not double
        // draw as static).
        for (GeneratedPlanets.Generated g : GeneratedPlanets.generatedNear(null, cam, range))
        {
            if (systemPlanetIds.contains(g.id))
            {
                continue;
            }
            out.add(new Body(Kind.PLANET, g.position, g.radius, g.tint, g.id));
        }

        // stars: tint is the hashed stellar-class colour, drawn as the whole solid body. These are the system suns WITHIN
        // the near range, drawn full (drawStar).
        double nearSq = range * range;
        for (StarPositions.Star s : StarPositions.starsNear(null, cam, range))
        {
            out.add(new Body(Kind.STAR, s.position, s.radius, s.tint, null));
        }

        // DISTANT SYSTEM SUNS: every OTHER system's sun, drawn as a cheap far beacon so space is never empty. Enumerate the
        // cached deterministic system grid (no per-frame cell walking), take suns beyond the near range (the ones above are
        // already full-drawn) and within the universe limit, skip destroyed ones, then keep the nearest MAX_FAR_SUNS. This
        // is a client-only visual: it spawns nothing and touches no server state.
        double farLimitSq = SYSTEM_SUN_DRAW_DISTANCE * SYSTEM_SUN_DRAW_DISTANCE;
        List<GeneratedSystems.System> farSystems = new ArrayList<>();
        for (GeneratedSystems.System sys : GeneratedSystems.allSystems(null))
        {
            double dSq = sys.starPos.distanceToSqr(cam);
            if (dSq <= nearSq || dSq > farLimitSq)
            {
                continue;   // within near range (already full-drawn) or past the universe limit.
            }
            if (SpaceLayout.isDestroyed(null, sys.starKey))
            {
                continue;   // a destroyed sun is off the beacon field, exactly as it is off the near draw and the map.
            }
            farSystems.add(sys);
        }
        // nearest first, so the sun cap and the "which systems get planets" tier both keep the closest, most legible
        // systems. One sort over the far set (a few hundred at most), not per frame (deriveNear runs on the ~1s refresh).
        farSystems.sort((a, b) -> Double.compare(a.starPos.distanceToSqr(cam), b.starPos.distanceToSqr(cam)));
        long epochFar = OrbitClock.epochMillis();
        int sunCount = 0;
        for (GeneratedSystems.System sys : farSystems)
        {
            if (sunCount >= MAX_FAR_SUNS)
            {
                break;
            }
            out.add(new Body(Kind.FAR_SUN, sys.starPos, sys.starRadius, sys.starTint, sys.starKey));
            // the nearest few far systems also draw their PLANETS as small lit points at their live orbital positions, so
            // a distant system reads as a sun WITH planets, not a lone glowing ball. A destroyed planet is suppressed at
            // the source (the same synced set the near draw and the map use).
            if (sunCount < MAX_FAR_SYSTEMS_WITH_PLANETS)
            {
                for (GeneratedSystems.SystemPlanet p : sys.planets)
                {
                    if (SpaceLayout.isDestroyed(null, p.id))
                    {
                        continue;
                    }
                    Vec3 pp = sys.planetPositionAt(p, epochFar);
                    // a far system's sun is beyond the near range, but a planet on its near side can still fall INSIDE it,
                    // where the near pass above already drew it as a full cube. Skip those, so a planet is never drawn as
                    // both a near cube and a far dot.
                    if (pp.distanceToSqr(cam) <= nearSq)
                    {
                        continue;
                    }
                    out.add(new Body(Kind.FAR_PLANET, pp, p.radius, p.tint, p.id));
                }
            }
            sunCount++;
        }

        // black holes: no tint (the disc texture carries its own black/rim).
        for (BlackHolePositions.BlackHole h : BlackHolePositions.blackHolesNear(null, cam, range))
        {
            out.add(new Body(Kind.BLACK_HOLE, h.position, h.radius, 0, null));
        }

        // the seven super bodies: from the SYNCED snapshot, because their positions are authoritative server state now (a
        // body relocates when its ball is lost) and can no longer be derived. They cull on their OWN shorter distance
        // (clientSuperRenderDistance, default 1000): only drawn when the player is almost on top, radar leads you there.
        // The claimed flag rides the tint slot (1 = claimed, 0 = not), which drawSuper reads for grey vs dragon ball.
        double superRange = Math.min(range, SpaceLayout.clientSuperRenderDistance());
        double superRangeSq = superRange * superRange;
        float superRadius = SuperPlanetPositions.radius();
        for (net.shurui.shuruisutilities.space.PacketSpaceLayoutSync.SuperBody sb : SpaceLayout.clientSuperBodies())
        {
            if (sb.pos().distanceToSqr(cam) <= superRangeSq)
            {
                out.add(new Body(Kind.SUPER, sb.pos(), superRadius, sb.claimed() ? 1 : 0, sb.id()));
            }
        }
        return out;
    }

    // The one map from a body's TRUE camera distance to the single scale applied to BOTH its drawn position and its drawn
    // size. It returns SHELL / (dist + SHELL), so the drawn radial distance is dist * scale = SHELL * dist / (dist + SHELL):
    // a strictly INCREASING, bounded map of [0, inf) onto [0, SHELL). Two properties matter and both hold:
    //   1. APPEARANCE IS UNCHANGED. Position and size share this one scale, and a perspective projection sends k*V to the
    //      same screen pixel as V for any k > 0, so a body's silhouette (its apparent size and screen position) is
    //      identical to drawing it at its true distance and true radius. Only its view-space DEPTH changes. This is why
    //      the fix does not touch sizing, which the user confirmed is correct.
    //   2. DEPTH ORDER FOLLOWS TRUE DISTANCE. The drawn radial distance is strictly increasing in true distance and stays
    //      below SHELL (the proven-safe shell radius, well inside the projection far plane at any render distance), so a
    //      nearer body is genuinely nearer in the depth buffer and a farther one genuinely farther. Two bodies that
    //      overlap on screen lie on nearly the same ray, so their view depth ratio equals their drawn-radial-distance
    //      ratio and the nearer one wins the depth test cleanly. This REPLACES the old branch (true position inside SHELL,
    //      a single collapsed shell outside it) that drew every far body at the identical shell depth, which is what let
    //      bodies thousands of blocks apart intersect once the pass began writing depth.
    private static double shellDrawScale(double dist)
    {
        return SHELL / (dist + SHELL);
    }

    // draw one body at its camera-relative position, applying the shell depth map so a distant body is never clipped and
    // nearer bodies sit genuinely nearer in the depth buffer. Direction is always the true direction to the body; only the
    // drawn distance and size are scaled (by the same factor), which preserves angular size and screen position.
    private static void drawBody(PoseStack poseStack, MultiBufferSource buffer, Body body, Vec3 cam, double tickCount,
                                 long epoch)
    {
        Vec3 live = body.livePos(epoch);
        double dx = live.x - cam.x;
        double dy = live.y - cam.y;
        double dz = live.z - cam.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 1.0E-4)
        {
            return;
        }

        // POSITION and SIZE both use the SAME scale, so the body's angular size and screen position are exactly preserved
        // (see shellDrawScale): every drawn cube vertex is the true vertex times drawScale, and a perspective projection
        // maps k*V to the same screen point as V, so the silhouette is pixel-identical whatever drawScale is. drawScale is
        // now a strictly DECREASING function of true distance (shellDrawScale), so a nearer body is drawn at a genuinely
        // nearer view depth than a farther one. That is the whole fix for the "far bodies intersect nearer ones" defect:
        // when bodies write depth (they always did, through the cutout type), two overlapping bodies now sort by TRUE
        // distance instead of colliding at one shared shell depth.
        double drawScale = shellDrawScale(dist);
        double drawX = dx * drawScale;
        double drawY = dy * drawScale;
        double drawZ = dz * drawScale;
        float drawRadius = (float) (body.radius * drawScale);

        // gate the nameplate on distance to the body's SURFACE (true centre distance minus its true radius), so a label
        // only shows once the player is genuinely near, and appears at the same visual closeness for a big or small body.
        boolean showLabel = dist - body.radius <= SpaceLayout.LABEL_DISTANCE;

        poseStack.pushPose();
        poseStack.translate(drawX, drawY, drawZ);
        switch (body.kind)
        {
            case PLANET -> drawPlanet(poseStack, buffer, body, drawRadius, tickCount, showLabel);
            case STAR -> drawStar(poseStack, buffer, body, drawRadius, tickCount);
            case BLACK_HOLE -> drawBlackHole(poseStack, buffer, drawRadius, tickCount);
            case SUPER -> drawSuper(poseStack, buffer, body, drawRadius, tickCount, showLabel);
            case FAR_SUN -> drawFarSun(poseStack, buffer, body, drawRadius);
            case FAR_PLANET -> drawFarPlanet(poseStack, buffer, body, drawRadius);
        }
        poseStack.popPose();
    }

    //
    // Every planet and star is now drawn from the Blockbench "solar system" pack (assets/shuruisutilities/models +
    // textures/solar_system_pack). The models are registered as ADDITIONAL models (see PackModels), so vanilla bakes them
    // and stitches their textures into the BLOCK ATLAS; that is what animates them for free: an animated sheet's .mcmeta
    // is ticked by the atlas every client tick, and because a baked quad's UVs point at a fixed atlas slot whose pixels
    // are what change, the model animates with no per-frame work here (the orbiting moon on the earth model, the sun's
    // flares, and so on). We do NOT re-bake or re-parse geometry per frame: we pull the BakedModel's quads (cached) and
    // stamp them into the same painter's-algorithm pass the rest of this renderer uses, with our own spin, our own tilt,
    // a per-body colour multiply (star tint / doom reddening) and a normalise-then-scale that pins the model's CORE body
    // to the body radius so the drawn size (and thus the landing volume) is exactly what it was before.
    private static ResourceLocation packModel(String name)
    {
        return new ResourceLocation(ShuruisUtilities.MODID, "solar_system_pack/" + name);
    }

    private static final ResourceLocation PACK_EARTH = packModel("earth");
    // the grayscale Earth: the SAME geometry/UV as PACK_EARTH but textured from a grayscale copy of the earth sheet, so
    // it can be multiplied by a per-body colour (see packBaseTint) and still show Earth's continent/cloud detail as
    // luminance. Every fixed body EXCEPT Earth draws this, which keeps one geometry (identical normalise, scale and
    // landing volume) across all of them. It intentionally has NO emissive layer: earth.json references only the base
    // earth sheet (no earth_e), so this variant inherits none of Earth's city lights, which would look wrong recoloured.
    private static final ResourceLocation PACK_EARTH_GRAYSCALE = packModel("earth_grayscale");
    private static final ResourceLocation PACK_SUN = packModel("sun");
    private static final ResourceLocation PACK_MERCURY = packModel("mercury");
    private static final ResourceLocation PACK_VENUS = packModel("venus");
    private static final ResourceLocation PACK_MARS = packModel("mars");
    private static final ResourceLocation PACK_JUPITER = packModel("jupiter");
    private static final ResourceLocation PACK_SATURN = packModel("saturn");
    private static final ResourceLocation PACK_URANUS = packModel("uranus");
    // Not a model on disk: a sprite-cache key for the RING-PLANE subset of the uranus model's quads, which is drawn on
    // its own and so needs a cache slot that cannot collide with the full model's. Kept a legal ResourceLocation path
    // (no '#') because the constructor rejects anything else, and never handed to the model manager.
    private static final ResourceLocation PACK_URANUS_RING_PLANE = packModel("uranus_ring_plane_quads");
    private static final ResourceLocation PACK_NEPTUNE = packModel("neptune");
    // Planet Vegeta's DEDICATED colour sheet: the same cube geometry and box-UV net as the mars pack model, but pointed at
    // its own blue-and-brown horizontally striped texture (textures/solar_system_pack/vegeta). It is a FULL COLOUR sheet
    // drawn UNTINTED (white multiply), like Earth's own colour sheet, because stripes are a two-colour PATTERN that a single
    // flat RGB multiply over a grayscale sheet can never reproduce. Only Planet Vegeta ever draws this model (special-cased
    // in drawFixedBody), so no other body is affected.
    private static final ResourceLocation PACK_VEGETA = packModel("vegeta");

    // GRAYSCALE pack model variants: the SAME geometry and UV as the colour models above, but each references a grayscale
    // copy of its texture (models/solar_system_pack/<name>_grayscale.json -> textures/solar_system_pack/<name>_grayscale),
    // so drawPackModel's per-quad colour multiply recolours them to any planet-like or stellar tint while the sheet's own
    // surface detail survives as luminance. Every drawn planet body and every star now uses one of these instead of a
    // colour sheet: generated planets pick from the plain/ringed grayscale pool, main (non-Earth) fixed planets pick from
    // the grayscale set, and a star is the grayscale sun tinted to its stellar colour (its flares inherit that same tint
    // because they are quads of the same model, so a red star gets a red corona, never a mismatched yellow one). The sun
    // grayscale sheet and its four flare sheets carry the colour sheets' animation .mcmeta so the flares still animate.
    private static final ResourceLocation PACK_SUN_GRAYSCALE = packModel("sun_grayscale");
    private static final ResourceLocation PACK_MERCURY_GRAYSCALE = packModel("mercury_grayscale");
    private static final ResourceLocation PACK_VENUS_GRAYSCALE = packModel("venus_grayscale");
    private static final ResourceLocation PACK_MARS_GRAYSCALE = packModel("mars_grayscale");
    private static final ResourceLocation PACK_JUPITER_GRAYSCALE = packModel("jupiter_grayscale");
    private static final ResourceLocation PACK_SATURN_GRAYSCALE = packModel("saturn_grayscale");
    private static final ResourceLocation PACK_URANUS_GRAYSCALE = packModel("uranus_grayscale");
    private static final ResourceLocation PACK_NEPTUNE_GRAYSCALE = packModel("neptune_grayscale");

    // the two ringed models (rings are part of the model), the pool of plain generated-planet models, and the full list
    // for registration. STARS use PACK_SUN; FIXED planets use PACK_EARTH; a generated planet that currently draws rings
    // (ringCount > 0) picks a ringed model, the rest distribute across the plain pool, all by the body key hash so a
    // given planet always looks the same.
    // the generated-planet plain pool is the GRAYSCALE variants now, so every generated planet draws a grayscale body and
    // is tinted a deterministic planet-like colour (see packBaseTint / PLANET_PALETTE) instead of wearing a colour sheet.
    private static final ResourceLocation[] PACK_GENERATED_POOL = {
            PACK_MERCURY_GRAYSCALE, PACK_VENUS_GRAYSCALE, PACK_MARS_GRAYSCALE, PACK_JUPITER_GRAYSCALE,
            PACK_NEPTUNE_GRAYSCALE
    };
    // the grayscale set a MAIN (non-Earth) fixed planet picks its body model from, deterministically by key. saturn and
    // uranus are DELIBERATELY EXCLUDED: their rings are baked quads inside the model, so drawPackModel's one colour
    // multiply tinted the rings the same colour as the body and they intersected the body cube at its mid-height (the
    // user's "rings are the same colour, untextured, and show the inside"). Rings are drawn procedurally now (see
    // drawPlanetRings), outside the body, with their own pale tint and their own annulus texture, for any planet the ring
    // roll picks; the ringed pack models are no longer drawn by anything (they still bake, harmlessly).
    private static final ResourceLocation[] MAIN_PLANET_GRAYSCALE = {
            PACK_JUPITER_GRAYSCALE, PACK_MARS_GRAYSCALE, PACK_MERCURY_GRAYSCALE, PACK_NEPTUNE_GRAYSCALE,
            PACK_VENUS_GRAYSCALE
    };
    private static final List<ResourceLocation> PACK_MODELS = List.of(
            PACK_EARTH, PACK_EARTH_GRAYSCALE, PACK_SUN, PACK_MERCURY, PACK_VENUS, PACK_MARS,
            PACK_JUPITER, PACK_SATURN, PACK_URANUS, PACK_NEPTUNE,
            PACK_SUN_GRAYSCALE, PACK_MERCURY_GRAYSCALE, PACK_VENUS_GRAYSCALE, PACK_MARS_GRAYSCALE,
            PACK_JUPITER_GRAYSCALE, PACK_SATURN_GRAYSCALE, PACK_URANUS_GRAYSCALE, PACK_NEPTUNE_GRAYSCALE,
            PACK_VEGETA);

    // PALETTE MECHANISM FIX. A body's colour is the grayscale sheet MULTIPLIED by the per-body tint, and a plain multiply
    // DARKENS: these grayscale sheets average roughly 0.53..0.74 luminance (measured from the pngs), so a rust tint like
    // 0x9E5B3E multiplied by ~0.6 collapses to ~0x5F372B, a dim muddy brown, no matter which hex the palette picks. Every
    // planet then reads as the same dark smear, which is exactly the "the palette is wrong" complaint. The fix is at the
    // mechanism, not the hex values: we LIFT the tint by 1 / (sheet mean luminance) before the multiply, so the sheet's
    // multiply brings the AVERAGE pixel back to the true tint while the sheet's lighter/darker pixels supply variation
    // above and below it. Effective result for a sheet of mean m: on-screen average colour == tint, highlights brighter,
    // shadows darker, instead of a blanket darkening. These are the measured opaque means of each grayscale sheet; the sun
    // is mapped to 1.0 (NO lift) because a star is an emissive beacon that already reads at its true stellar colour and the
    // user is happy with it. A model absent from the table uses DEFAULT_SHEET_MEAN.
    private static final float DEFAULT_SHEET_MEAN = 0.62F;
    private static final Map<ResourceLocation, Float> SHEET_MEAN_LUM = Map.ofEntries(
            Map.entry(PACK_MERCURY_GRAYSCALE, 0.561F),
            Map.entry(PACK_VENUS_GRAYSCALE, 0.734F),
            Map.entry(PACK_MARS_GRAYSCALE, 0.585F),
            Map.entry(PACK_JUPITER_GRAYSCALE, 0.707F),
            Map.entry(PACK_NEPTUNE_GRAYSCALE, 0.531F),
            Map.entry(PACK_SATURN_GRAYSCALE, 0.692F),
            Map.entry(PACK_URANUS_GRAYSCALE, 0.743F),
            Map.entry(PACK_EARTH_GRAYSCALE, 0.633F),
            Map.entry(PACK_SUN_GRAYSCALE, 1.0F),
            // Planet Vegeta's striped sheet is FULL COLOUR drawn untinted, so it must take NO luminance lift (a lift would
            // brighten and wash out its authored blue and brown bands). Mapped to 1.0 like the sun for exactly that reason.
            Map.entry(PACK_VEGETA, 1.0F));

    // the lift factor (1 / mean luminance) for a grayscale sheet, so multiplying the lifted tint by the sheet recovers the
    // true tint on average. Sun -> 1.0 (no lift). Unknown model -> the default mean.
    private static float sheetLift(ResourceLocation model)
    {
        float mean = SHEET_MEAN_LUM.getOrDefault(model, DEFAULT_SHEET_MEAN);
        return mean <= 0.0F ? 1.0F : 1.0F / mean;
    }

    // a model's CORE body element in Blockbench (0..16) units: its centre and its widest side. Parsed once per resource
    // reload (parsePackNorm) and used to normalise the baked model so the core body spans exactly 2*radius, matching the
    // old cube's half-extent = radius. Decorations that reach past the core (the earth model's orbiting moon, the sun's
    // flares, a ring plane) are deliberately NOT counted, so they overspill the body just as they were authored to.
    private record Norm(float cx, float cy, float cz, float span)
    {
    }

    // per-reload norms, and the per-model baked-quad list, cached so the cell walk and the atlas lookup are the only cost.
    // QUAD_CACHE is cleared on reload because a reload rebakes the models into fresh BakedModel instances.
    private static volatile Map<ResourceLocation, Norm> modelNorms = Map.of();
    private static final Map<ResourceLocation, List<BakedQuad>> QUAD_CACHE = new ConcurrentHashMap<>();

    // The DISTINCT sprites each cached quad list samples, derived once per model and cleared with QUAD_CACHE. Used only
    // to keep their animations running under Embeddium and its relatives; see keepSpritesAnimated below for why that is
    // needed at all. Derived rather than looked up by name because a model's sheets are whatever its own quads point at,
    // and a handful of distinct sprites per model is far cheaper to walk each frame than the full quad list.
    private static final Map<ResourceLocation, List<TextureAtlasSprite>> SPRITE_CACHE = new ConcurrentHashMap<>();

    // the RING plane's quads of the baked uranus pack model, filtered out of its full quad set (the body cube's quads are
    // dropped) and coincident-face deduped, cached once per reload. A FIXED body's angled ring is drawn from THESE, so it
    // wears the model's baked 45 degree tilt instead of the flat 20 degree procedural annulus. Null until first needed and
    // cleared on reload alongside QUAD_CACHE, because a reload rebakes the model into a fresh instance.
    private static volatile List<BakedQuad> uranusRingQuads = null;

    // one decisive DRAW-time diagnostic per model per reload: the actual raw baked-vertex box, the uniform scale applied
    // and the resulting drawn size per axis. This is the proof a body is a cube (equal spans) at 2*radius, not a stretched
    // prism, taken from the exact geometry that reaches putBulkData rather than from the source JSON.
    private static final java.util.Set<ResourceLocation> DRAW_DIAG_DONE = ConcurrentHashMap.newKeySet();

    // set true on every reload; the next render frame logs one diagnostic line per pack model, then clears it.
    private static volatile boolean diagnosticsPending = true;

    // report, once per reload, whether each pack body baked to a real model (not the missing cube) and whether its body
    // sprite is really on the block atlas (a missing sprite is the pink checker the user reported). Runs on the first
    // render frame after a reload, when models and atlases are both ready.
    private static void logPackDiagnostics(Minecraft mc)
    {
        if (!diagnosticsPending)
        {
            return;
        }
        diagnosticsPending = false;
        var atlas = mc.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        BakedModel missing = mc.getModelManager().getMissingModel();
        for (ResourceLocation modelRl : PACK_MODELS)
        {
            BakedModel baked = mc.getModelManager().getModel(modelRl);
            boolean bakedOk = baked != null && baked != missing;
            // the body sprite is named exactly like the model path (solar_system_pack/<name>); getSprite returns the
            // missing sprite (named minecraft:missingno) when it is not stitched, so compare the resolved name.
            ResourceLocation spriteId = new ResourceLocation(modelRl.getNamespace(), modelRl.getPath());
            var sprite = atlas.getSprite(spriteId);
            boolean spriteOk = sprite != null && spriteId.equals(sprite.contents().name());
            Norm n = modelNorms.get(modelRl);
            // QUAD COUNT is the decisive number: a body that reports baked=true sprite=true but only a handful of quads
            // (6 or fewer) is losing geometry in collection and will render as fragments; a whole pack body is dozens of
            // quads (six faces per cube across every element). Baked once here and cached, so this costs nothing later.
            int quadCount = bakedOk ? QUAD_CACHE.computeIfAbsent(modelRl, k -> collectQuads(baked)).size() : -1;
            LOGGER.info("[SU] pack body {} baked={} sprite={} quads={} norm={}", modelRl, bakedOk, spriteOk, quadCount,
                    n == null ? "none" : ("span=" + n.span() + " centre=" + n.cx() + "," + n.cy() + "," + n.cz()));
        }
    }

    // Earth's dimension id: the ONE fixed body that keeps the full-colour earth model. Every other fixed body draws the
    // grayscale variant recoloured (see planetModel / packBaseTint).
    private static final String OVERWORLD_KEY = "minecraft:overworld";

    // The SMP survival world. It is an EARTH-STYLE world, so it draws the artist's full-colour earth sheet untinted,
    // exactly like Earth, rather than the grayscale body every other fixed planet recolours. It only exists as a body
    // at all on a dedicated server running Shurui's Key (the destination is stripped everywhere else, see
    // MixinDmzSpacePodDestinations), so on every other install these three checks simply never match.
    private static final String SMP_KEY = "dmz_ragnarok:smp";

    /** Worlds drawn with the full-colour earth model rather than the recolourable grayscale one. */
    private static boolean isEarthStyle(String key)
    {
        return OVERWORLD_KEY.equals(key) || SMP_KEY.equals(key);
    }

    // Planet Vegeta's dimension id: the ONE fixed body that draws a dedicated blue-and-brown striped COLOUR sheet
    // (PACK_VEGETA) untinted, instead of a grayscale model under a flat tint. Special-cased in drawFixedBody only.
    private static final String VEGETA_KEY = "dmz_ragnarok:planet_vegeta";

    // Per-body colour multiplied over the grayscale Earth sheet, keyed by dimension id. WHY a table and not just
    // PlanetPositions.tint everywhere: the two DMZ worlds have canonical colours we want exact, not a hash roll. It is
    // trivially extensible: one line adds a body. A key ABSENT here falls back to PlanetPositions.tint(key) in
    // packBaseTint, so cereal, beerus and any future body still get a stable distinct colour for free with no code
    // change. Planet Vegeta (brown) is coming as a follow-up and slots in as a single entry here.
    private static final Map<String, Integer> FIXED_BODY_TINT = Map.of(
            // Namek reads as green. DMZ's own namek_grass_block_top actually samples to teal #00727A, which as a multiply
            // would tint the sheet blue-green rather than green, so this is a deliberate chosen Namek green, not that sample.
            "dragonminez:namek", 0x33B24D,
            // Sacred Kai world: the average opaque RGB of DMZ sacred_planet_grass_block_top.png, sampled straight out of
            // libs/dragonminez-2.1.3.jar = #42B43C (a yellow-green), rather than eyeballed.
            "dragonminez:sacredkaiplanet", 0x42B43C,
            // Planet Vegeta: a rocky desert brown (#9A5F32). It is a fixed body now, so without an entry here it would
            // fall through to its hash tint; the Saiyan homeworld reads as arid red-brown rock, and this mid-brightness
            // R>G>B multiply keeps the grayscale earth sheet's continent/cloud luminance legible rather than muddying it.
            "dmz_ragnarok:planet_vegeta", 0x9A5F32);

    // pick the model for a PLANET body. A fixed body (its key is a dimension id, not the generated prefix): Earth keeps
    // the full-colour earth model, every OTHER fixed body draws the SAME geometry from the grayscale earth variant so it
    // can be recoloured per body (see packBaseTint) while keeping one shared normalise/scale and landing volume. A
    // generated planet that would have worn procedural rings gets a ringed model (saturn/uranus); the rest spread across
    // the plain pool. All picks are a pure function of the body key so they never flicker between frames or clients.
    private static ResourceLocation planetModel(Body body)
    {
        String key = body.planetKey;
        if (key == null || !key.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            return isEarthStyle(key) ? PACK_EARTH : PACK_EARTH_GRAYSCALE;
        }
        // a generated planet always draws a plain (ringless) body model; if the ring roll gives it rings they are drawn
        // separately by drawPlanetRings with their own tint and texture, so no model with baked rings is used any more.
        return PACK_GENERATED_POOL[Math.floorMod(packHash(key, "planet"), PACK_GENERATED_POOL.length)];
    }

    // the grayscale body model a MAIN (non-Earth) fixed planet draws, a pure function of its key so a given world always
    // looks the same across restarts and clients.
    private static ResourceLocation mainPlanetModel(String key)
    {
        return MAIN_PLANET_GRAYSCALE[Math.floorMod(packHash(key, "mainbody"), MAIN_PLANET_GRAYSCALE.length)];
    }

    // a curated set of plausible PLANET hues. The raw GeneratedPlanets.colourTint hash (each channel floored at 96 up to
    // ~246) can land on neon greens, magentas or grey mush that never read as a world, so a generated planet's body tint
    // is picked from this palette by the key hash instead. These are DULL worlds, kept low-saturation on purpose (the user
    // asked for duller and has not retracted it): the sheets are grayscale under a multiply and a garish tint reads as a
    // plastic ball. The BALANCE has been moved back toward the middle: the palette keeps a warm rocky/desert BASE (rust,
    // terracotta, umber, ochre, tan, dun brown, brick, warm grey) but now carries a real minority of COOL worlds (ocean
    // blue, ice blue, olive green, teal). The split is 8 warm/neutral to 4 cool, i.e. two-thirds warm to one-third cool,
    // not the near eleven-to-one it was. Because the sheet-luminance lift now maps a tint close to its true picked colour
    // (the multiply no longer darkens by a third), these values read literally, so they are chosen dull rather than being
    // over-saturated to survive the old darkening. Deterministic, so the same planet is always the same colour.
    private static final int[] PLANET_PALETTE = {
            // warm / neutral base (8)
            0x9E5B3E, // rust
            0xA9744E, // terracotta
            0x82613F, // burnt umber
            0xB08A50, // ochre
            0xC2A47A, // tan
            0x9A8467, // dun brown
            0x8C4C3B, // dull brick red
            0x8F8579, // warm grey
            // cool minority (4)
            0x3E5F70, // dull ocean blue
            0x89A4B2, // pale ice blue
            0x5E6B42, // moss / olive green
            0x3F6A62  // dull teal
    };

    private static int planetPalette(String key)
    {
        return PLANET_PALETTE[Math.floorMod(packHash(key, "ptint"), PLANET_PALETTE.length)];
    }

    //
    // Every planet body gets a bright glowing coloured EDGE, the outline the solar-system pack promo shows around each
    // cube (Earth cyan, Mars orange, and so on). It is drawn with the SAME reversed-wound far-face shell technique the
    // SUPER body rim uses (see drawSuper / SuperRenderTypes.rim): an enlarged cube wound in REVERSE so the CULL render
    // type keeps only its far faces, LEQUAL-tested against the body depth committed just before it, so inside the body
    // silhouette the far faces fail LEQUAL and only the edge ring, just outside the body, survives. Full-bright so it
    // reads as a glow, not a shaded wireframe.
    //
    // COLOUR: each pack COLOUR sheet carries a small solid swatch outside its sampled UV region (see OUTLINE_SWATCH), the
    // pack's own intended outline colour. Because our bodies are now recoloured from GRAYSCALE sheets under a tint, the
    // swatch would clash (a mars sheet tinted dull brown wearing a bright orange edge), so by default the rim colour is
    // DERIVED FROM THE BODY'S FINAL TINT (brightened and saturated), so the glow always matches the planet you actually
    // see. A body that keeps its REAL colour art untinted (Earth) has no representative tint (its multiply is white), so
    // it uses its sheet swatch instead. Flip OUTLINE_FROM_TINT to false to use the source swatch verbatim everywhere,
    // which is literally what the user asked for before they knew we recolour; kept a one-line change on purpose.
    private static final boolean OUTLINE_FROM_TINT = true;

    // rim geometry: a shade larger than the body (a thin edge) at a near-opaque alpha so the glow reads clearly.
    private static final float PLANET_RIM_SCALE = 1.06F;
    private static final float PLANET_RIM_ALPHA = 0.85F;

    // the pack's own per-sheet outline colours, read once from the 4x4 solid swatch each COLOUR sheet carries OUTSIDE the
    // region its model UVs sample (earth at (0,24), the rest at (0,28)). Baked here as constants so nothing samples a
    // texture at render time. Keyed by BOTH the colour model and its grayscale twin, so the verbatim-swatch mode works
    // whichever model a body draws. sun.png carries NO such swatch (it ships a dedicated sun_outline.png instead), so the
    // sun is absent here on purpose; a star glows through its corona, not this rim.
    private static final Map<ResourceLocation, Integer> OUTLINE_SWATCH = Map.ofEntries(
            Map.entry(PACK_EARTH, 0x89F3FF),   Map.entry(PACK_EARTH_GRAYSCALE, 0x89F3FF),   // cyan
            Map.entry(PACK_MARS, 0xFF9556),    Map.entry(PACK_MARS_GRAYSCALE, 0xFF9556),    // orange
            Map.entry(PACK_VENUS, 0xFDCC79),   Map.entry(PACK_VENUS_GRAYSCALE, 0xFDCC79),   // gold
            Map.entry(PACK_NEPTUNE, 0x6CBDFF), Map.entry(PACK_NEPTUNE_GRAYSCALE, 0x6CBDFF), // blue
            Map.entry(PACK_URANUS, 0xA9F0FF),  Map.entry(PACK_URANUS_GRAYSCALE, 0xA9F0FF),  // pale cyan
            Map.entry(PACK_SATURN, 0xFFC57B),  Map.entry(PACK_SATURN_GRAYSCALE, 0xFFC57B),  // warm gold
            Map.entry(PACK_JUPITER, 0xFFD397), Map.entry(PACK_JUPITER_GRAYSCALE, 0xFFD397), // cream tan
            Map.entry(PACK_MERCURY, 0xD5C0B1), Map.entry(PACK_MERCURY_GRAYSCALE, 0xD5C0B1)); // pale grey-tan

    // the rim shell render type: reuse the SUPER body's rim pass (a translucent, back-face-culling, LEQUAL, depth-write-off
    // pass over the plain white sheet), so the planet edge is built from the exact technique already proven on the super
    // body rather than a second one. Declared where SUPER_SHELL_RIM is; referenced here.

    // resolve the packed rim colour for a body drawn with the given model under the given FINAL (pre-doom) base tint.
    private static int outlineColour(ResourceLocation model, int baseTintRgb)
    {
        Integer swatch = OUTLINE_SWATCH.get(model);
        if (!OUTLINE_FROM_TINT)
        {
            return swatch != null ? swatch : brightenSaturate(baseTintRgb);
        }
        // an untinted real-art body (Earth: white multiply) has no representative tint, so use its sheet swatch; every
        // recoloured body derives its glow from the tint you actually see.
        if (baseTintRgb == 0xFFFFFF && swatch != null)
        {
            return swatch;
        }
        return brightenSaturate(baseTintRgb);
    }

    // a brighter, more vivid version of a packed colour: lift the brightest channel near full, then push each channel away
    // from its own grey so the hue reads as a glow rather than a washed pastel. Clamped per channel.
    private static int brightenSaturate(int rgb)
    {
        float r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        float max = Math.max(r, Math.max(g, b));
        if (max < 1.0F)
        {
            max = 1.0F;
        }
        float boost = 245.0F / max;
        r *= boost; g *= boost; b *= boost;
        float grey = (r + g + b) / 3.0F;
        float sat = 1.4F;
        r = grey + (r - grey) * sat;
        g = grey + (g - grey) * sat;
        b = grey + (b - grey) * sat;
        int ri = Math.max(0, Math.min(255, Math.round(r)));
        int gi = Math.max(0, Math.min(255, Math.round(g)));
        int bi = Math.max(0, Math.min(255, Math.round(b)));
        return (ri << 16) | (gi << 8) | bi;
    }

    // draw the glowing edge around a planet body: the reversed-wound enlarged cube (see the section note), spun and tilted
    // to sit exactly on the body cube, in the given rim colour. The body depth MUST already be committed (the caller
    // flushes the body pass first) so the LEQUAL far-face test leaves only the edge. Emissive-looking via FULL_BRIGHT and
    // deliberately NOT darkened by doom: the caller passes the pre-doom rim colour, so a dying world keeps a bright edge.
    private static void drawPlanetRim(PoseStack poseStack, MultiBufferSource buffer, float radius, float spin,
                                      int rimRgb)
    {
        // the default 20 degree presentation tilt, kept for stars and fixed bodies (they never angle their axis). Ringed
        // generated planets pass their own per-key tilt through the overload below so the rim stays welded to the tilted
        // body silhouette (see planetBodyTilt).
        drawPlanetRim(poseStack, buffer, radius, spin, rimRgb, 20.0F);
    }

    private static void drawPlanetRim(PoseStack poseStack, MultiBufferSource buffer, float radius, float spin,
                                      int rimRgb, float tilt)
    {
        float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
        float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
        float rb = (rimRgb & 0xFF) / 255.0F;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        VertexConsumer vc = buffer.getBuffer(SUPER_SHELL_RIM);
        drawReverseCube(poseStack.last(), vc, radius * PLANET_RIM_SCALE, rr, rg, rb, PLANET_RIM_ALPHA, FULL_BRIGHT);
        poseStack.popPose();
    }

    // the SPHERE analogue of drawPlanetRim, used ONLY by the black hole (whose body is a sphere, not a cube). Identical
    // machinery (SUPER_SHELL_RIM: translucent, back-face-culling, LEQUAL vs the committed body depth, no depth write) but
    // over a reverse-wound sphere shell (drawReverseSphere) instead of drawReverseCube, so the surviving edge ring is
    // circular and hugs the round void instead of boxing it. Planet and star rims still call drawPlanetRim and are
    // untouched by this.
    private static void drawBlackHoleRim(PoseStack poseStack, MultiBufferSource buffer, float radius, float spin,
                                         int rimRgb, float tilt)
    {
        float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
        float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
        float rb = (rimRgb & 0xFF) / 255.0F;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        VertexConsumer vc = buffer.getBuffer(SUPER_SHELL_RIM);
        drawReverseSphere(poseStack.last(), vc, radius * PLANET_RIM_SCALE, rr, rg, rb, PLANET_RIM_ALPHA, FULL_BRIGHT);
        poseStack.popPose();
    }

    // a deterministic cloud-shell shade for a fixed body, white (1.0) down to dark grey (0.35), a pure function of the key
    // so planets differ from each other but never flicker between loads. This is the CLOUD colour only; it is never the
    // body colour (the user's "varying tints of white to dark grey" for the cloud texture). The body's own tint and the
    // doom reddening are applied elsewhere.
    private static float cloudGrey(String key)
    {
        int h = ((key == null ? "" : key) + "#cloud").hashCode();
        h ^= (h >>> 16);
        double u = (h & 0x7FFFFFFF) / (double) 0x7FFFFFFF;
        return (float) (0.35 + u * 0.65);
    }

    // the base colour (packed 0xRRGGBB) to multiply the chosen pack model by, BEFORE any doom reddening. Earth and every
    // generated planet wear their own real art, so they return white (untinted) and keep their sheet colours. Every other
    // fixed body draws the grayscale earth and returns its table colour, or PlanetPositions.tint(key) (== body.tint, set
    // at Body construction) when absent from the table, so a new body is distinct for free. Doom composes ON TOP of this
    // in drawPlanet, so a tinted body still visibly reddens as it dies.
    private static int packBaseTint(Body body)
    {
        String key = body.planetKey;
        // generated planets now wear a grayscale body sheet, so they take a deterministic planet-like palette colour
        // instead of drawing white (the colour sheets are gone from their draw path).
        if (key != null && key.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            return planetPalette(key);
        }
        if (key == null || isEarthStyle(key))
        {
            return 0xFFFFFF;
        }
        return FIXED_BODY_TINT.getOrDefault(key, body.tint);
    }

    // a small stable hash of a body key plus a salt, so the ring vs plain roll and the plain-pool pick do not correlate
    // with each other or with the ring/gas rolls elsewhere.
    private static int packHash(String key, String salt)
    {
        int h = (key + '#' + salt).hashCode();
        return h ^ (h >>> 16);
    }

    // draw a pack model at the body's radius with our own spin and tilt and a per-body colour multiply. Returns false if
    // the model or its norm is not ready (first frames of a reload, a missing asset), so the caller can fall back to the
    // old procedural body and never draw nothing. The pose does all the normalising: translate the core centre to the
    // origin, scale so the core's widest side becomes 2*radius, then tilt and spin. Baked vertices are in model/16 units
    // (vanilla FaceBakery), so the core centre and span (in 0..16) are divided by 16 to match.
    private static boolean drawPackModel(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation modelRl,
                                         float radius, float spin, float tilt, float r, float g, float b, int light)
    {
        Norm norm = modelNorms.get(modelRl);
        if (norm == null || norm.span() < 1.0E-4F)
        {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel baked = mc.getModelManager().getModel(modelRl);
        if (baked == null || baked == mc.getModelManager().getMissingModel())
        {
            return false;
        }
        List<BakedQuad> quads = QUAD_CACHE.computeIfAbsent(modelRl, k -> collectQuads(baked));
        if (quads.isEmpty())
        {
            return false;
        }
        // PALETTE MECHANISM (item 4): lift the tint by 1 / sheet-mean-luminance so the grayscale multiply recovers the
        // true tint on average instead of darkening it into a muddy smear. The lift is applied UNIFORMLY to all three
        // channels and CAPPED so the brightest channel never exceeds 1.0: clamping channels independently would push a
        // bright tan's red and green to 1.0 while its blue lagged, draining the hue toward white (and, after the sheet
        // multiply, toward grey). A uniform cap keeps the hue exact and just brightens as far as it can without clipping,
        // so a bright tint stays its own colour rather than washing out. The sun is mapped to a 1.0 lift so stars are
        // untouched. Applied to the (already doom-reddened) colour, so a dying world still reddens correctly.
        float lift = sheetLift(modelRl);
        float maxChannel = Math.max(r, Math.max(g, b));
        if (maxChannel > 0.0F)
        {
            lift = Math.min(lift, 1.0F / maxChannel);
        }
        r = r * lift;
        g = g * lift;
        b = b * lift;
        float sc = 32.0F * radius / norm.span();
        logDrawDiagnostics(modelRl, quads, sc, norm, radius, lift, r, g, b);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        poseStack.scale(sc, sc, sc);
        poseStack.translate(-norm.cx() / 16.0F, -norm.cy() / 16.0F, -norm.cz() / 16.0F);
        // the model textures live on the block atlas, so a single atlas-bound cutout type draws every face; putBulkData
        // copies the baked (animated) atlas UVs and multiplies the quad colour by r/g/b (white for an untinted planet, a
        // star's tint, or a doom-reddened body).
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(TextureAtlas.LOCATION_BLOCKS));
        Pose pose = poseStack.last();
        keepSpritesAnimated(modelRl, quads);
        for (BakedQuad quad : quads)
        {
            vc.putBulkData(pose, quad, r, g, b, light, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
        return true;
    }

    /**
     * Tell a Sodium-family renderer that this model's sheets are on screen, so their animations keep running.
     *
     * <p>Embeddium ships {@code animate_only_visible_textures} on by default (it is on in this pack), and it decides
     * "visible" from what its CHUNK MESHER built. Our sheets live on the block atlas but no block in the world samples
     * them: the quads above are pulled from the baked model and pushed straight into an atlas-bound render type here, a
     * path the mesher never sees. So the sprites were never marked active and their animations were never ticked, which
     * is what froze the sun's four flare sheets on their first frame. Vanilla Forge ticks every animated sprite
     * regardless, which is why the dev run always looked right and only the real client showed it.
     *
     * <p>Marking is done per DISTINCT sprite, not per quad: the sun model is a few hundred quads over six sheets, and
     * the marker is the same flag write however many times it is called. On a client with no Sodium-family renderer
     * {@link SodiumSpriteAnimation#available()} is false and this returns before touching the cache at all.
     */
    private static void keepSpritesAnimated(ResourceLocation modelRl, List<BakedQuad> quads)
    {
        if (!SodiumSpriteAnimation.available())
            return;
        List<TextureAtlasSprite> sprites = SPRITE_CACHE.computeIfAbsent(modelRl, k -> distinctSprites(quads));
        for (TextureAtlasSprite sprite : sprites)
        {
            SodiumSpriteAnimation.markActive(sprite);
        }
    }

    // the distinct sprites a quad list samples, in first-seen order. Identity comparison is enough and is what we want:
    // two quads on the same sheet carry the very same sprite instance out of the atlas.
    private static List<TextureAtlasSprite> distinctSprites(List<BakedQuad> quads)
    {
        List<TextureAtlasSprite> out = new ArrayList<>(8);
        for (BakedQuad quad : quads)
        {
            TextureAtlasSprite sprite = quad.getSprite();
            if (sprite == null)
            {
                continue;
            }
            boolean seen = false;
            for (TextureAtlasSprite known : out)
            {
                if (known == sprite)
                {
                    seen = true;
                    break;
                }
            }
            if (!seen)
            {
                out.add(sprite);
            }
        }
        return List.copyOf(out);
    }

    // draw a pack model's geometry as a SEMI-TRANSPARENT solid over a caller-supplied render type. This is drawPackModel's
    // exact normalise, scale, spin and tilt, but with a per-vertex ALPHA and NO sheet-luminance lift: the caller wants a
    // flat tint at a chosen opacity (the black hole's pure black at BLACK_HOLE_BODY_ALPHA), not the palette recovery a
    // planet body needs. Uses the Forge alpha putBulkData overload with readExistingColor=false, so the quad is drawn at
    // exactly r/g/b/alpha (the block-atlas UVs still point at the model's sheet, which is why a round pack model reads as
    // a round body). Returns false if the model has not baked yet, like drawPackModel, so the caller can skip a frame.
    private static boolean drawPackModelAlpha(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation modelRl,
                                              RenderType type, float radius, float spin, float tilt,
                                              float r, float g, float b, float alpha, int light)
    {
        Norm norm = modelNorms.get(modelRl);
        if (norm == null || norm.span() < 1.0E-4F)
        {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel baked = mc.getModelManager().getModel(modelRl);
        if (baked == null || baked == mc.getModelManager().getMissingModel())
        {
            return false;
        }
        List<BakedQuad> quads = QUAD_CACHE.computeIfAbsent(modelRl, k -> collectQuads(baked));
        if (quads.isEmpty())
        {
            return false;
        }
        float sc = 32.0F * radius / norm.span();
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        poseStack.scale(sc, sc, sc);
        poseStack.translate(-norm.cx() / 16.0F, -norm.cy() / 16.0F, -norm.cz() / 16.0F);
        VertexConsumer vc = buffer.getBuffer(type);
        Pose pose = poseStack.last();
        keepSpritesAnimated(modelRl, quads);
        for (BakedQuad quad : quads)
        {
            vc.putBulkData(pose, quad, r, g, b, alpha, light, OverlayTexture.NO_OVERLAY, false);
        }
        poseStack.popPose();
        return true;
    }

    // DECISIVE geometry check, once per model per reload. Baked vertices are 8 ints each (DefaultVertexFormat.BLOCK):
    // ints 0..2 are the position floats in model/16 units. We take the raw axis-aligned box of every quad vertex, its
    // per-axis span, the single scale sc that drawPackModel applies, and the resulting drawn size per axis. If the three
    // rawSpan values are equal the geometry is a cube; if the three drawnSize values are equal and about 2*radius the on
    // screen body is a cube at the right size. A tall prism would show one rawSpan (or one drawnSize) markedly larger than
    // the other two, naming the stretched axis and the factor outright. Scale is logged too so a non-uniform pose (which
    // this path does not use) would still be caught by drawnSize disagreeing while rawSpan agrees.
    private static void logDrawDiagnostics(ResourceLocation modelRl, List<BakedQuad> quads, float sc, Norm norm,
                                           float radius, float lift, float r, float g, float b)
    {
        if (!DRAW_DIAG_DONE.add(modelRl))
        {
            return;
        }
        // PALETTE PROOF (item 4): the lift applied and the final multiply colour, so the "muddy brown" complaint is
        // checkable from the log. The effective on-screen average colour is this multiply times the sheet mean, which by
        // construction equals the pre-lift tint.
        LOGGER.info("[SU] pack TINT {} sheetLift={} finalMultiply=#{}", modelRl,
                String.format(Locale.ROOT, "%.3f", lift),
                String.format(Locale.ROOT, "%02X%02X%02X",
                        Math.round(r * 255.0F), Math.round(g * 255.0F), Math.round(b * 255.0F)));
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (BakedQuad quad : quads)
        {
            int[] v = quad.getVertices();
            for (int base = 0; base + 2 < v.length; base += 8)
            {
                float x = Float.intBitsToFloat(v[base]);
                float y = Float.intBitsToFloat(v[base + 1]);
                float z = Float.intBitsToFloat(v[base + 2]);
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
            }
        }
        float spanX = maxX - minX, spanY = maxY - minY, spanZ = maxZ - minZ;
        LOGGER.info("[SU] pack DRAW {} rawAABB=[{},{},{}]..[{},{},{}] rawSpan=({},{},{}) sc={} drawnSize=({},{},{}) "
                        + "radius={} normSpan={}", modelRl, minX, minY, minZ, maxX, maxY, maxZ,
                spanX, spanY, spanZ, sc, spanX * sc, spanY * sc, spanZ * sc, radius, norm.span());
    }

    // collect every quad of a baked model: the unculled (null-side) list PLUS each of the six direction buckets. Quads
    // are bucketed by cull-face; a face with no cullface lands in the null bucket, a face that names one lands in that
    // direction. Collecting ONLY the null bucket (or only some sides) is exactly what leaves a body as a handful of
    // stray faces, so we sweep all seven buckets. The Forge five-arg overload (ModelData.EMPTY, null render type) is
    // used rather than the vanilla three-arg so a Forge model that only answers the extended signature still returns its
    // full quad set instead of a silent subset. Seeded so a model with random face rotations (none here) stays stable.
    private static List<BakedQuad> collectQuads(BakedModel baked)
    {
        List<BakedQuad> out = new ArrayList<>();
        RandomSource rand = RandomSource.create();
        rand.setSeed(42L);
        out.addAll(baked.getQuads(null, null, rand, ModelData.EMPTY, null));
        for (Direction dir : Direction.values())
        {
            rand.setSeed(42L);
            out.addAll(baked.getQuads(null, dir, rand, ModelData.EMPTY, null));
        }
        return out;
    }

    // parse a pack model's CORE body element (the largest solid, non-inverted cube) to its centre and widest side, in the
    // model's own 0..16 units. Inverted cubes (the atmosphere outline shells, authored from > to) and flat elements (ring
    // and flare planes, one side ~0) are skipped so only the real body sets the scale; that is what keeps the earth model
    // from being shrunk to fit its wide orbiting moon.
    private static Norm parsePackNorm(ResourceManager rm, ResourceLocation modelRl)
    {
        ResourceLocation file = new ResourceLocation(modelRl.getNamespace(), "models/" + modelRl.getPath() + ".json");
        var opt = rm.getResource(file);
        if (opt.isEmpty())
        {
            return null;
        }
        try (InputStream in = opt.get().open())
        {
            JsonObject root = GsonHelper.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
            JsonArray elements = root.getAsJsonArray("elements");
            float bestSpan = -1.0F;
            float bcx = 0.0F, bcy = 0.0F, bcz = 0.0F;
            for (JsonElement je : elements)
            {
                JsonObject e = je.getAsJsonObject();
                JsonArray from = e.getAsJsonArray("from");
                JsonArray to = e.getAsJsonArray("to");
                float fx = from.get(0).getAsFloat(), fy = from.get(1).getAsFloat(), fz = from.get(2).getAsFloat();
                float tx = to.get(0).getAsFloat(), ty = to.get(1).getAsFloat(), tz = to.get(2).getAsFloat();
                if (fx > tx || fy > ty || fz > tz)
                {
                    continue;   // inverted outline shell
                }
                float dx = tx - fx, dy = ty - fy, dz = tz - fz;
                if (dx < 1.0E-3F || dy < 1.0E-3F || dz < 1.0E-3F)
                {
                    continue;   // flat ring or flare plane
                }
                float span = Math.max(dx, Math.max(dy, dz));
                if (span > bestSpan)
                {
                    bestSpan = span;
                    bcx = (fx + tx) * 0.5F;
                    bcy = (fy + ty) * 0.5F;
                    bcz = (fz + tz) * 0.5F;
                }
            }
            if (bestSpan <= 0.0F)
            {
                return null;
            }
            return new Norm(bcx, bcy, bcz, bestSpan);
        }
        catch (IOException | RuntimeException ex)
        {
            LOGGER.warn("[SU] failed to parse pack model norm {}", modelRl, ex);
            return null;
        }
    }

    private static Map<ResourceLocation, Norm> parsePackNorms(ResourceManager rm)
    {
        Map<ResourceLocation, Norm> out = new HashMap<>();
        for (ResourceLocation modelRl : PACK_MODELS)
        {
            Norm n = parsePackNorm(rm, modelRl);
            if (n != null)
            {
                out.put(modelRl, n);
            }
        }
        return out;
    }

    // register the pack models as additional models (so they bake and their textures stitch into the block atlas), and
    // register the norm reload listener. Mod bus, client only.
    @Mod.EventBusSubscriber(modid = "dmz_ragnarok_space", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class PackModels
    {
        private PackModels()
        {
        }

        @SubscribeEvent
        public static void onRegisterAdditional(ModelEvent.RegisterAdditional event)
        {
            for (ResourceLocation modelRl : PACK_MODELS)
            {
                event.register(modelRl);
            }
        }

        @SubscribeEvent
        public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event)
        {
            event.registerReloadListener(new SimplePreparableReloadListener<Map<ResourceLocation, Norm>>()
            {
                @Override
                protected Map<ResourceLocation, Norm> prepare(ResourceManager rm, ProfilerFiller profiler)
                {
                    return parsePackNorms(rm);
                }

                @Override
                protected void apply(Map<ResourceLocation, Norm> parsed, ResourceManager rm, ProfilerFiller profiler)
                {
                    modelNorms = parsed;
                    QUAD_CACHE.clear();
                    SPRITE_CACHE.clear();
                    RESOLVED.clear();
                    uranusRingQuads = null;
                    DRAW_DIAG_DONE.clear();
                    FIXED_DIAG_DONE.clear();
                    MOON_DIAG_DONE.clear();
                    RING_DIAG_DONE.clear();
                    STAR_DIAG_DONE.clear();
                    depthPolicyLogged = false;
                    blackHoleLogged = false;
                    diagnosticsPending = true;
                }
            });
        }
    }

    // flush the shared buffer so everything submitted ABOVE this call is drawn before anything below it. A BufferSource
    // batches by render type, not by submission order, so without this a translucent shell (cloud, rim) could be drawn
    // before the opaque body it wraps, and the body's committed depth (needed for the rim's LEQUAL edge test and for a
    // ring's near/far split) would not be there yet. Under the pass depth policy (see onRenderLevel) each render type owns
    // its own write mask: the opaque body (entityCutout) writes depth, the translucent shells (COLOR_WRITE) do not, so
    // this just ends the batch in order and leaves the mask to the next render type's own setup.
    private static void flushInOrder(MultiBufferSource buffer)
    {
        if (buffer instanceof MultiBufferSource.BufferSource bs)
        {
            bs.endBatch();
        }
    }

    private static void drawPlanet(PoseStack poseStack, MultiBufferSource buffer, Body body, float radius,
                                   double tickCount, boolean showLabel)
    {
        // FIXED bodies (their key is a dimension id, not the generated prefix) draw the artist's Earf earth model on the
        // hand-cube path: a body cube PLUS a white cloud shell, both with the box UVs this file was originally built
        // around (PLANET_BOX_UV / CLOUDS_BOX_UV). This path, NOT the baked pack model, is used for them on purpose: it
        // draws the body and the cloud shell as two SEPARATE coloured draws, which is what lets the body be recoloured
        // per planet while the clouds stay white. A single baked model applies one colour to every quad and could not do
        // that. Generated (sugen:) planets fall through to the pack-model pool below, unchanged.
        String fixedKey = body.planetKey;
        if (fixedKey != null && !fixedKey.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            drawFixedBody(poseStack, buffer, body, radius, tickCount, showLabel);
            return;
        }

        // PACK-MODEL PATH: the normal case. The chosen pack model (earth for fixed bodies, a ringed or plain model for a
        // generated one) is drawn untinted so its real art keeps its colours, with the SAME spin (PLANET_DEG_PER_TICK)
        // and tilt the old cube used, plus the doom reddening applied as a colour multiply. The model already carries its
        // rings, clouds and atmosphere, so the old procedural cloud/gas/ring passes are NOT run on this path. The moon is
        // still drawn: it is a LANDABLE body, not decoration, so it stays even though the earth model also shows its own
        // decorative orbiting moon (see the note on drawMoon). Falls through to the procedural body only if the model is
        // not ready.
        // BASE PER-BODY TINT: Earth and generated planets return white (own art, untouched); every other fixed body
        // returns its recolour (see packBaseTint), multiplied over the grayscale earth sheet. The doom ramp below then
        // composes ON TOP of this base rather than replacing it, so a green Namek still visibly reddens as it is
        // destroyed instead of the tint being overwritten.
        int packBase = packBaseTint(body);
        float pr = ((packBase >> 16) & 0xFF) / 255.0F;
        float pg = ((packBase >> 8) & 0xFF) / 255.0F;
        float pb = (packBase & 0xFF) / 255.0F;
        // the moon is a separate landable body drawn as a neutral-grey Earf cube (see drawMoon), so it must NOT inherit the
        // planet's base tint (a green Namek must not turn its moon green). It starts white and only takes the doom ramp,
        // matching how this path behaved before the per-body recolour existed.
        float mr = 1.0F, mg = 1.0F, mb = 1.0F;
        float packDoom = doomFactor(body.planetKey, tickCount);
        if (packDoom > 0.0F)
        {
            pr = pr + (1.0F - pr) * packDoom;
            pg = pg * (1.0F - packDoom);
            pb = pb * (1.0F - packDoom);
            mr = mr + (1.0F - mr) * packDoom;
            mg = mg * (1.0F - packDoom);
            mb = mb * (1.0F - packDoom);
        }
        float packSpin = spinDeg(tickCount, PLANET_DEG_PER_TICK);
        ResourceLocation packModelRl = planetModel(body);
        // AXIAL TILT (angled rings): a ringed planet is tilted by its own deterministic per-key angle so its rings sit at
        // an angle rather than dead flat; a ringless planet keeps the previous fixed 20 degree presentation tilt. The
        // BODY, its RINGS and its RIM all take this one angle so the axis stays coherent from every viewing angle.
        float bodyTilt = planetBodyTilt(body.planetKey);
        if (drawPackModel(poseStack, buffer, packModelRl, radius, packSpin, bodyTilt, pr, pg, pb, FULL_BRIGHT))
        {
            // ORDER (item 3): the body is an entityCutout draw that wrote depth. Commit it, draw any rings this planet
            // rolled as a separate procedural pass with their own tint/texture OUTSIDE the body (defect 2), then lay the
            // glowing edge just outside the silhouette (item 5). A generated planet draws no separate cloud shell on this
            // path. Rim colour is derived from the PRE-DOOM base tint so the edge stays bright as a dying world reddens.
            flushInOrder(buffer);
            drawPlanetRings(poseStack, buffer, body.planetKey, radius, packBase, packSpin, packDoom, bodyTilt);
            flushInOrder(buffer);
            drawPlanetRim(poseStack, buffer, radius, packSpin, outlineColour(packModelRl, packBase), bodyTilt);
            flushInOrder(buffer);
            drawMoon(poseStack, buffer, body, radius, tickCount, mr, mg, mb);
            if (showLabel)
            {
                drawNameplate(poseStack, buffer, body, radius);
            }
            return;
        }

        Resolved resolved = resolvePlanet(body.planetKey);
        float r = 1.0F;
        float g = 1.0F;
        float b = 1.0F;
        // TINT RULE: only the shared grayscale placeholder is multiplied by the per-planet tint. A body with its OWN
        // texture (Namek) draws UNTINTED at full white, so its real art keeps its colour. The doom ramp just below is the
        // ONE deliberate exception to this rule; everything else must keep honouring it.
        if (resolved.tinted())
        {
            r = ((body.tint >> 16) & 0xFF) / 255.0F;
            g = ((body.tint >> 8) & 0xFF) / 255.0F;
            b = (body.tint & 0xFF) / 255.0F;
        }

        // DOOM RAMP: a planet with a live destruction sequence blends toward red as its timer runs down, so the seconds
        // before it comes apart read as critical rather than the world simply blinking out. This DELIBERATELY OVERRIDES
        // the tint rule above: an arted body normally keeps its own colours untouched, but a planet being destroyed has
        // to visibly die whatever texture it wears. Do NOT "fix" this by moving it inside the resolved.tinted() branch,
        // that would leave an arted world dying with no visible change at all. The ramp flows on into the cloud shell and
        // the moon (both take these r/g/b) and is passed separately to the gas-giant envelope, so the whole planet reddens
        // together rather than just the core cube.
        float doom = doomFactor(body.planetKey, tickCount);
        if (doom > 0.0F)
        {
            r = r + (1.0F - r) * doom;
            g = g * (1.0F - doom);
            b = b * (1.0F - doom);
        }

        // FALLBACK PATH (only the first frames of a reload, before the pack model bakes). ORDER (item 3): the passes are
        // explicitly flushed in submission order so a BufferSource cannot paint a translucent shell before the body, and
        // so each translucent pass depth-tests against the body's committed cutout depth. Order is body -> rings -> cloud
        // -> gas -> rim, so the rings split near/far against the body, the cloud sits over both, and the glowing edge is
        // laid last.
        // reuse the same per-key tilt the pack path chose (bodyTilt above), so the fallback body, its rings and its rim
        // stay coherent with each other and with the pack-model look once it bakes.
        float spin = spinDeg(tickCount, PLANET_DEG_PER_TICK);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(bodyTilt));
        // CULLING (fixes "inside visible from outside"): use the culling entityCutout, NOT entityCutoutNoCull. A planet
        // is a solid body you can never see the interior of, so with NO_CULL both the near AND far walls were submitted,
        // and because we render with depth WRITES off (see onRenderLevel) neither wall wins the depth test against the
        // other, so at overlapping pixels whichever face was submitted LAST overwrote the near one and you saw the far
        // interior wall. Enabling GL back-face culling discards the far walls outright: our cube is wound CCW as seen from
        // outside (verified per face in drawCube), which is exactly GL's front-facing convention (glFrontFace GL_CCW,
        // culling GL_BACK, both MC defaults), so the outward faces survive and the inward ones are dropped. This also
        // halves fill for a body we never see inside of, which is why we cull rather than draw both sides.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(resolved.texture()));
        // planets (placeholder AND real art like Namek) use per-face BOX UV so each face samples its own region.
        drawBoxCube(poseStack.last(), vc, radius, r, g, b, FULL_BRIGHT, PLANET_BOX_UV);
        poseStack.popPose();
        // commit the body depth so the rings below split near/far against it and the rim's edge test works.
        flushInOrder(buffer);

        // Saturn-style flat rings, laid in the planet's equatorial plane and turning with it. Rings only go on TINTED
        // placeholder bodies: an arted body (Earth, Namek) has no single tint to key a companion colour off, so it never
        // gets rings (see ringCount). The ring colour is a LIGHTER, duller companion of the body colour now (item 4), not
        // the old inverse. Drawn after the body is committed, so its near half sits in front of the body and its far half
        // behind it through the depth buffer.
        if (resolved.tinted())
        {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(spin));
            poseStack.mulPose(Axis.XP.rotationDegrees(bodyTilt));
            drawRings(poseStack.last(), buffer, body, radius);
            poseStack.popPose();
            flushInOrder(buffer);
        }

        // CLOUD SHELL: a second, slightly larger cube around the body (the geo Clouds cube, inflate 0.5, so 1.125x the
        // body half-extent), sampling the dedicated soft CLOUD_SHEET. Drawn with a TRANSLUCENT type so the sheet's soft
        // alpha reveals the body as a gradient, and spun at PLANET_DEG_PER_TICK, the SAME rate as the body, so the
        // concentric cubes stay face-aligned.
        drawCloudShell(poseStack, buffer, resolved, body, radius, tickCount, r, g, b);
        flushInOrder(buffer);

        // GAS GIANT: some TINTED placeholder planets (see gasGiantColour) additionally get an opaque atmospheric shell so
        // they read like Jupiter/Neptune rather than a rocky world. The placeholder sheet has no cloud art of its own, so
        // this shell samples the body's own opaque BODY region and recolours it, then draws it half-transparent and a
        // touch bigger than the surface: a hazy coloured envelope, deliberately a flat band colour (not white puffs) so
        // it never looks like Earth's real cloud sprite. Arted bodies (Earth, Namek) are excluded, so this can never turn
        // Earth into a gas giant or fight its real clouds.
        if (resolved.tinted())
        {
            drawGasGiantShell(poseStack, buffer, resolved, body, radius, tickCount, doom);
            flushInOrder(buffer);
        }

        // the glowing edge (item 5), laid last so it reads crisply over the shells. Rim colour derives from the PRE-DOOM
        // body tint, so it stays bright as the world reddens; a placeholder body's tint drives the glow.
        drawPlanetRim(poseStack, buffer, radius, spin, outlineColour(resolved.texture(), body.tint), bodyTilt);
        flushInOrder(buffer);

        // MOON: a small cube orbiting the body, drawn ONLY for a FIXED body (drawMoon gates on MoonBody.hasMoon against
        // the synced fixed-body set, the same rule the server's landing volume uses, so a generated planet draws none).
        // Its size and orbit radius scale from the planet's own half-extent, so the moon reads the same relative to a tiny
        // world or a huge one. If the parent sheet carries a drawn moon region (overworld) it is textured; otherwise
        // (Namek, Sacred Kai) a tinted placeholder moon is drawn, since the moon exists regardless of the sheet's art.
        drawMoon(poseStack, buffer, body, radius, tickCount, r, g, b);

        // generated planets carry a nameplate (derived name + owner). Fixed bodies (key is a dimension id, not the
        // generated prefix) draw none. The name is derived client-side from the id; the owner comes from the synced owner
        // map, expanding to a translated "Unclaimed" when absent, so the plate reads in the viewer's language. Only drawn
        // when the player is within LABEL_DISTANCE of the body's surface (see drawBody), so distant specks stay unlabelled.
        if (showLabel)
        {
            drawNameplate(poseStack, buffer, body, radius);
        }
    }

    // one-shot-per-key confirmation that a FIXED body is drawing on the hand-cube Earf path, mirroring the pack
    // diagnostic so the log alone tells us which path drew and that BOTH cubes are present. quads=12 is body 6 + cloud 6.
    private static final java.util.Set<String> FIXED_DIAG_DONE = ConcurrentHashMap.newKeySet();

    private static void logFixedBody(String key, String path, ResourceLocation bodyTexOrModel, int bodyTint,
                                     float cloudShade, float drawnRadius)
    {
        if (FIXED_DIAG_DONE.add(key))
        {
            // drawnRadius is the resolved visual half-extent this body is scaled to (the cube spans 2*drawnRadius), logged
            // so a future "wrong size" complaint is diagnosable straight from the log without a rebuild.
            LOGGER.info("[SU] fixed body {} path={} body={} bodyTint=#{} cloudSheet={} cloudGrey={} drawnRadius={} moon={}",
                    key, path, bodyTexOrModel, String.format(Locale.ROOT, "%06X", bodyTint & 0xFFFFFF), CLOUD_SHEET,
                    String.format(Locale.ROOT, "%.2f", cloudShade), String.format(Locale.ROOT, "%.2f", drawnRadius),
                    MoonBody.hasMoon(null, key));
        }
    }

    // draw a FIXED body (a "main" planet). Earth wears its full-colour Earf sheet untinted; every other fixed body wears a
    // grayscale pack model multiplied by its FIXED_BODY_TINT colour (or PlanetPositions.tint(key), carried in body.tint,
    // when the table has no line for it, so cereal/beerus/any future body stay distinct for free). The cloud shell now
    // samples the dedicated soft CLOUD_SHEET tinted the per-planet grey shade for EVERY main planet INCLUDING EARTH (Earth
    // is no longer a white-cloud special case, item 6). The doom ramp reddens the body AND the clouds together so a dying
    // world reads as one event. A glowing coloured edge (item 5) is laid over the shells. Procedural rings/gas envelopes
    // are not run here; a main planet's rings, if any, come baked into its saturn/uranus pack model.
    private static void drawFixedBody(PoseStack poseStack, MultiBufferSource buffer, Body body, float radius,
                                      double tickCount, boolean showLabel)
    {
        boolean earth = isEarthStyle(body.planetKey);
        int base = earth ? 0xFFFFFF : FIXED_BODY_TINT.getOrDefault(body.planetKey, body.tint);
        float r = ((base >> 16) & 0xFF) / 255.0F;
        float g = ((base >> 8) & 0xFF) / 255.0F;
        float b = (base & 0xFF) / 255.0F;

        // CLOUD shade: a deterministic grey per key (white .. dark grey), NEVER the body tint, so a white-to-grey cloud
        // layer sits over the recoloured body instead of flattening the whole planet to one colour. The MOON stays neutral
        // grey (drawn in drawMoon) and, like the clouds, only takes the doom reddening on top, never the body tint.
        float cloudShade = cloudGrey(body.planetKey);
        float cr = cloudShade, cg = cloudShade, cb = cloudShade;
        float mr = 1.0F, mg = 1.0F, mb = 1.0F;
        float doom = doomFactor(body.planetKey, tickCount);
        if (doom > 0.0F)
        {
            r = r + (1.0F - r) * doom;
            g = g * (1.0F - doom);
            b = b * (1.0F - doom);
            cr = cr + (1.0F - cr) * doom;
            cg = cg * (1.0F - doom);
            cb = cb * (1.0F - doom);
            mr = mr + (1.0F - mr) * doom;
            mg = mg * (1.0F - doom);
            mb = mb * (1.0F - doom);
        }

        // BODY. Earth keeps its full-colour Earf sheet untinted on the hand-cube path (its UV was authored for that
        // unwrap). Every OTHER main planet draws a grayscale PACK MODEL chosen deterministically from the key, tinted by
        // its FIXED_BODY_TINT: the pack sheets are UV-authored for their OWN pack models, NOT for the Earf PLANET_BOX_UV
        // unwrap, so they MUST draw with their own model or the faces would sample the wrong regions. The pack planet
        // models are single centred cubes, so the body stays face-aligned with the concentric hand-cube cloud shell under
        // the same spin + 20-degree tilt. If the model is not ready (first frames of a reload) we fall back to the old
        // grayscale Earf cube tinted, so a main planet never draws nothing.
        String path;
        ResourceLocation bodyTexOrModel;
        if (earth)
        {
            drawEarfBodyCube(poseStack, buffer, EARF_COLOUR, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), r, g, b);
            path = "hand-cube";
            bodyTexOrModel = EARF_COLOUR;
        }
        else if (VEGETA_KEY.equals(body.planetKey))
        {
            // PLANET VEGETA: a dedicated blue-and-brown horizontally striped COLOUR sheet, drawn UNTINTED (white multiply)
            // so its authored bands show literally, exactly like Earth's own colour sheet. Stripes are a two-colour PATTERN
            // and a single flat RGB multiply over a grayscale model can only ever produce one flat colour, so it MUST have
            // its own texture. The doom reddening still applies: the body multiply starts white and only reddens as the
            // world is destroyed (bodyR toward 1, bodyG/bodyB toward 0), matching every other body's death ramp.
            float bodyR = 1.0F, bodyG = 1.0F, bodyB = 1.0F;
            if (doom > 0.0F)
            {
                bodyG = bodyG * (1.0F - doom);
                bodyB = bodyB * (1.0F - doom);
            }
            boolean drew = drawPackModel(poseStack, buffer, PACK_VEGETA, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), 20.0F,
                    bodyR, bodyG, bodyB, FULL_BRIGHT);
            if (drew)
            {
                path = "pack-model-vegeta";
                bodyTexOrModel = PACK_VEGETA;
            }
            else
            {
                drawEarfBodyCube(poseStack, buffer, EARF_GRAYSCALE, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), r, g, b);
                path = "hand-cube-fallback";
                bodyTexOrModel = EARF_GRAYSCALE;
            }
        }
        else
        {
            ResourceLocation model = mainPlanetModel(body.planetKey);
            boolean drew = drawPackModel(poseStack, buffer, model, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), 20.0F,
                    r, g, b, FULL_BRIGHT);
            if (drew)
            {
                path = "pack-model";
                bodyTexOrModel = model;
            }
            else
            {
                drawEarfBodyCube(poseStack, buffer, EARF_GRAYSCALE, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), r, g, b);
                path = "hand-cube-fallback";
                bodyTexOrModel = EARF_GRAYSCALE;
            }
        }
        logFixedBody(body.planetKey, path, bodyTexOrModel, base, cloudShade, radius);

        // ORDER (item 3): the body above is an entityCutout draw that wrote depth. Commit it BEFORE the translucent
        // shells so a BufferSource cannot paint a shell first, and so the shells depth-test against the body. A main
        // planet that drew a saturn/uranus model wears that model's baked rings inside the SAME cutout depth, so those
        // rings already split near/far with the body; the cloud shell then sits over both, and the rim edge is laid last.
        flushInOrder(buffer);

        // rings (defect 2): a separate procedural pass, their own pale tint and annulus texture, OUTSIDE the body, so a
        // main planet that rolls rings (e.g. Sacred Kai) no longer wears the same-colour, untextured, body-intersecting
        // model rings. Drawn after the body depth is committed so the near/far halves split correctly against it. Earth is
        // skipped (the real Earth has no rings, and it kept the ringless earth model before).
        if (!earth)
        {
            // ANGLED RING (fixed bodies): the ring is drawn from the baked uranus pack model's ring PLANE, which carries
            // its OWN 45 degree tilt (uranus.json, rotation 45 about z), instead of the flat 20 degree procedural annulus.
            // The body cube, its concentric cloud shell and its moon still sit at 20 (see drawEarfBodyCube /
            // drawFixedCloudShell / drawMoon): those ARE concentric cubes, so angling them would push the cloud shell's
            // corners through the body's flat faces, whereas the ring is a single flat plane with no such constraint. The
            // per-key angled ring on GENERATED planets is a separate path (drawPlanetRings via planetBodyTilt); this call
            // is fixed bodies only, and it falls back to the flat annulus only until the uranus model bakes.
            drawFixedBodyRings(poseStack, buffer, body.planetKey, radius, base, spinDeg(tickCount, PLANET_DEG_PER_TICK), doom);
            flushInOrder(buffer);
        }

        // cloud shell: the dedicated soft CLOUD_SHEET, tinted the deterministic per-planet grey shade (Earth included now)
        // and spun at the body's exact rate so the concentric cubes stay face-aligned.
        drawFixedCloudShell(poseStack, buffer, radius, tickCount, cr, cg, cb);
        flushInOrder(buffer);

        // the glowing edge (item 5), laid last over the shells. Earth uses its sheet swatch (cyan) since its base multiply
        // is white; every other main planet derives the glow from its FIXED_BODY_TINT. Pre-doom colour (base), so the edge
        // stays bright as the world reddens.
        ResourceLocation rimModel = earth ? PACK_EARTH : mainPlanetModel(body.planetKey);
        drawPlanetRim(poseStack, buffer, radius, spinDeg(tickCount, PLANET_DEG_PER_TICK), outlineColour(rimModel, base));
        flushInOrder(buffer);

        // the moon is the grey Earf body cube (see drawMoon), taking only the doom reddening in mr/mg/mb.
        drawMoon(poseStack, buffer, body, radius, tickCount, mr, mg, mb);

        if (showLabel)
        {
            drawNameplate(poseStack, buffer, body, radius);
        }
    }

    // draw the artist's Earf BODY cube (the uv[0,0] PLANET_BOX_UV unwrap) from the given sheet, spun on the body's own
    // axis at `spin` degrees and given the body's 20-degree tilt, under the r/g/b multiply. This is the ONE cube path for
    // every Earf body: the fixed planet (colour sheet untinted for Earth, grayscale under a per-body tint for the rest)
    // AND its orbiting moon (grayscale under a neutral grey) both call it, instead of each keeping its own copy of the
    // push/spin/tilt/draw block. Culling entityCutout because an Earf body is a solid we never see the interior of and
    // depth writes are off for the whole pass, so back-face culling is what stops the far wall painting over the near one
    // (see the long culling note in drawPlanet).
    private static void drawEarfBodyCube(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation bodySheet,
                                         float radius, float spin, float r, float g, float b)
    {
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutout(bodySheet));
        drawBoxCube(poseStack.last(), vc, radius, r, g, b, FULL_BRIGHT, PLANET_BOX_UV);
        poseStack.popPose();
    }

    // the cloud shell for a fixed body: the geo Clouds cube (1.125x the body half-extent, matching inflate 0.5) sampling
    // the dedicated CLOUD_SHEET now (CLOUD_SHEET_UV) instead of the Earf sheet's old cloud strip, drawn translucent so the
    // sheet's SOFT alpha reveals the body as a gradient. It MUST spin at PLANET_DEG_PER_TICK, the body's exact rate, and
    // share the body's 20-degree tilt: these are concentric cubes, so any relative yaw pushes the shell's corners through
    // the body's flat faces. entityTranslucentCull (TRANSLUCENT_TRANSPARENCY) alpha-BLENDS rather than alpha-thresholds,
    // so the soft cloud edges survive; a cutout type would have hard-cut them. Every fixed body INCLUDING EARTH now takes
    // the per-planet grey shade in r/g/b, so Earth's clouds are tinted like the rest rather than drawn flat white.
    private static void drawFixedCloudShell(PoseStack poseStack, MultiBufferSource buffer, float radius, double tickCount,
                                            float r, float g, float b)
    {
        float cloudSpin = spinDeg(tickCount, PLANET_DEG_PER_TICK);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(cloudSpin));
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(CLOUD_SHEET));
        drawBoxCube(poseStack.last(), vc, radius * CLOUD_INFLATE_FACTOR, r, g, b, FULL_BRIGHT, CLOUD_SHEET_UV);
        poseStack.popPose();
    }

    // draw the translucent cloud shell: the geo Clouds cube scaled to the model's inflate (1.125x the body half-extent),
    // sharing the body's 20-degree tilt AND its exact spin rate (PLANET_DEG_PER_TICK). It must share the rate: these are
    // concentric cubes, so any relative yaw pushes the shell's corners through the body's flat faces and the clouds read
    // as a separate box rotated inside the world.
    //
    // CULLING and ROTATION (fixes "clouds render over parts I should not see" and "clouds rotated wrong"): this uses
    // entityTranslucentCull, NOT the old NO_CULL entityTranslucent. The shell is a full cube, and with depth writes off
    // (see onRenderLevel) its FAR wall was being painted over its NEAR wall, so you saw the back of the cube through the
    // front; culling fixed that OVERDRAW half. The residual "rotated wrong" half was a separate defect: the shell used to
    // spin faster than the body, so at any moment the two cubes sat at different yaws and the shell's corners protruded
    // over the body's faces. Sharing PLANET_DEG_PER_TICK removes that; the per-face UV mapping itself was always correct
    // (it matches the geo Clouds box unwrap). Back-face culling discards the far wall at the GL level, independent of
    // depth, so only the camera-facing cloud faces remain: correct occlusion and correct orientation. The shell still
    // alpha-blends, so a transparent cloud pixel reveals the body, and the r/g/b follow the SAME tint rule as the body
    // (tinted placeholder is multiplied, arted body stays white). Winding matches the body's outward-CCW cube.
    private static void drawCloudShell(PoseStack poseStack, MultiBufferSource buffer, Resolved resolved, Body body,
                                       float radius, double tickCount, float r, float g, float b)
    {
        float cloudSpin = spinDeg(tickCount, PLANET_DEG_PER_TICK);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(cloudSpin));
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        // sample the dedicated soft CLOUD_SHEET (CLOUD_SHEET_UV), translucent-blended so its alpha gradient survives.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(CLOUD_SHEET));
        drawBoxCube(poseStack.last(), vc, radius * CLOUD_INFLATE_FACTOR, r, g, b, FULL_BRIGHT, CLOUD_SHEET_UV);
        poseStack.popPose();
    }

    // draw a gas giant's atmospheric envelope, if this planet is one. The gas-giant roll and colour come from
    // gasGiantColour: it returns null for a normal (rocky) planet, in which case nothing draws. For a gas giant it
    // returns a packed 0xRRGGBB band colour, and we draw a shell that samples the body's OWN opaque BODY region (the
    // placeholder sheet has no cloud art to borrow) recoloured to that band colour and half-transparent, so the surface
    // shows through as banding. Culled and back-face correct exactly like the cloud shell, and spun at PLANET_DEG_PER_TICK,
    // the body's rate: this is a concentric cube, so it must stay face-aligned or its corners protrude through the body's
    // faces (see drawCloudShell). Result reads as a hazy coloured atmosphere, not white puffs, so it never looks like
    // Earth's clouds.
    private static void drawGasGiantShell(PoseStack poseStack, MultiBufferSource buffer, Resolved resolved, Body body,
                                          float radius, double tickCount, float doom)
    {
        Integer band = gasGiantColour(body.planetKey, body.tint);
        if (band == null)
        {
            return;
        }
        float r = ((band >> 16) & 0xFF) / 255.0F;
        float g = ((band >> 8) & 0xFF) / 255.0F;
        float b = (band & 0xFF) / 255.0F;

        // the envelope reddens with the body it wraps (see the doom ramp in drawPlanet). Without this a dying gas giant
        // would keep a calm blue or amber atmosphere over a red-hot surface, which reads as a bug rather than a planet
        // coming apart.
        if (doom > 0.0F)
        {
            r = r + (1.0F - r) * doom;
            g = g * (1.0F - doom);
            b = b * (1.0F - doom);
        }

        float spin = spinDeg(tickCount, PLANET_DEG_PER_TICK);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentCull(resolved.texture()));
        drawBoxCubeAlpha(poseStack.last(), vc, radius * GAS_GIANT_INFLATE_FACTOR, r, g, b, GAS_GIANT_ALPHA,
                FULL_BRIGHT, PLANET_BOX_UV);
        poseStack.popPose();
    }

    // decide, deterministically from the SAME per-body key everything else is hashed from, whether this planet is a gas
    // giant and if so what band colour it wears. Returns null for a rocky planet. PROPORTION: about one placeholder
    // planet in four is a gas giant (a >=750 window on a 0..999 roll). COLOURING RULE: gas giants pick a saturated band
    // hue from a small fixed palette (amber, teal, violet, pale gold) chosen by a second hash bit, blended halfway toward
    // the planet's own tint so no two neighbouring giants look identical while each palette entry still reads clearly as
    // a gas band and never as Earth's white cloud. Uses an independent hash mix so the gas-giant roll does not correlate
    // with the ring roll or the placeholder-sheet pick.
    private static Integer gasGiantColour(String planetKey, int tint)
    {
        if (planetKey == null || planetKey.isEmpty())
        {
            return null;
        }
        int h = planetKey.hashCode() * 0x85EBCA6B;
        h ^= (h >>> 13);
        int roll = Math.floorMod(h, 1000);
        if (roll < 750)
        {
            return null;   // three in four planets stay rocky
        }
        // a small fixed palette of unmistakably "gas band" colours, none of them white.
        int[] palette = { 0xE0A94B, 0x4BB8B0, 0x8A5CC8, 0xD8C77A };
        int base = palette[Math.floorMod(h >>> 4, palette.length)];
        // blend halfway toward the body's own tint so giants vary but keep a clear band colour.
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int tr = (tint >> 16) & 0xFF, tg = (tint >> 8) & 0xFF, tb = tint & 0xFF;
        int mr = (br + tr) / 2, mg = (bg + tg) / 2, mb = (bb + tb) / 2;
        return (mr << 16) | (mg << 8) | mb;
    }

    // one-shot-per-parent confirmation that a moon is drawing on the grey Earf cube path, mirroring logFixedBody so the log
    // alone tells us which sheet a moon actually drew and that it is the BODY cube (no cloud shell). This is exactly the
    // check that caught the fixed-body change not taking effect, so a moon carries its own line.
    private static final java.util.Set<String> MOON_DIAG_DONE = ConcurrentHashMap.newKeySet();

    private static void logMoon(String parentKey)
    {
        if (MOON_DIAG_DONE.add(parentKey))
        {
            LOGGER.info("[SU] moon of {} path=earf-cube bodySheet={} grey={} cloudShell=none quads=6 (body only)",
                    parentKey, EARF_GRAYSCALE, String.format(Locale.ROOT, "#%06X", MOON_GREY));
        }
    }

    // draw the orbiting moon: the SAME Earf body cube as the parent planet (drawEarfBodyCube, PLANET_BOX_UV) but drawn ALL
    // GREY whatever planet it circles (see MOON_GREY), a small cube (MoonBody.RADIUS_FACTOR of the body half-extent) placed
    // out on an orbit ring (MoonBody.ORBIT_FACTOR of the body half-extent) and swept around the body at
    // MoonBody.ORBIT_DEG_PER_TICK. Those constants are the shared source of truth (see MoonBody), so this drawn position
    // and the server's landing volume stay locked together. The orbit is applied in the OUTER (untilted, unspun) body frame
    // so the moon circles the world independently of the planet's own axial spin, with a Y-rotation then a translate along
    // +X, so increasing the orbit angle walks it around a circle in the body's XZ plane. That Y-rotation plus +X step is
    // exactly what MoonBody.position resolves to as a world Vec3, so both sides agree. tickCount is gameTime + partialTick,
    // so the drawn orbit stays smooth between whole server ticks.
    //
    // EXISTENCE is the shared code rule: a moon is drawn iff the parent is a FIXED body (and not destroyed), decided by
    // MoonBody.hasMoon against SpaceLayout.fixedBodies (server == null here, so the SYNCED snapshot, the same set the
    // server's landing volume reads on its side). Every fixed planet (Earth, Namek, Sacred Kai) is in that set, so each
    // gets exactly ONE moon; generated planets are never in it, so they get none.
    //
    // APPEARANCE: the grayscale Earf BODY sheet (EARF_GRAYSCALE) under a neutral grey (MOON_GREY), and deliberately NO
    // cloud shell (a moon has no atmosphere), so it reads as bare airless rock distinct from its parent rather than
    // matching that planet's colour. It gets the SAME spin + tilt treatment the planet body gets (PLANET_DEG_PER_TICK on
    // its own axis, via drawEarfBodyCube) so a lit face is not frozen toward the camera. The incoming r/g/b carry the
    // parent's doom reddening (they are 1,1,1 unless the parent is dying), and we MULTIPLY them over the grey base so a
    // dying world's moon reddens with it, composing doom ON TOP of the grey exactly as the old moon composed doom on top
    // of its texture. Uses the culling cutout like the body, since a moon is a solid opaque cube.
    private static void drawMoon(PoseStack poseStack, MultiBufferSource buffer, Body body,
                                 float radius, double tickCount, float r, float g, float b)
    {
        if (!MoonBody.hasMoon(null, body.planetKey))
        {
            return;
        }
        float orbitAngle = spinDeg(tickCount, MoonBody.ORBIT_DEG_PER_TICK);
        float orbitRadius = radius * MoonBody.ORBIT_FACTOR;
        float moonRadius = radius * MoonBody.RADIUS_FACTOR;
        // neutral grey base, then the parent's doom reddening multiplied on top (r/g/b stay 1,1,1 unless the parent dies).
        float mr = ((MOON_GREY >> 16) & 0xFF) / 255.0F * r;
        float mg = ((MOON_GREY >> 8) & 0xFF) / 255.0F * g;
        float mb = (MOON_GREY & 0xFF) / 255.0F * b;
        logMoon(body.planetKey);
        poseStack.pushPose();
        // sweep around the orbit, then step out to the orbit radius: the Y-rotation turns the +X step into a full circle
        // in the equatorial plane as the angle advances. Orbit maths untouched, they are the shared source of truth.
        poseStack.mulPose(Axis.YP.rotationDegrees(orbitAngle));
        poseStack.translate(orbitRadius, 0.0D, 0.0D);
        // the moon body is the shared Earf cube from the GRAYSCALE sheet, spun on its own axis at PLANET_DEG_PER_TICK.
        drawEarfBodyCube(poseStack, buffer, EARF_GRAYSCALE, moonRadius, spinDeg(tickCount, PLANET_DEG_PER_TICK), mr, mg, mb);
        poseStack.popPose();
    }

    // how many flat rings this planet gets: 0..3, decided deterministically from the SAME per-body key the tint comes
    // from so it is stable across restarts and identical on every client. Most planets get NONE: a hash window keeps
    // roughly one planet in five ringed. Of the ringed ones the count is spread 1/2/3, weighted toward the single ring
    // (a lone ring is the common Saturn look). Arted bodies never reach here (guarded at the call site), and even so a
    // null key returns 0.
    /**
     * Whether a body (by its stable key) wears rings, the single deterministic per-key predicate both this renderer and
     * any other feature (the surface overhead-ring pass, the star-systems layout) can share so the answer never
     * disagrees between them. Derived purely from the body key hash through {@link #ringCount}, so it is stable across
     * restarts and identical on every client and on the server. Roughly one body in five is ringed.
     */
    public static boolean isRinged(String bodyKey)
    {
        return PlanetRings.isRinged(bodyKey);
    }

    // The single source of truth for ring geometry is now PlanetRings (a pure, server-safe class both this renderer and
    // the surface overhead-ring pass share). This delegates so the space view, the surface view and the server never
    // disagree; PlanetRings.bands reproduces the exact roll this method used to hold, and adds the moon/sun exclusion a
    // shared helper needs (a moon and the central sun are never ringed).
    private static int ringCount(String planetKey)
    {
        return PlanetRings.bands(planetKey);
    }

    // a ring's OWN colour, deliberately NOT the body colour (defect 2.1: the user reported model rings reading the same
    // colour as the planet). We desaturate the body tint halfway toward its own grey, then lift it most of the way toward
    // a warm pale dust tone, so the band always reads as pale ring dust with only a hint of the planet's hue, clearly
    // distinct from the body whatever colour the body is. Deterministic (a pure function of the body's base tint).
    private static int ringTint(int baseRgb)
    {
        float r = (baseRgb >> 16) & 0xFF, g = (baseRgb >> 8) & 0xFF, b = baseRgb & 0xFF;
        float mean = (r + g + b) / 3.0F;
        float desat = 0.5F;
        r = r + (mean - r) * desat; g = g + (mean - g) * desat; b = b + (mean - b) * desat;
        float pr = 0xE8, pg = 0xDE, pb = 0xC8, toPale = 0.55F;
        r = r + (pr - r) * toPale; g = g + (pg - g) * toPale; b = b + (pb - b) * toPale;
        int ri = Math.max(0, Math.min(255, Math.round(r)));
        int gi = Math.max(0, Math.min(255, Math.round(g)));
        int bi = Math.max(0, Math.min(255, Math.round(b)));
        return (ri << 16) | (gi << 8) | bi;
    }

    // one-shot-per-key confirmation that a planet's rings drew on the PROCEDURAL pass (their own tint, their own annulus
    // texture, outside the body), so the ring fix is visible in the log exactly like the body/moon diagnostics.
    private static final java.util.Set<String> RING_DIAG_DONE = ConcurrentHashMap.newKeySet();

    private static void logRing(String key, int count, int ringRgb, int baseRgb, ResourceLocation sheet, float mean,
                                float lift, float tilt)
    {
        if (RING_DIAG_DONE.add(key))
        {
            LOGGER.info("[SU] planet rings {} count={} ringTint=#{} bodyTint=#{} texture={} sheetMean={} lift={} "
                            + "tiltDeg={} pass=procedural(two-sided, body+ring+rim share tilt)",
                    key, count, String.format(Locale.ROOT, "%06X", ringRgb & 0xFFFFFF),
                    String.format(Locale.ROOT, "%06X", baseRgb & 0xFFFFFF), sheet,
                    String.format(Locale.ROOT, "%.3f", mean), String.format(Locale.ROOT, "%.3f", lift),
                    String.format(Locale.ROOT, "%.1f", tilt));
        }
    }

    // the deterministic per-planet axial tilt for a RINGED planet, so its rings sit at an angle rather than dead flat and
    // no two ringed planets share one angle. Now owned by PlanetRings (the shared 15..40 degree band, keyed on the body
    // key with its own salt), so the body, the ring and the rim (via planetBodyTilt) and the surface overhead-ring pass
    // all read the one angle.
    private static float ringTilt(String key)
    {
        // delegate to the shared source of truth; PlanetRings.tiltDegrees mirrors this exact packHash mix and 15..40 band.
        return PlanetRings.tiltDegrees(key);
    }

    // the axial tilt a generated planet's BODY is drawn at: a ringed planet takes its per-key ringTilt (15..40) so body
    // and rings share one angled axis; a ringless planet keeps the previous fixed 20 degree presentation tilt, so only
    // ringed worlds change appearance. The rim is drawn at this same angle so it hugs the tilted silhouette.
    private static float planetBodyTilt(String key)
    {
        return ringCount(key) > 0 ? ringTilt(key) : 20.0F;
    }

    // the ring sheet a ringed planet wears, chosen deterministically per key so a given planet always looks the same, and
    // split between the two real sheets for free variety (saturn's broad banded ring vs uranus's thinner one). Carries the
    // sheet's measured mean luminance so the caller can lift the ring tint by THAT, never a body mean.
    private record RingSheet(ResourceLocation texture, float mean)
    {
    }

    private static RingSheet ringSheet(String key)
    {
        return Math.floorMod(packHash(key, "ringtex"), 2) == 0
                ? new RingSheet(SATURN_RINGS_TEXTURE, SATURN_RINGS_MEAN)
                : new RingSheet(URANUS_RINGS_TEXTURE, URANUS_RINGS_MEAN);
    }

    // draw a planet's flat rings PROCEDURALLY on the pack-model paths (both generated and main planets). This is the whole
    // fix for the three ring defects: the ring is its OWN pass with its OWN pale tint (defect 2.1), samples the REAL artist
    // ring sheet (saturn_rings_grayscale / uranus_rings_grayscale, picked per key by ringSheet) so it is clearly textured
    // with genuine ring structure (defect 2.2), and sits at >=2.1x the body half-extent,
    // entirely OUTSIDE the body cube, drawn two-sided (entityTranslucentEmissive is NO_CULL) after the body depth is
    // committed, so its near half sits in front of the body and its far half behind it and it never intersects the body or
    // shows its interior (defect 2.3). The doom ramp reddens the ring with the body it wraps. Pushes its own spun+tilted
    // frame to match the body's axis.
    private static void drawPlanetRings(PoseStack poseStack, MultiBufferSource buffer, String key, float radius,
                                        int baseTint, float spin, float doom, float tilt)
    {
        int count = ringCount(key);
        if (count == 0)
        {
            return;
        }
        int ringRgb = ringTint(baseTint);
        float r = ((ringRgb >> 16) & 0xFF) / 255.0F;
        float g = ((ringRgb >> 8) & 0xFF) / 255.0F;
        float b = (ringRgb & 0xFF) / 255.0F;
        if (doom > 0.0F)
        {
            r = r + (1.0F - r) * doom;
            g = g * (1.0F - doom);
            b = b * (1.0F - doom);
        }
        // SHEET + LIFT: pick this planet's real ring sheet and lift the ring tint by 1 / (that sheet's mean) so the
        // grayscale ring multiply recovers the ring's true dust colour on average, capped so the brightest channel never
        // clips past 1.0 and washes the ring toward white. Same capped-lift shape drawPackModel uses for bodies, but keyed
        // on the RING sheet's own (light) mean so these already-bright sheets do not blow out.
        RingSheet sheet = ringSheet(key);
        float lift = sheet.mean() <= 0.0F ? 1.0F : 1.0F / sheet.mean();
        float maxCh = Math.max(r, Math.max(g, b));
        if (maxCh > 0.0F)
        {
            lift = Math.min(lift, 1.0F / maxCh);
        }
        r *= lift;
        g *= lift;
        b *= lift;
        logRing(key, count, ringRgb, baseTint, sheet.texture(), sheet.mean(), lift, tilt);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        // the per-key axial tilt (feature: angled rings): the ring plane is laid in the body's equatorial plane and then
        // tilted by exactly the angle the body was tilted, so ring and body share one axis and never disagree edge-on.
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(sheet.texture()));
        for (int i = 0; i < count; ++i)
        {
            float ringHalf = radius * (2.1F + i * 0.55F);
            drawFlatRing(poseStack.last(), vc, ringHalf, r, g, b, FULL_BRIGHT);
        }
        poseStack.popPose();
    }

    // the ONE sprite that tells the uranus model's ring plane apart from its body cube: the ring plane samples
    // solar_system_pack/uranus_rings, the body cube samples solar_system_pack/uranus. Filtering the baked quads by this is
    // how drawFixedBodyRings draws only the ring and never the body.
    private static final ResourceLocation URANUS_RING_SPRITE =
            new ResourceLocation(ShuruisUtilities.MODID, "solar_system_pack/uranus_rings");

    // the uranus ring plane's outer edge is scaled to this multiple of the body radius: the SAME 2.1x the procedural
    // annulus's first ring uses (drawFlatRing's base ringHalf), so swapping a fixed body's ring to the baked geometry does
    // not change the ring's size relative to the planet. The plane's own half-extent in the baked model is 1.0 block (its
    // 32-unit y/z span, centred on the body centre), so a uniform scale of this factor times the radius lands its outer
    // edge exactly where the flat annulus sat.
    private static final float FIXED_RING_OUTER_FACTOR = 2.1F;

    // draw a FIXED body's ring from the baked uranus pack model's RING plane, which carries its OWN 45 degree tilt, instead
    // of the flat 20 degree procedural annulus drawPlanetRings lays down. Only the ring plane's quads are drawn: they are
    // told apart from the body cube's quads by their sprite (URANUS_RING_SPRITE), so the body cube is never emitted here.
    // The ring keeps the SAME per-body dust tint, doom reddening, capped sheet lift and Y spin drawPlanetRings gives it;
    // ONLY the tilt source changes, from a runtime 20 to the model's baked 45. The fixed body's own cube, its cloud shell
    // and its moon are untouched and still sit at 20 (see drawEarfBodyCube / drawFixedCloudShell / drawMoon), so the
    // cloud-shell corner-clipping constraint and the moon orbit frame are preserved. If the uranus model or its norm has
    // not baked yet (the first frames of a reload), fall back to the flat procedural annulus so a ring never blinks out.
    // GENERATED planets never reach this method; they still call drawPlanetRings, unchanged.
    private static void drawFixedBodyRings(PoseStack poseStack, MultiBufferSource buffer, String key, float radius,
                                           int baseTint, float spin, float doom)
    {
        int count = ringCount(key);
        if (count == 0)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel baked = mc.getModelManager().getModel(PACK_URANUS);
        Norm norm = modelNorms.get(PACK_URANUS);
        if (baked == null || baked == mc.getModelManager().getMissingModel() || norm == null
                || norm.span() < 1.0E-4F)
        {
            drawPlanetRings(poseStack, buffer, key, radius, baseTint, spin, doom, 20.0F);
            return;
        }
        List<BakedQuad> ringQuads = uranusRingQuads(baked);
        if (ringQuads.isEmpty())
        {
            drawPlanetRings(poseStack, buffer, key, radius, baseTint, spin, doom, 20.0F);
            return;
        }
        // RING DUST TINT: the same pale companion tint, doom reddening and capped sheet-mean lift drawPlanetRings computes,
        // so the angled ring wears exactly the colour the flat annulus did. Keyed on the body's chosen ring sheet mean, not
        // a body mean, so an already-bright ring sheet does not blow out (see drawPlanetRings for the full rationale).
        int ringRgb = ringTint(baseTint);
        float r = ((ringRgb >> 16) & 0xFF) / 255.0F;
        float g = ((ringRgb >> 8) & 0xFF) / 255.0F;
        float b = (ringRgb & 0xFF) / 255.0F;
        if (doom > 0.0F)
        {
            r = r + (1.0F - r) * doom;
            g = g * (1.0F - doom);
            b = b * (1.0F - doom);
        }
        RingSheet sheet = ringSheet(key);
        float lift = sheet.mean() <= 0.0F ? 1.0F : 1.0F / sheet.mean();
        float maxCh = Math.max(r, Math.max(g, b));
        if (maxCh > 0.0F)
        {
            lift = Math.min(lift, 1.0F / maxCh);
        }
        r *= lift;
        g *= lift;
        b *= lift;
        // the diagnostic reports the baked 45 tilt (not a runtime 20) so the log alone tells us the angled-ring path drew.
        logRing(key, count, ringRgb, baseTint, sheet.texture(), sheet.mean(), lift, 45.0F);
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        // NO runtime XP tilt here: the 45 degree ring tilt is baked into the geometry, which is the whole point of drawing
        // the uranus ring plane rather than the flat annulus. Centre the model on the body centre exactly as drawPackModel
        // does (translate -norm centre / 16, baked vertices being in model/16 units), then scale by FIXED_RING_OUTER_FACTOR
        // so the ring's outer edge lands where the flat annulus sat. The body cube is never emitted.
        float sc = FIXED_RING_OUTER_FACTOR * radius;
        poseStack.scale(sc, sc, sc);
        poseStack.translate(-norm.cx() / 16.0F, -norm.cy() / 16.0F, -norm.cz() / 16.0F);
        // the ring quads sample the uranus_rings sprite on the BLOCK ATLAS (their baked UVs point there), so draw over the
        // atlas, two-sided and emissive-no-depth-write like drawPlanetRings, with the dust tint multiplied through
        // putBulkData exactly as drawPackModel tints a body. Full bright so the ring reads as lit ring dust.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(TextureAtlas.LOCATION_BLOCKS));
        Pose pose = poseStack.last();
        // The ring plane is drawn from a filtered subset of the uranus model's quads, so it is keyed on its own cache
        // entry rather than PACK_URANUS: keying both on the model id would let whichever ran first decide the sprite
        // list for the other. The ring sheet is still, but marking it costs a flag write and keeps this uniform with
        // every other atlas draw here.
        keepSpritesAnimated(PACK_URANUS_RING_PLANE, ringQuads);
        for (BakedQuad quad : ringQuads)
        {
            vc.putBulkData(pose, quad, r, g, b, FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        }
        poseStack.popPose();
    }

    // the uranus model's ring-plane quads, filtered from its full baked quad set and coincident-face deduped, computed once
    // per reload and cached. Body-cube quads (they sample the uranus body sheet, not uranus_rings) are dropped, so this
    // draws the ring and never the body.
    private static List<BakedQuad> uranusRingQuads(BakedModel baked)
    {
        List<BakedQuad> cached = uranusRingQuads;
        if (cached != null)
        {
            return cached;
        }
        List<BakedQuad> all = QUAD_CACHE.computeIfAbsent(PACK_URANUS, k -> collectQuads(baked));
        List<BakedQuad> ring = new ArrayList<>();
        java.util.Set<String> seenFaces = new java.util.HashSet<>();
        for (BakedQuad quad : all)
        {
            if (!URANUS_RING_SPRITE.equals(quad.getSprite().contents().name()))
            {
                continue;   // a body-cube quad; never drawn on the ring pass
            }
            // the ring plane is a ZERO-THICKNESS element, so its two visible faces (east and west) share the SAME four
            // corner positions with opposite winding. Under the NO_CULL ring render type BOTH would rasterise and the
            // translucent ring would blend over itself and read twice as opaque, so collapse a coincident pair to ONE quad
            // by a canonical position key. drawFlatRing draws a single two-sided quad for exactly the same reason.
            if (seenFaces.add(quadPositionKey(quad)))
            {
                ring.add(quad);
            }
        }
        uranusRingQuads = ring;
        return ring;
    }

    // a canonical key for a baked quad's four corner positions, order-independent, so a face and its coincident
    // reverse-wound twin hash to the same key and dedupe to one. Baked vertices are packed ints, the first three of each
    // vertex being x,y,z as float bits; we round to the nearest 1/256 block so tiny FaceBakery rounding never splits a
    // genuinely coincident pair.
    private static String quadPositionKey(BakedQuad quad)
    {
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        String[] corners = new String[4];
        for (int i = 0; i < 4; ++i)
        {
            float x = Float.intBitsToFloat(v[i * stride]);
            float y = Float.intBitsToFloat(v[i * stride + 1]);
            float z = Float.intBitsToFloat(v[i * stride + 2]);
            corners[i] = Math.round(x * 256.0F) + "," + Math.round(y * 256.0F) + "," + Math.round(z * 256.0F);
        }
        java.util.Arrays.sort(corners);
        return String.join(";", corners);
    }

    // draw this planet's flat rings in its equatorial plane (the XZ plane of the already-spun, already-tilted body
    // frame). Each ring is a flat annulus quad, coloured a LIGHTER, duller COMPANION of the body tint (item 4: the user
    // reported the old inverse-colour rings as a defect), extending beyond the body so it reads as a ring and not a
    // collar. Multiple rings get slightly different radii so they nest like real ring systems.
    private static void drawRings(Pose bodyPoseUnused, MultiBufferSource buffer, Body body, float radius)
    {
        // (bodyPoseUnused is only here to document that the caller already pushed the spun+tilted frame; we re-read the
        // live pose below so we can lay the ring flat within it.)
        int count = ringCount(body.planetKey);
        if (count == 0)
        {
            return;
        }
        // the ring's own pale dust tint (see ringTint), clearly distinct from the body colour rather than a lighter shade
        // of the same hue, matching the procedural pack-model ring pass.
        int companion = ringTint(body.tint);
        float r = ((companion >> 16) & 0xFF) / 255.0F;
        float g = ((companion >> 8) & 0xFF) / 255.0F;
        float b = (companion & 0xFF) / 255.0F;

        // same real ring sheet and capped lift the pack-model ring pass uses (see drawPlanetRings), so the fallback rings
        // match once the model bakes rather than briefly showing the old generic annulus.
        RingSheet sheet = ringSheet(body.planetKey);
        float lift = sheet.mean() <= 0.0F ? 1.0F : 1.0F / sheet.mean();
        float maxCh = Math.max(r, Math.max(g, b));
        if (maxCh > 0.0F)
        {
            lift = Math.min(lift, 1.0F / maxCh);
        }
        r *= lift;
        g *= lift;
        b *= lift;

        // A ring is visible from BOTH sides (you can look at the planet from above or below its equator), so it must NOT
        // be back-face culled the way the body is. It also carries its own alpha (the annulus is transparent in the hole
        // and outside the band), so it needs a translucent, un-culled, emissive type: entityTranslucentEmissive is
        // no-cull and depth-write off, which is exactly right here and does not touch the body's culling entityCutout.
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(sheet.texture()));
        for (int i = 0; i < count; ++i)
        {
            // first ring sits at 2.1x the body half-extent, each further ring a little wider, so they nest.
            float ringHalf = radius * (2.1F + i * 0.55F);
            drawFlatRing(bodyPoseUnused, vc, ringHalf, r, g, b, FULL_BRIGHT);
        }
    }

    // a flat ring quad lying in the body's XZ (equatorial) plane, centred on the body. The annulus texture supplies the
    // hole and outer fade; this just maps it across a square of half-size s.
    private static void drawFlatRing(Pose pose, VertexConsumer vc, float s, float r, float g, float b, int light)
    {
        ringVertex(pose, vc, -s, -s, 0.0F, 0.0F, r, g, b, light);
        ringVertex(pose, vc, s, -s, 1.0F, 0.0F, r, g, b, light);
        ringVertex(pose, vc, s, s, 1.0F, 1.0F, r, g, b, light);
        ringVertex(pose, vc, -s, s, 0.0F, 1.0F, r, g, b, light);
    }

    private static void ringVertex(Pose pose, VertexConsumer vc, float x, float z, float u, float v,
                                   float r, float g, float b, int light)
    {
        vc.vertex(pose.pose(), x, 0.0F, z)
                .color(r, g, b, 1.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    private static void drawNameplate(PoseStack poseStack, MultiBufferSource buffer, Body body, float radius)
    {
        String key = body.planetKey;
        if (key == null || !key.startsWith(GeneratedPlanets.ID_PREFIX))
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        String name = GeneratedPlanets.nameFor(key);
        String owner = SpaceLayout.ownerOf(key);
        net.minecraft.network.chat.Component ownerText = owner == null || owner.isEmpty()
                ? net.minecraft.network.chat.Component.translatable(
                        "message.dmz_ragnarok.core.space_owner_unclaimed")
                : net.minecraft.network.chat.Component.literal(owner);
        net.minecraft.network.chat.Component plate = net.minecraft.network.chat.Component.literal(name + "  ")
                .append(ownerText);

        // lift to just above the top face, billboard to the camera, and draw with the shared font, as a name tag does.
        poseStack.pushPose();
        poseStack.translate(0.0D, radius + 6.0D, 0.0D);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.25F, -0.25F, 0.25F);
        Matrix4f matrix = poseStack.last().pose();
        net.minecraft.client.gui.Font font = mc.font;
        float bgAlpha = mc.options.getBackgroundOpacity(0.25F);
        int bg = (int) (bgAlpha * 255.0F) << 24;
        float half = -font.width(plate) / 2.0F;
        font.drawInBatch(plate, half, 0.0F, 0x20FFFFFF, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, bg, FULL_BRIGHT);
        font.drawInBatch(plate, half, 0.0F, 0xFFFFFFFF, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        poseStack.popPose();
    }

    private static Resolved resolvePlanet(String planetKey)
    {
        return RESOLVED.computeIfAbsent(planetKey == null ? "" : planetKey, SpaceBodyRenderer::probePlanet);
    }

    // probe the per-planet texture through the ResourceManager (NOT the texture pipeline) so a miss is never memoised as
    // a broken texture; only bind a real per-planet texture once confirmed present, else one of the tinted placeholders.
    private static Resolved probePlanet(String planetKey)
    {
        String name = sanitise(planetKey);
        if (!name.isEmpty())
        {
            ResourceLocation candidate = new ResourceLocation(ShuruisUtilities.MODID, PLANET_TEXTURE_DIR + name + ".png");
            if (Minecraft.getInstance().getResourceManager().getResource(candidate).isPresent())
            {
                return new Resolved(candidate, false);
            }
        }
        // no dedicated art: pick ONE of the three grayscale placeholders deterministically from the key's hash (the same
        // stable per-body key the tint is derived from), so a given body always draws the same sheet and generated
        // planets vary across the set. floorMod keeps the index non-negative for any hashCode. Still drawn TINTED.
        int index = Math.floorMod((planetKey == null ? "" : planetKey).hashCode(), PLANET_PLACEHOLDERS.length);
        return new Resolved(PLANET_PLACEHOLDERS[index], true);
    }

    private static String sanitise(String planetKey)
    {
        if (planetKey == null)
        {
            return "";
        }
        int colon = planetKey.indexOf(':');
        String path = colon >= 0 ? planetKey.substring(colon + 1) : planetKey;
        return path.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    // A super dragon ball body is drawn as the REAL faceted dball_super4x model shape (its 13 shell cubes). The user
    // rejected a smooth UV sphere and asked for the model's own faceted silhouette, so we keep the geo. BOTH claim states
    // render through the SAME depth-prefill shell; only the colour, alpha and inner star differ:
    //
    // The 13 shell cubes are axis-aligned, overlapping, and inflated, so their raw union is a blocky mass of intersecting
    // slabs with visible seams, not a ball. A single direct draw of that union shows every protruding panel. The prepass
    // resolves it: pass 1 stamps the nearest shell surface depth (colour masked off), pass 2 draws colour with an EQUAL
    // depth test so only that nearest surface survives, giving one clean faceted silhouette that reads as a ball. An
    // earlier prepass rendered NOTHING because its two passes used two DIFFERENT core shaders (entity_cutout for depth,
    // entity_translucent_cull for colour); MC's core shaders are not declared invariant, so the colour pass's window-Z did
    // not match the depth pass bit for bit and EQUAL discarded everything. The fix is that both passes run the SAME
    // entity-translucent-cull shader over the SAME geometry and pose (see SuperRenderTypes), so the interpolated depth is
    // bit-identical and EQUAL matches reliably.
    //
    // CLAIMED (dragon ball): the warm ball colour at the configured shell alpha, with the inner star read through the
    // glass. The star is NOT on the surface: it is a camera-facing sprite drawn inside the shell (drawSuperStars) and read
    // through the single translucent layer.
    //
    // UNCLAIMED (grey): the same shell in flat grey at alpha 1.0 and no star. At full alpha the translucent blend is
    // visually opaque (src*1 + dst*0), so the grey ball is a solid faceted ball with nothing to see through.

    // the super ball's own warm body colour (the yellow-orange of the ball art), multiplied over the plain white sheet
    // the geo samples. A CLAIMED body is this colour; an UNCLAIMED one (ball not yet taken) stays SUPER_GREY.
    private static final int SUPER_BALL_RGB = 0xFFDE26;

    // the dball_super4x geo (assets/.../geo/block/dball_super4x.geo.json) reduced to its 13 shell cubes as axis-aligned
    // boxes, each {x0,y0,z0, x1,y1,z1} in units where the ball's own half-extent is 1, so multiplying by the body radius
    // makes the ball span exactly 2*radius, matching the old sphere and thus the landing volume. Derived once from the
    // geo (origin - inflate .. origin + size + inflate, then centred on the geo AABB centre and divided by its widest
    // half): the panels overlap into a rounded faceted ball, which is the shape the user asked to keep.
    private static final float[][] SUPER_GEO = {
            {-0.8261F, -0.9348F, -0.8261F, 0.8261F, -0.1087F, 0.8261F},
            {-0.8261F, 0.0217F, -0.8261F, 0.8261F, 0.8043F, 0.8261F},
            {-0.6609F, -0.9783F, -0.6609F, 0.6609F, -0.2391F, 0.6609F},
            {-0.6696F, 0.1087F, -0.6696F, 0.6696F, 0.8913F, 0.6696F},
            {-0.4957F, 0.2826F, -0.5826F, 0.4957F, 0.9783F, 0.5826F},
            {-0.6696F, -0.6826F, -1.0000F, 0.6609F, 0.5435F, -0.2174F},
            {-0.8261F, -0.8478F, 0.0870F, 0.8261F, 0.7174F, 0.9130F},
            {-0.6522F, -0.7174F, 0.2174F, 0.6522F, 0.5870F, 1.0000F},
            {-0.8261F, -0.8478F, -0.9130F, 0.8261F, 0.7174F, -0.0870F},
            {0.0435F, -0.8478F, -0.8261F, 0.9130F, 0.7174F, 0.8261F},
            {-0.9130F, -0.8478F, -0.8261F, -0.0435F, 0.7174F, 0.8261F},
            {-1.0000F, -0.7174F, -0.6522F, -0.2174F, 0.5870F, 0.6522F},
            {0.2174F, -0.7174F, -0.6522F, 1.0000F, 0.5870F, 0.6522F}
    };

    // the inner star pips are the classic red of a dragon ball's markings, drawn emissive so they stay readable through
    // the translucent shell whatever the shell alpha.
    private static final float STAR_PIP_R = 0.86F;
    private static final float STAR_PIP_G = 0.13F;
    private static final float STAR_PIP_B = 0.02F;

    // white star-arrangement sprite (star1..star7) for a given count, tinted to the pip colour on the inner billboard.
    // Authored on a transparent background so only the pips paint.
    private static ResourceLocation superStarSprite(int star)
    {
        int n = Math.max(1, Math.min(7, star));
        return new ResourceLocation(ShuruisUtilities.MODID, "textures/entity/space/dball_stars/star" + n + ".png");
    }

    // the claimed shell's two passes: a depth prefill and an EQUAL colour pass. Built once (create() does not memoise, so
    // building per frame would leak render types and defeat the BufferSource's per-type batching). Both target the SAME
    // texture; SuperRenderTypes guarantees they share one shader so the EQUAL test matches.
    private static final RenderType SUPER_SHELL_DEPTH = SuperRenderTypes.depthPrefill(STAR_BODY_TEXTURE);
    private static final RenderType SUPER_SHELL_COLOR = SuperRenderTypes.colorEqual(STAR_BODY_TEXTURE);
    // the outer rim highlight pass for the super body: a translucent CULLING colour pass (depth writes off, LEQUAL) that
    // draws the enlarged, reversed-wound shell so back-face culling keeps only the FAR faces. Against the shell depth the
    // prefill committed, the interior fails LEQUAL and only the edge ring paints. Built once like the two shell passes.
    private static final RenderType SUPER_SHELL_RIM = SuperRenderTypes.rim(STAR_BODY_TEXTURE);

    // draw a SUPER body. BOTH claim states use the ONE prefill-shell path below, parameterised by colour, alpha and
    // whether a star is drawn, so the two can never drift apart. CLAIMED: the warm ball colour at SUConfig.dragonBallAlpha
    // with the star billboard read through it. UNCLAIMED: flat grey at alpha 1.0 (visually opaque) and no star. The caller
    // only adds a super body inside the short super cull, so it only ever draws when the player is nearly on top of it.
    private static void drawSuper(PoseStack poseStack, MultiBufferSource buffer, Body body, float radius,
                                  double tickCount, boolean showLabel)
    {
        boolean claimed = body.tint == 1;

        // the real buffer source when we have one, so passes that use different render types can be flushed in order (a
        // BufferSource batches by type, not by submission order). null on the rare path where a caller passes a
        // non-flushable source: then we just draw without the inter-pass flushes.
        MultiBufferSource.BufferSource flushable =
                buffer instanceof MultiBufferSource.BufferSource bs ? bs : null;

        float spin = spinDeg(tickCount, SUPER_DEG_PER_TICK);

        // colour and alpha select on claim state. A claimed ball is the warm dragon ball colour at the configured shell
        // alpha with the star inside; an unclaimed one is flat grey, fully opaque, and starless.
        int rgb = claimed ? SUPER_BALL_RGB : SUPER_GREY;
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float alpha = claimed ? (float) SUConfig.dragonBallAlpha : 1.0F;

        // 1) INNER STAR first (claimed only), straight into the colour buffer, so the shell blends OVER it and it reads as
        // sitting inside the glass. Flushed so it lands before the shell.
        if (claimed)
        {
            int star = SuperPlanetPositions.starOf(body.planetKey);
            if (star >= 1)
            {
                drawSuperStars(poseStack, buffer, star, radius);
                if (flushable != null)
                {
                    flushable.endBatch();
                }
            }
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        // a gentle tilt so the spin axis is not dead vertical.
        poseStack.mulPose(Axis.ZP.rotationDegrees(12.0F));

        // 2) DEPTH PREFILL: same shader as the colour pass, colour masked off, depth writes ON, LEQUAL. Stamps the nearest
        // shell surface depth per pixel without touching the star colour underneath.
        VertexConsumer prefill = buffer.getBuffer(SUPER_SHELL_DEPTH);
        drawSuperGeo(poseStack.last(), prefill, radius, r, g, b, alpha, FULL_BRIGHT);
        if (flushable != null)
        {
            flushable.endBatch();
        }

        // 3) COLOUR: the SAME shader over the SAME geometry and pose, depth test EQUAL, depth writes OFF, translucent
        // blend. The identical vertex program makes the interpolated depth bit-identical to the prefill, so only the
        // nearest surface per pixel passes EQUAL: exactly ONE translucent layer over the star, no interior panels and no
        // see-through to the far side.
        VertexConsumer colour = buffer.getBuffer(SUPER_SHELL_COLOR);
        drawSuperGeo(poseStack.last(), colour, radius, r, g, b, alpha, FULL_BRIGHT);
        poseStack.popPose();

        if (flushable != null)
        {
            flushable.endBatch();
            // the prefill's DEPTH_WRITE mask leaves depth writes ON after its batch clears; the body pass runs with depth
            // writes OFF, so restore that before the next body.
            RenderSystem.depthMask(false);
        }

        // RIM: a concentric, slightly larger, slightly lighter SINGLE enclosing box wound in REVERSE, drawn after the
        // shell depth is committed. Reversed winding makes the CULL render type keep only the far faces; inside the
        // silhouette they sit behind the shell surface and LEQUAL discards them, so only the lighter edge ring survives.
        // A single box (not the 13-box hull) so the outline reads as one clean square instead of faceted noise. Both
        // claim states get a rim (a lighter warm ball edge when claimed, a lighter grey edge when not).
        int rimRgb = DragonBallShell.lighten(rgb);
        float rr = ((rimRgb >> 16) & 0xFF) / 255.0F;
        float rg = ((rimRgb >> 8) & 0xFF) / 255.0F;
        float rb = (rimRgb & 0xFF) / 255.0F;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.ZP.rotationDegrees(12.0F));
        VertexConsumer rimVc = buffer.getBuffer(SUPER_SHELL_RIM);
        drawSuperRimGeo(poseStack.last(), rimVc, radius, rr, rg, rb, DragonBallShell.RIM_ALPHA, FULL_BRIGHT);
        poseStack.popPose();
        if (flushable != null)
        {
            flushable.endBatch();
            RenderSystem.depthMask(false);
        }

        if (showLabel)
        {
            drawSuperLabel(poseStack, buffer, radius);
        }
    }

    // Two render types on ONE shader for the claimed shell. depthPrefill: colour off, depth on, LEQUAL. colorEqual: colour
    // on, depth off, EQUAL, translucent blend. Both reference RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER and draw the same
    // geometry under the same pose, so their window depth is bit-identical and EQUAL matches. This is NOT the earlier
    // broken version that paired two different core shaders (entity_cutout for depth, entity_translucent_cull for colour):
    // MC core shaders are not invariant, so that mismatch made EQUAL reject every fragment and the ball vanished.
    private static final class SuperRenderTypes extends RenderType
    {
        private SuperRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size,
                                 boolean affectsCrumbling, boolean sortOnUpload, Runnable setup, Runnable clear)
        {
            super(name, format, mode, size, affectsCrumbling, sortOnUpload, setup, clear);
            throw new UnsupportedOperationException();
        }

        static RenderType depthPrefill(ResourceLocation texture)
        {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(NO_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(DEPTH_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(true);
            return create("su_super_shell_depth", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                    true, false, state);
        }

        static RenderType colorEqual(ResourceLocation texture)
        {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setDepthTestState(EQUAL_DEPTH_TEST)
                    .createCompositeState(true);
            return create("su_super_shell_color", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                    true, false, state);
        }

        // a SEMI-TRANSPARENT body pass: translucent blend, back-face culling, and DEPTH WRITE, LEQUAL. The black hole body
        // wears this so it blends the sky behind it (a see-through void) yet is still the depth AUTHORITY for its own rim
        // and accretion discs, exactly as an opaque planet's cutout body is under the pass depth policy (see onRenderLevel).
        // CULL keeps only the front faces so the void blends once, not doubled through both walls.
        static RenderType translucentBody(ResourceLocation texture)
        {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(COLOR_DEPTH_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(true);
            return create("su_translucent_body", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                    true, false, state);
        }

        // the rim pass: translucent, back-face culling, depth writes off, LEQUAL against the committed shell depth. The
        // caller draws the enlarged shell with reversed winding so this keeps only the far faces (see drawSuperRimGeo).
        static RenderType rim(ResourceLocation texture)
        {
            CompositeState state = CompositeState.builder()
                    .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_CULL_SHADER)
                    .setTextureState(new TextureStateShard(texture, false, false))
                    .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                    .setCullState(CULL)
                    .setLightmapState(LIGHTMAP)
                    .setOverlayState(OVERLAY)
                    .setWriteMaskState(COLOR_WRITE)
                    .setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(true);
            return create("su_super_shell_rim", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS, 256,
                    true, false, state);
        }
    }

    // draw the faceted super-ball shell: every SUPER_GEO box as an outward-wound axis-aligned cube, scaled by the body
    // radius, flat-tinted over the plain white sheet.
    private static void drawSuperGeo(Pose pose, VertexConsumer vc, float radius, float r, float g, float b, float alpha,
                                     int light)
    {
        for (float[] box : SUPER_GEO)
        {
            drawAabb(pose, vc, box[0] * radius, box[1] * radius, box[2] * radius,
                    box[3] * radius, box[4] * radius, box[5] * radius, r, g, b, alpha, light);
        }
    }

    // one axis-aligned box [x0,x1]x[y0,y1]x[z0,z1], six faces wound CCW seen from OUTSIDE so a culling type keeps the
    // outward faces and drops the inward ones. UV is the full 0..1 white sheet on every face (a flat tint, no unwrap).
    // Same winding convention as drawCube; the geometry is just no longer forced to a symmetric half-extent.
    private static void drawAabb(Pose pose, VertexConsumer vc, float x0, float y0, float z0, float x1, float y1,
                                 float z1, float r, float g, float b, float alpha, int light)
    {
        float[] c000 = {x0, y0, z0}, c100 = {x1, y0, z0}, c110 = {x1, y1, z0}, c010 = {x0, y1, z0};
        float[] c001 = {x0, y0, z1}, c101 = {x1, y0, z1}, c111 = {x1, y1, z1}, c011 = {x0, y1, z1};
        quad(pose, vc, c010, c110, c100, c000, 0, 0, -1, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // -Z
        quad(pose, vc, c111, c011, c001, c101, 0, 0, 1, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // +Z
        quad(pose, vc, c011, c010, c000, c001, -1, 0, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // -X
        quad(pose, vc, c110, c111, c101, c100, 1, 0, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // +X
        quad(pose, vc, c000, c100, c101, c001, 0, -1, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // -Y
        quad(pose, vc, c110, c010, c011, c111, 0, 1, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // +Y
    }

    // the super ball rim: the SINGLE convex hull of SUPER_GEO, enlarged about the body centre and wound in REVERSE so
    // back-face culling keeps only its far faces. The old single union AABB circumscribed the roughly round body, so its
    // empty corners ballooned far past it (the "really bad" giant box the user called out on this body); the convex hull
    // instead hugs the ball's real faceted silhouette and gives ONE clean edge. SUPER_GEO is normalised to half-extent 1
    // and centred on the origin, so the hull is enlarged about (0,0,0) and multiplied by the body radius (outputScale) at
    // draw time, exactly as drawSuperGeo scales the body; the pose already carries the body's spin and tilt. Flat lighter
    // tint. The hull is computed once and cached in DragonBallHull, so this adds no per-frame geometry cost.
    private static void drawSuperRimGeo(Pose pose, VertexConsumer vc, float radius, float r, float g, float b,
                                        float alpha, int light)
    {
        float[][] tris = DragonBallHull.hull(SUPER_GEO);
        DragonBallShell.drawHullInverted(pose, vc, tris, 0.0F, 0.0F, 0.0F,
                DragonBallShell.RIM_HULL_SCALE, DragonBallShell.RIM_HULL_MIN_THICKNESS, radius, r, g, b, alpha, light);
    }

    // the inner star billboard: a camera-facing quad at the body's exact centre showing this ball's star sprite, tinted
    // red and drawn emissive so it is bright through the translucent shell. Sized under the body radius so it sits well
    // inside the glass. Drawn BEFORE the shell by drawSuper.
    private static void drawSuperStars(PoseStack poseStack, MultiBufferSource buffer, int star, float radius)
    {
        var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        poseStack.pushPose();
        poseStack.mulPose(orientation);
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(superStarSprite(star)));
        drawColouredDisc(poseStack.last(), vc, radius * 0.62F, STAR_PIP_R, STAR_PIP_G, STAR_PIP_B, 1.0F, FULL_BRIGHT);
        poseStack.popPose();
    }

    // a billboarded name over a super body, drawn like the planet nameplate but with a fixed translated label so a player
    // near enough knows it is a Super Dragon Ball body. Resolved in the viewer's own language client-side.
    private static void drawSuperLabel(PoseStack poseStack, MultiBufferSource buffer, float radius)
    {
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.network.chat.Component plate = net.minecraft.network.chat.Component.translatable(
                "message.dmz_ragnarok.core.space_super_body");
        poseStack.pushPose();
        poseStack.translate(0.0D, radius + 6.0D, 0.0D);
        poseStack.mulPose(mc.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.25F, -0.25F, 0.25F);
        Matrix4f matrix = poseStack.last().pose();
        net.minecraft.client.gui.Font font = mc.font;
        float bgAlpha = mc.options.getBackgroundOpacity(0.25F);
        int bg = (int) (bgAlpha * 255.0F) << 24;
        float half = -font.width(plate) / 2.0F;
        font.drawInBatch(plate, half, 0.0F, 0x20FFFFFF, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, bg, FULL_BRIGHT);
        font.drawInBatch(plate, half, 0.0F, 0xFFFFFFFF, false, matrix, buffer,
                net.minecraft.client.gui.Font.DisplayMode.NORMAL, 0, FULL_BRIGHT);
        poseStack.popPose();
    }

    // a star is the SAME cube geometry as a planet, but huge (about 1.5x a planet), and a SOLID fully bright body in its
    // own hashed stellar colour (warm white / yellow / orange / red / blue-white). It is NOT textured with the sun any
    // more: that left black corners on every face. We bind a plain all-white sheet and multiply the star tint over it,
    // so the whole cube is that colour with no dark anywhere. Drawn with the translucent emissive type so the cube GLOWS
    // against the black rather than being shaded like terrain. Around it a bright CORONA of stacked halo billboards in
    // the star colour makes it unmistakably a star, not a planet.
    //
    // WHY THE OLD HALO WAS INVISIBLE (issue: "stars don't have the sun-style ring"): it was a SINGLE faint radial-falloff
    // quad at only 2.4x the half-extent, drawn BEFORE the cube. The star cube's corners already reach ~1.73x, so a 2.4x
    // disc poked out by barely a third of a radius, and the faint texture at one pass over black read as almost nothing;
    // worse, the emissive cube drawn afterwards added over the halo's inner half. The fix: draw the corona AFTER the cube
    // and much larger, and BUILD IT UP with several additive passes at growing size, so a clearly visible bright ring of
    // the star's own colour surrounds the cube. Additive-emissive means every pass only ever adds light, so stacking can
    // never darken the cube; it just makes the glow bright and wide. A star now reads as a glowing orb of colour; a
    // planet has no corona at all, a lit textured surface, and (near enough) a nameplate, moon and maybe rings.
    private static void drawStar(PoseStack poseStack, MultiBufferSource buffer, Body body, float radius, double tickCount)
    {
        float r = ((body.tint >> 16) & 0xFF) / 255.0F;
        float g = ((body.tint >> 8) & 0xFF) / 255.0F;
        float b = (body.tint & 0xFF) / 255.0F;

        // STAR DRAWN SIZE (defect 4): the star is drawn three times the size the shell sizing gives it, body and corona
        // alike, so it reads as an unmistakable beacon. Purely visual; the true radius (hazard/overlap) is untouched.
        float drawR = radius * STAR_DRAW_SCALE;
        // the star's rim/outline colour, derived from its own tint the SAME way a planet's is (brightenSaturate), so a red
        // star gets a red edge and a blue star a blue one. The sun sheet carries no outline swatch, so this is always the
        // tint-derived glow. Scaled with the star: drawPlanetRim is handed drawR (the 3x-enlarged radius), so the rim grows
        // with STAR_DRAW_SCALE and hugs the enlarged body instead of detaching at the true (un-scaled) radius.
        int starRim = brightenSaturate(body.tint);
        logStar(radius, drawR, body.tint, starRim);
        float starSpin = spinDeg(tickCount, STAR_DEG_PER_TICK);

        // PACK-MODEL PATH: a star is the GRAYSCALE sun model, tinted to the star's own hashed stellar colour (a colour
        // multiply on the model). Grayscale matters here: multiplying a stellar tint over the colour sun sheet gave muddy
        // results (a blue-white tint over an orange sheet), whereas over a grayscale sheet the tint reads true. The sun
        // model carries its own animated flares as quads of the SAME model, so drawPackModel's single r/g/b multiply tints
        // the body AND every flare together: a red star gets a red corona, never a yellow one. The grayscale flare sheets
        // carry the colour sheets' .mcmeta, so they still animate. Same slow star drift and tilt. Falls through to the old
        // solid cube + corona only if the model is not ready.
        if (drawPackModel(poseStack, buffer, PACK_SUN_GRAYSCALE, drawR, starSpin, 20.0F, r, g, b,
                FULL_BRIGHT))
        {
            // ORDER (item 3, same as a planet): the sun model is an entityCutout draw that committed depth. Commit it, then
            // lay the SAME reverse-wound rim shell planets get just outside the body silhouette, spun/tilted to match the
            // body so it stays welded to the edge. The rim's far faces LEQUAL-test against the body depth just committed, so
            // only the edge ring survives. The model's own animated flares (quads of the same model) already drew with the
            // body, so the rim composes over the body-plus-flares rather than replacing that glow.
            flushInOrder(buffer);
            drawPlanetRim(poseStack, buffer, drawR, starSpin, starRim);
            flushInOrder(buffer);
            return;
        }

        // the solid emissive body first.
        float spin = starSpin;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_BODY_TEXTURE));
        // solid body: a plain white sheet stretched over every face (UV 0..1), multiplied by the star tint, so the whole
        // cube is one flat emissive colour with no dark corners.
        drawCube(poseStack.last(), vc, drawR, r, g, b, FULL_BRIGHT, 0.0F, 1.0F);
        poseStack.popPose();

        // RIM (item 3): commit the cube depth, then lay the same reverse-wound edge shell planets get, so the fallback star
        // carries the outline too. Drawn before the corona so the additive corona billboards compose OVER the rim rather
        // than the rim hiding the glow. drawR (the 3x radius) keeps the rim welded to the enlarged cube.
        flushInOrder(buffer);
        drawPlanetRim(poseStack, buffer, drawR, spin, starRim);
        flushInOrder(buffer);

        // corona: several camera-facing halo billboards in the star colour, stacked from a wide soft outer glow down to a
        // tight bright inner ring, all additive so they compound into a clearly visible corona that frames and slightly
        // overspills the cube. Drawn AFTER the cube so the ring sits over the cube's silhouette edge, exactly like a sun.
        var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        poseStack.pushPose();
        poseStack.mulPose(orientation);
        VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_HALO_TEXTURE));
        // {size factor, alpha} passes: a broad faint outer glow, a mid corona, and a bright tight ring hugging the cube.
        drawColouredDisc(poseStack.last(), halo, drawR * 4.2F, r, g, b, 0.55F, FULL_BRIGHT);
        drawColouredDisc(poseStack.last(), halo, drawR * 3.2F, r, g, b, 0.75F, FULL_BRIGHT);
        drawColouredDisc(poseStack.last(), halo, drawR * 2.4F, r, g, b, 1.0F, FULL_BRIGHT);
        poseStack.popPose();
    }

    // The brightness scale a far body draws at, given its HONEST drawn radius (radius * drawScale, before any floor) and
    // the floor its disc was pinned to. At or above the floor the body is at full brightness (1.0); below it the disc holds
    // at the floor size but the alpha fades linearly toward FAR_MIN_ALPHA_FRAC as the honest size shrinks to nothing. This
    // is the "fade to a point instead of growing" rule: a far body never reads as an inflated bright disc that covers a
    // nearer body, it reads as a faint point that dims with distance ("brightness by distance"). Never returns 0, so even
    // the most distant charted system stays faintly visible.
    private static float farFadeAlpha(float honestRadius, float floor)
    {
        if (honestRadius >= floor || floor <= 0.0F)
        {
            return 1.0F;
        }
        float t = honestRadius / floor;   // 0..1
        return FAR_MIN_ALPHA_FRAC + (1.0F - FAR_MIN_ALPHA_FRAC) * t;
    }

    // DISTANT SYSTEM SUN (the "space seems empty" / "glowing orb" fix). A far system's sun is drawn CHEAPLY as a
    // camera-facing beacon: a bright tight CORE disc and a softer glare halo in the sun's own hashed tint, so it reads as a
    // real star with glare rather than a flat ball, while staying a couple of additive discs so a few hundred cost almost
    // nothing. Its size is FLOORED to MIN_FAR_SUN_DRAW so a distant sun stays a visible point; past the floor the disc
    // holds but its brightness FADES (farFadeAlpha), so it never inflates into a disc that covers a nearer body (the
    // intersection fix). Additive-emissive and never depth-writing, drawn far-to-near, so it can only ever add light behind
    // nearer bodies.
    private static void drawFarSun(PoseStack poseStack, MultiBufferSource buffer, Body body, float drawRadius)
    {
        float r = ((body.tint >> 16) & 0xFF) / 255.0F;
        float g = ((body.tint >> 8) & 0xFF) / 255.0F;
        float b = (body.tint & 0xFF) / 255.0F;
        // the honest beacon size (the true angular size, kept at the 3x star scale so a near-ish far sun reads as a sun),
        // then floored so a very distant one never vanishes; the fade compensates for the floor.
        float honest = drawRadius * STAR_DRAW_SCALE;
        float drawR = Math.max(honest, MIN_FAR_SUN_DRAW);
        float fade = farFadeAlpha(honest, MIN_FAR_SUN_DRAW);

        var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        poseStack.pushPose();
        poseStack.mulPose(orientation);
        VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_HALO_TEXTURE));
        // a soft outer glare, a mid glow and a tight bright core: three passes read as a distant star with a corona.
        drawColouredDisc(poseStack.last(), halo, drawR * 2.8F, r, g, b, 0.35F * fade, FULL_BRIGHT);
        drawColouredDisc(poseStack.last(), halo, drawR * 1.7F, r, g, b, 0.6F * fade, FULL_BRIGHT);
        drawColouredDisc(poseStack.last(), halo, drawR * 0.95F, r, g, b, 0.98F * fade, FULL_BRIGHT);
        poseStack.popPose();
    }

    // DISTANT SYSTEM PLANET. One of a near-enough far system's planets, drawn as a small lit point at its real orbital
    // position so the far system reads as a sun WITH planets. A tight core disc plus a faint surrounding glow in the
    // planet's own tint, floored to MIN_FAR_PLANET_DRAW and faded past the floor exactly like a far sun, so it never
    // inflates over a nearer body. Additive-emissive, never depth-writing, drawn far-to-near.
    private static void drawFarPlanet(PoseStack poseStack, MultiBufferSource buffer, Body body, float drawRadius)
    {
        float r = ((body.tint >> 16) & 0xFF) / 255.0F;
        float g = ((body.tint >> 8) & 0xFF) / 255.0F;
        float b = (body.tint & 0xFF) / 255.0F;
        float honest = drawRadius;
        float drawR = Math.max(honest, MIN_FAR_PLANET_DRAW);
        float fade = farFadeAlpha(honest, MIN_FAR_PLANET_DRAW);

        var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
        poseStack.pushPose();
        poseStack.mulPose(orientation);
        VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_HALO_TEXTURE));
        // a faint surrounding glow and a tight lit core: reads as a small planet dot beside its sun.
        drawColouredDisc(poseStack.last(), halo, drawR * 1.9F, r, g, b, 0.28F * fade, FULL_BRIGHT);
        drawColouredDisc(poseStack.last(), halo, drawR * 0.9F, r, g, b, 0.92F * fade, FULL_BRIGHT);
        poseStack.popPose();
    }

    // one-shot-per-reload confirmation that stars are drawn at the enlarged size (defect 4), so the star size is visible
    // in the log exactly like the planet drawn-radius diagnostics. Logs the shell-sized radius the renderer received and
    // the enlarged radius it actually drew.
    private static final java.util.Set<String> STAR_DIAG_DONE = ConcurrentHashMap.newKeySet();

    private static void logStar(float shellRadius, float drawnRadius, int tint, int rimRgb)
    {
        if (STAR_DIAG_DONE.add("star"))
        {
            LOGGER.info("[SU] star draw shellRadius={} starScale={} drawnRadius={} coronaMax={} tint=#{} rim=#{} "
                            + "(rim reuses drawPlanetRim, scaled by STAR_DRAW_SCALE)",
                    String.format(Locale.ROOT, "%.2f", shellRadius), STAR_DRAW_SCALE,
                    String.format(Locale.ROOT, "%.2f", drawnRadius),
                    String.format(Locale.ROOT, "%.2f", drawnRadius * 4.2F),
                    String.format(Locale.ROOT, "%06X", tint & 0xFFFFFF),
                    String.format(Locale.ROOT, "%06X", rimRgb & 0xFFFFFF));
        }
    }

    // the black hole is a REAL 3D body, not a billboard: a procedural UV sphere (drawSphere), tinted pure black and drawn
    // SEMI-TRANSPARENT (BLACK_HOLE_BODY_TYPE writes depth, so it is the depth authority for its own rim, discs and wrap
    // arcs), an orange rim reused from drawPlanetRim exactly as stars now wear one, two angled accretion discs, and two
    // camera-facing wrap-arc crescents over the top and under the bottom. The pack-model path (drawPackModelAlpha) is
    // still live for planets; the black hole no longer sources a pack model of its own.
    // black, semi-transparent: mostly opaque so it reads as a dark void, but alpha < 1 lets a hint of the sky behind it
    // bleed through. 0.70 = 70% black over 30% background, a dark void with a hint of what is behind it.
    private static final float BLACK_HOLE_BODY_ALPHA = 0.70F;
    // a warm orange for the rim and the accretion discs, in the star-rim orange family.
    private static final int BLACK_HOLE_ORANGE = 0xFF7A1E;
    // the semi-transparent body render type, over the plain white star sheet (multiplied black). The body is now a
    // procedural sphere (drawSphere), not a pack cube, so it binds the plain white sheet the void multiply needs rather
    // than the block atlas the pack sheets lived on.
    private static final RenderType BLACK_HOLE_BODY_TYPE =
            SuperRenderTypes.translucentBody(STAR_BODY_TEXTURE);
    // the body sphere resolution the user chose: 48 longitude by 24 latitude cells = 1152 quads, one per cell, so the
    // void holds up as a round body at point blank range instead of the single cube every pack model is.
    private static final int BLACK_HOLE_LON = 48;
    private static final int BLACK_HOLE_LAT = 24;
    // the wrap arcs (the lensed far side of the disc faked as geometry): a half annulus over the top and one under the
    // bottom, each swept as a strip of this many angular cells, from an inner radius that tucks just inside the sphere
    // silhouette (so the sphere occludes it and it reads as emerging from behind the void) out to a soft transparent edge.
    private static final int BLACK_HOLE_ARC_SEGMENTS = 32;
    private static final float BLACK_HOLE_ARC_INNER = 0.90F;
    private static final float BLACK_HOLE_ARC_OUTER = 1.40F;
    private static final float BLACK_HOLE_ARC_ALPHA = 0.85F;

    // DISTORTION IS AN APPROXIMATION, NOT REAL LENSING. Genuine gravitational lensing bends the light already on screen,
    // which needs a screen-space post pass that samples and warps the framebuffer around the hole: a whole render target
    // plus a bespoke shader, and SU has NO shader infrastructure at all (no RegisterShadersEvent, no ShaderInstance). On
    // top of that these bodies draw at RenderLevelStageEvent.AFTER_SKY, before terrain and most of the world are in the
    // framebuffer, so there is little behind the hole to lens at the point we draw. So we do NOT sample the framebuffer:
    // the two accretion discs sample the swirl sheet and counter-rotate, which reads as swept, in-falling, lensed-looking
    // light WITHOUT touching the framebuffer. It suggests distortion; it does not actually distort the world behind it.
    private static void drawBlackHole(PoseStack poseStack, MultiBufferSource buffer, float radius, double tickCount)
    {
        float spin = spinDeg(tickCount, BLACK_HOLE_DEG_PER_TICK);

        // (a) THE BODY: a procedural UV sphere (BLACK_HOLE_LON x BLACK_HOLE_LAT cells), pure black, semi-transparent,
        // writing depth (BLACK_HOLE_BODY_TYPE is the depth authority for the rim, the flat discs and the wrap arcs). The
        // sphere is always producible, so the fallback billboard only guards a degenerate radius, keeping the hole from
        // ever being blank.
        boolean drewBody = radius > 1.0E-4F;
        if (drewBody)
        {
            poseStack.pushPose();
            poseStack.mulPose(Axis.YP.rotationDegrees(spin));
            poseStack.mulPose(Axis.XP.rotationDegrees(20.0F));
            VertexConsumer vc = buffer.getBuffer(BLACK_HOLE_BODY_TYPE);
            drawSphere(poseStack.last(), vc, radius, 0.0F, 0.0F, 0.0F, BLACK_HOLE_BODY_ALPHA, FULL_BRIGHT);
            poseStack.popPose();
        }
        else
        {
            var orientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
            poseStack.pushPose();
            poseStack.mulPose(orientation);
            VertexConsumer core = buffer.getBuffer(RenderType.entityTranslucent(BLACK_HOLE_TEXTURE));
            drawDisc(poseStack.last(), core, radius, 1.0F, FULL_BRIGHT);
            poseStack.popPose();
        }
        // commit the body depth so the rim's edge test, the discs' near/far split and the arcs' behind-the-void tuck all
        // resolve against it.
        flushInOrder(buffer);

        // (b) THE ORANGE RIM: the SPHERE analogue of the planet/star rim (drawBlackHoleRim over a reverse-wound sphere
        // shell), in orange, welded to the round body silhouette and spun with it. Same 20 degree tilt the body was drawn
        // at so it hugs the edge. A cube rim (drawPlanetRim) would box a round void, so the black hole uses its own sphere
        // variant; planets and stars still use drawPlanetRim.
        drawBlackHoleRim(poseStack, buffer, radius, spin, BLACK_HOLE_ORANGE, 20.0F);
        flushInOrder(buffer);

        // (c) TWO ACCRETION DISCS feeding into the hole, drawn through the SAME ring machinery as a planet's rings
        // (drawFlatRing over entityTranslucentEmissive), at different radii and different tilts, counter-rotating so they
        // read as matter spiralling in. The swirl sheet plus the rotation is the cheap-fake lensing (see the note above).
        drawAccretionDisc(poseStack, buffer, radius * 2.3F, 24.0F, spin * 1.6F);
        drawAccretionDisc(poseStack, buffer, radius * 3.0F, 66.0F, -spin * 1.1F);
        flushInOrder(buffer);

        // (d) THE WRAP ARCS: two camera-facing emissive orange crescents, a half annulus each, hugging the top and the
        // bottom of the sphere silhouette (the fake lensed far side of the disc wrapping over and under the void). They
        // LEQUAL-test against the committed sphere depth (entityTranslucentEmissive), so the inner edge that dips inside
        // the silhouette is occluded by the nearer sphere front and the crescent reads as emerging from behind the void.
        // A sphere's silhouette is a circle of radius `radius` from every viewing angle, so the arcs stay welded to the
        // edge no matter where the camera sits.
        boolean drewArcs = drewBody;
        if (drewArcs)
        {
            var arcOrientation = Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation();
            poseStack.pushPose();
            poseStack.mulPose(arcOrientation);
            VertexConsumer arcVc = buffer.getBuffer(RenderType.entityTranslucentEmissive(STAR_BODY_TEXTURE));
            float ar = ((BLACK_HOLE_ORANGE >> 16) & 0xFF) / 255.0F;
            float ag = ((BLACK_HOLE_ORANGE >> 8) & 0xFF) / 255.0F;
            float ab = (BLACK_HOLE_ORANGE & 0xFF) / 255.0F;
            float arcInner = radius * BLACK_HOLE_ARC_INNER;
            float arcOuter = radius * BLACK_HOLE_ARC_OUTER;
            // top: angles 0 (right equator) to PI (left equator), passing through PI/2 (crown). bottom: PI to 2PI.
            drawWrapArc(poseStack.last(), arcVc, arcInner, arcOuter, 0.0F, (float) Math.PI,
                    ar, ag, ab, BLACK_HOLE_ARC_ALPHA, FULL_BRIGHT);
            drawWrapArc(poseStack.last(), arcVc, arcInner, arcOuter, (float) Math.PI, (float) (2.0 * Math.PI),
                    ar, ag, ab, BLACK_HOLE_ARC_ALPHA, FULL_BRIGHT);
            poseStack.popPose();
            flushInOrder(buffer);
        }
        logBlackHole(drewBody, drewArcs);
    }

    // one accretion disc: a flat swirl annulus in its own spun and tilted frame, emissive orange, two-sided through the
    // ring machinery (drawFlatRing) so its near half sits in front of the void and its far half behind it against the
    // committed body depth. The swirl sheet's own alpha shapes the streaks; the rotation drives the in-fall read.
    private static void drawAccretionDisc(PoseStack poseStack, MultiBufferSource buffer, float half, float tilt,
                                          float spin)
    {
        float r = ((BLACK_HOLE_ORANGE >> 16) & 0xFF) / 255.0F;
        float g = ((BLACK_HOLE_ORANGE >> 8) & 0xFF) / 255.0F;
        float b = (BLACK_HOLE_ORANGE & 0xFF) / 255.0F;
        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(spin));
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
        VertexConsumer vc = buffer.getBuffer(RenderType.entityTranslucentEmissive(BLACK_HOLE_SWIRL_TEXTURE));
        drawFlatRing(poseStack.last(), vc, half, r, g, b, FULL_BRIGHT);
        poseStack.popPose();
    }

    // one-shot-per-reload confirmation of the rebuilt black hole path, so it reads as one log line: which body drew (the
    // pack model, or the billboard fallback before the model bakes), the void alpha, and that the rim and discs are the
    // reused machinery rather than the old billboard stack.
    private static volatile boolean blackHoleLogged = false;

    private static void logBlackHole(boolean drewSphereBody, boolean drewArcs)
    {
        if (blackHoleLogged)
        {
            return;
        }
        blackHoleLogged = true;
        int quads = BLACK_HOLE_LAT * BLACK_HOLE_LON;
        LOGGER.info("[SU] black hole: body={} lon={} lat={} quads={} verts={} alpha={} (procedural sphere, black, "
                        + "semi-transparent, depth-writing, CCW-from-outside so CULL keeps front faces), "
                        + "rim=drawBlackHoleRim (reverse-wound sphere shell) orange #{}, 2 flat accretion discs via drawFlatRing over swirl sheet "
                        + "(tilts 24/66, counter-spin), wrapArcs={} (2 camera-facing half-annulus crescents, "
                        + "inner={}xR outer={}xR, LEQUAL vs sphere depth) = cheap-fake lensing, no framebuffer/shader",
                drewSphereBody ? "sphere" : "billboard-fallback",
                BLACK_HOLE_LON, BLACK_HOLE_LAT, quads, quads * 4,
                String.format(Locale.ROOT, "%.2f", BLACK_HOLE_BODY_ALPHA),
                String.format(Locale.ROOT, "%06X", BLACK_HOLE_ORANGE & 0xFFFFFF),
                drewArcs ? "yes" : "no",
                String.format(Locale.ROOT, "%.2f", BLACK_HOLE_ARC_INNER),
                String.format(Locale.ROOT, "%.2f", BLACK_HOLE_ARC_OUTER));
    }

    // The eight cube corners, indexed so bit 0 = +X, bit 1 = +Y, bit 2 = +Z (i.e. c[i] sign of each axis follows i).
    // The vertex order per face is chosen so that:
    //   * all six faces are wound counter-clockwise seen from OUTSIDE the cube. That is GL's front-facing convention
    //     (glFrontFace GL_CCW), so a CULLING render type (planets use entityCutout) keeps the outward faces and drops
    //     the inward ones. None of the faces comes out mirrored, and there is no near/far overwrite.
    //   * quad() assigns p0=UV(u0,v0) top-left, p1=(u1,v0) top-right, p2=(u1,v1) bottom-right, p3=(u0,v1) bottom-left,
    //     where V grows DOWN the texture, so the texture "up" runs from the (p2,p3) edge toward the (p0,p1) edge.
    // ORIENTATION CONVENTION:
    //   * The four SIDE faces (-Z,+Z,-X,+X) put the (p0,p1) edge on the +Y side, so texture-up points along world up
    //     from every side. Flying around the body, the art is upright on all four sides and never appears rotated 90
    //     or 180 degrees between adjacent sides.
    //   * The TOP (+Y) and BOTTOM (-Y) caps have no world "up", so we fix one convention for both: texture-up points
    //     toward world -Z (the same heading the sides read as "behind"). It is arbitrary but identical on both caps.
    // TWO UV MODES share this exact geometry and winding:
    //   * STARS (drawCube): one SQUARE region stretched across every face. The star sheet is a plain all-white 8x8, so
    //     the whole cube is a flat tinted colour; the region is just the full 0..1 sheet. Not a box unwrap.
    //   * PLANETS (drawBoxCube): each face samples its OWN region from PLANET_BOX_UV, a real Minecraft box unwrap, so
    //     adjacent faces line up across the shared corner seams.

    // STARS: stretch one square region [uvMin,uvMax] over all six faces.
    private static void drawCube(Pose pose, VertexConsumer vc, float s, float r, float g, float b, int light,
                                 float uvMin, float uvMax)
    {
        float[][] c = cubeCorners(s);
        // sides: texture-up = world +Y on all four.
        quad(pose, vc, c[3], c[2], c[1], c[0], 0, 0, -1, r, g, b, light, uvMin, uvMin, uvMax, uvMax); // -Z
        quad(pose, vc, c[6], c[7], c[4], c[5], 0, 0, 1, r, g, b, light, uvMin, uvMin, uvMax, uvMax);  // +Z
        quad(pose, vc, c[7], c[3], c[0], c[4], -1, 0, 0, r, g, b, light, uvMin, uvMin, uvMax, uvMax); // -X
        quad(pose, vc, c[2], c[6], c[5], c[1], 1, 0, 0, r, g, b, light, uvMin, uvMin, uvMax, uvMax);  // +X
        // caps: texture-up = world -Z on both.
        quad(pose, vc, c[0], c[1], c[5], c[4], 0, -1, 0, r, g, b, light, uvMin, uvMin, uvMax, uvMax); // -Y (bottom)
        quad(pose, vc, c[2], c[3], c[7], c[6], 0, 1, 0, r, g, b, light, uvMin, uvMin, uvMax, uvMax);  // +Y (top)
    }

    // PLANETS: each face samples its own {u0,v0,u1,v1} region from box (rows in the SAME order as the quads below).
    private static void drawBoxCube(Pose pose, VertexConsumer vc, float s, float r, float g, float b, int light,
                                    float[][] box)
    {
        drawBoxCubeAlpha(pose, vc, s, r, g, b, 1.0F, light, box);
    }

    // same box unwrap as drawBoxCube but with a per-vertex alpha, for the half-transparent gas-giant atmosphere shell.
    private static void drawBoxCubeAlpha(Pose pose, VertexConsumer vc, float s, float r, float g, float b, float alpha,
                                         int light, float[][] box)
    {
        float[][] c = cubeCorners(s);
        float[] f;
        // sides: texture-up = world +Y on all four.
        f = box[0]; quad(pose, vc, c[3], c[2], c[1], c[0], 0, 0, -1, r, g, b, alpha, light, f[0], f[1], f[2], f[3]); // -Z
        f = box[1]; quad(pose, vc, c[6], c[7], c[4], c[5], 0, 0, 1, r, g, b, alpha, light, f[0], f[1], f[2], f[3]);  // +Z
        f = box[2]; quad(pose, vc, c[7], c[3], c[0], c[4], -1, 0, 0, r, g, b, alpha, light, f[0], f[1], f[2], f[3]); // -X
        f = box[3]; quad(pose, vc, c[2], c[6], c[5], c[1], 1, 0, 0, r, g, b, alpha, light, f[0], f[1], f[2], f[3]);  // +X
        // caps: texture-up = world -Z on both.
        f = box[4]; quad(pose, vc, c[0], c[1], c[5], c[4], 0, -1, 0, r, g, b, alpha, light, f[0], f[1], f[2], f[3]); // -Y (bottom)
        f = box[5]; quad(pose, vc, c[2], c[3], c[7], c[6], 0, 1, 0, r, g, b, alpha, light, f[0], f[1], f[2], f[3]);  // +Y (top)
    }

    // a cube wound in REVERSE (each face's vertex order and normal flipped versus drawCube), so a back-face-culling
    // render type keeps only its FAR faces. Used for the planet rim glow: drawn a shade larger than the body and
    // LEQUAL-tested against the committed body depth, the far faces fail inside the body silhouette and only the edge
    // ring survives (the same trick the super body rim uses). Plain white sheet stretched 0..1, flat-tinted.
    private static void drawReverseCube(Pose pose, VertexConsumer vc, float s, float r, float g, float b, float alpha,
                                        int light)
    {
        float[][] c = cubeCorners(s);
        quad(pose, vc, c[0], c[1], c[2], c[3], 0, 0, 1, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // rev -Z
        quad(pose, vc, c[5], c[4], c[7], c[6], 0, 0, -1, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // rev +Z
        quad(pose, vc, c[4], c[0], c[3], c[7], 1, 0, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // rev -X
        quad(pose, vc, c[1], c[5], c[6], c[2], -1, 0, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // rev +X
        quad(pose, vc, c[4], c[5], c[1], c[0], 0, 1, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F);  // rev -Y
        quad(pose, vc, c[6], c[7], c[3], c[2], 0, -1, 0, r, g, b, alpha, light, 0.0F, 0.0F, 1.0F, 1.0F); // rev +Y
    }

    private static float[][] cubeCorners(float s)
    {
        return new float[][] {
                {-s, -s, -s}, { s, -s, -s}, { s,  s, -s}, {-s,  s, -s},
                {-s, -s,  s}, { s, -s,  s}, { s,  s,  s}, {-s,  s,  s}
        };
    }

    // BLACK HOLE BODY: a procedural UV sphere, one quad per lat/lon cell, emitted through the same vertex primitive the
    // cube helpers use. Positions: theta = PI*i/LAT (i=0 north pole/top .. LAT south pole/bottom), phi = 2PI*j/LON;
    // x=R*sin(theta)*cos(phi), y=R*cos(theta), z=R*sin(theta)*sin(phi). Each cell emits corners A(i,j) B(i,j+1)
    // C(i+1,j+1) D(i+1,j) in that order.
    // WINDING: A->B->C->D is CCW seen from OUTSIDE. Proof: edge1=B-A runs +phi, edge2=C-B runs +theta, and
    // edge1 x edge2 = dP/dphi x dP/dtheta = +(R^2 sin theta)(radial outward), matching the same "edge1 x edge2 =
    // outward normal" convention the outward-wound cube faces use (verified against drawCube's +X face). GL front face
    // is CCW, so translucentBody's CULL keeps these outward faces and drops the inner ones: no inside-out failure.
    // Poles degenerate to triangles (A==B at the north cap row, C==D at the south cap row), which is harmless. UVs are
    // cosmetic (pure black over the all-white sheet); normals are per-vertex radial (also cosmetic at FULL_BRIGHT, but
    // emitted correctly anyway).
    private static void drawSphere(Pose pose, VertexConsumer vc, float radius, float r, float g, float b, float alpha,
                                   int light)
    {
        emitSphere(pose, vc, radius, r, g, b, alpha, light, false);
    }

    // REVERSE-WOUND UV sphere: the exact analogue of drawReverseCube for the sphere body. Same cells as drawSphere but the
    // per-cell winding is flipped (A D C B instead of A B C D) and the emitted normals point INWARD, so from OUTSIDE every
    // face reads CW: a back-face-culling render type (SUPER_SHELL_RIM) drops the NEAR hemisphere's faces and keeps only the
    // FAR ones. Drawn a shade larger than the black hole body and LEQUAL-tested against the committed body depth, the far
    // faces fail the test inside the silhouette and only the edge ring, just outside the void, survives. This is the
    // spherical rim used ONLY by the black hole; planets and stars keep drawReverseCube via drawPlanetRim, unchanged.
    private static void drawReverseSphere(Pose pose, VertexConsumer vc, float radius, float r, float g, float b,
                                          float alpha, int light)
    {
        emitSphere(pose, vc, radius, r, g, b, alpha, light, true);
    }

    // shared UV-sphere emitter. reverse=false is the outward-wound body (CULL keeps front faces); reverse=true flips each
    // cell's winding and normal so CULL keeps the far faces (the rim shell). One generator, two windings, so the rim and
    // the body can never drift in resolution or shape.
    private static void emitSphere(Pose pose, VertexConsumer vc, float radius, float r, float g, float b, float alpha,
                                   int light, boolean reverse)
    {
        float normalSign = reverse ? -1.0F : 1.0F;
        for (int i = 0; i < BLACK_HOLE_LAT; i++)
        {
            float theta0 = (float) (Math.PI * i / BLACK_HOLE_LAT);
            float theta1 = (float) (Math.PI * (i + 1) / BLACK_HOLE_LAT);
            float v0 = (float) i / BLACK_HOLE_LAT;
            float v1 = (float) (i + 1) / BLACK_HOLE_LAT;
            for (int j = 0; j < BLACK_HOLE_LON; j++)
            {
                float phi0 = (float) (2.0 * Math.PI * j / BLACK_HOLE_LON);
                float phi1 = (float) (2.0 * Math.PI * (j + 1) / BLACK_HOLE_LON);
                float u0 = (float) j / BLACK_HOLE_LON;
                float u1 = (float) (j + 1) / BLACK_HOLE_LON;
                float[] a = spherePoint(radius, theta0, phi0);
                float[] bb = spherePoint(radius, theta0, phi1);
                float[] c = spherePoint(radius, theta1, phi1);
                float[] d = spherePoint(radius, theta1, phi0);
                if (reverse)
                {
                    // A D C B: the reverse of A B C D, so the outward face becomes CW and is culled, leaving the far side.
                    sphereVertex(pose, vc, a, u0, v0, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, d, u0, v1, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, c, u1, v1, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, bb, u1, v0, r, g, b, alpha, light, normalSign);
                }
                else
                {
                    sphereVertex(pose, vc, a, u0, v0, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, bb, u1, v0, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, c, u1, v1, r, g, b, alpha, light, normalSign);
                    sphereVertex(pose, vc, d, u0, v1, r, g, b, alpha, light, normalSign);
                }
            }
        }
    }

    private static float[] spherePoint(float radius, float theta, float phi)
    {
        float st = (float) Math.sin(theta), ct = (float) Math.cos(theta);
        float sp = (float) Math.sin(phi), cp = (float) Math.cos(phi);
        return new float[] {radius * st * cp, radius * ct, radius * st * sp};
    }

    private static void sphereVertex(Pose pose, VertexConsumer vc, float[] p, float u, float v,
                                     float r, float g, float b, float alpha, int light, float normalSign)
    {
        // radial normal = position normalised, times normalSign (+1 outward for the body, -1 inward for the reverse rim
        // shell). At the poles the vector is tiny but non-zero, guard the divide. Cosmetic at FULL_BRIGHT, emitted anyway.
        float len = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        float inv = (len > 1.0E-5F ? 1.0F / len : 0.0F) * normalSign;
        vc.vertex(pose.pose(), p[0], p[1], p[2])
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), p[0] * inv, p[1] * inv, p[2] * inv)
                .endVertex();
    }

    // one wrap arc: a half annulus swept from startAngle to endAngle in the camera-facing XY plane (caller has applied
    // the camera orientation), built as a strip of BLACK_HOLE_ARC_SEGMENTS quad cells between an inner and an outer
    // radius. Alpha is peakAlpha at the inner edge and fades to 0 at the outer edge, and is further multiplied by
    // sin(PI*t) across the sweep so the crescent melts to nothing at both angular ends and reads brightest at the crown.
    // Untextured intent (plain white sheet), so UVs are fixed at the sheet centre. entityTranslucentEmissive is NO_CULL,
    // so vertex winding is irrelevant here.
    private static void drawWrapArc(Pose pose, VertexConsumer vc, float innerR, float outerR, float startAngle,
                                    float endAngle, float r, float g, float b, float peakAlpha, int light)
    {
        for (int k = 0; k < BLACK_HOLE_ARC_SEGMENTS; k++)
        {
            float t0 = (float) k / BLACK_HOLE_ARC_SEGMENTS;
            float t1 = (float) (k + 1) / BLACK_HOLE_ARC_SEGMENTS;
            float a0 = startAngle + (endAngle - startAngle) * t0;
            float a1 = startAngle + (endAngle - startAngle) * t1;
            float e0 = peakAlpha * (float) Math.sin(Math.PI * t0);
            float e1 = peakAlpha * (float) Math.sin(Math.PI * t1);
            float ci0 = (float) Math.cos(a0), si0 = (float) Math.sin(a0);
            float ci1 = (float) Math.cos(a1), si1 = (float) Math.sin(a1);
            arcVertex(pose, vc, ci0 * innerR, si0 * innerR, r, g, b, e0, light);
            arcVertex(pose, vc, ci1 * innerR, si1 * innerR, r, g, b, e1, light);
            arcVertex(pose, vc, ci1 * outerR, si1 * outerR, r, g, b, 0.0F, light);
            arcVertex(pose, vc, ci0 * outerR, si0 * outerR, r, g, b, 0.0F, light);
        }
    }

    private static void arcVertex(Pose pose, VertexConsumer vc, float x, float y, float r, float g, float b,
                                  float alpha, int light)
    {
        vc.vertex(pose.pose(), x, y, 0.0F)
                .color(r, g, b, alpha)
                .uv(0.5F, 0.5F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    // opaque quad (alpha 1): the common case for the solid star cube and opaque planet/moon faces.
    private static void quad(Pose pose, VertexConsumer vc, float[] p0, float[] p1, float[] p2, float[] p3,
                             int nx, int ny, int nz, float r, float g, float b, int light,
                             float u0, float v0, float u1, float v1)
    {
        quad(pose, vc, p0, p1, p2, p3, nx, ny, nz, r, g, b, 1.0F, light, u0, v0, u1, v1);
    }

    // quad with an explicit alpha, used by the half-transparent gas-giant shell.
    private static void quad(Pose pose, VertexConsumer vc, float[] p0, float[] p1, float[] p2, float[] p3,
                             int nx, int ny, int nz, float r, float g, float b, float alpha, int light,
                             float u0, float v0, float u1, float v1)
    {
        vertex(pose, vc, p0, u0, v0, nx, ny, nz, r, g, b, alpha, light);
        vertex(pose, vc, p1, u1, v0, nx, ny, nz, r, g, b, alpha, light);
        vertex(pose, vc, p2, u1, v1, nx, ny, nz, r, g, b, alpha, light);
        vertex(pose, vc, p3, u0, v1, nx, ny, nz, r, g, b, alpha, light);
    }

    private static void vertex(Pose pose, VertexConsumer vc, float[] p, float u, float v,
                               int nx, int ny, int nz, float r, float g, float b, float alpha, int light)
    {
        vc.vertex(pose.pose(), p[0], p[1], p[2])
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
    }

    private static void drawDisc(Pose pose, VertexConsumer vc, float s, float alpha, int light)
    {
        discVertex(pose, vc, -s, -s, 0.0F, 0.0F, alpha, light);
        discVertex(pose, vc, s, -s, 1.0F, 0.0F, alpha, light);
        discVertex(pose, vc, s, s, 1.0F, 1.0F, alpha, light);
        discVertex(pose, vc, -s, s, 0.0F, 1.0F, alpha, light);
    }

    private static void discVertex(Pose pose, VertexConsumer vc, float x, float y, float u, float v, float alpha,
                                   int light)
    {
        vc.vertex(pose.pose(), x, y, 0.0F)
                .color(1.0F, 1.0F, 1.0F, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
                .endVertex();
    }

    // same flat quad as drawDisc but with a per-vertex tint, for the coloured star halo and the black hole rim/swirl.
    // The caller has already applied the camera orientation, so this is drawn in the XY plane facing the viewer.
    private static void drawColouredDisc(Pose pose, VertexConsumer vc, float s, float r, float g, float b, float alpha,
                                         int light)
    {
        colouredDiscVertex(pose, vc, -s, -s, 0.0F, 0.0F, r, g, b, alpha, light);
        colouredDiscVertex(pose, vc, s, -s, 1.0F, 0.0F, r, g, b, alpha, light);
        colouredDiscVertex(pose, vc, s, s, 1.0F, 1.0F, r, g, b, alpha, light);
        colouredDiscVertex(pose, vc, -s, s, 0.0F, 1.0F, r, g, b, alpha, light);
    }

    private static void colouredDiscVertex(Pose pose, VertexConsumer vc, float x, float y, float u, float v,
                                           float r, float g, float b, float alpha, int light)
    {
        vc.vertex(pose.pose(), x, y, 0.0F)
                .color(r, g, b, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
