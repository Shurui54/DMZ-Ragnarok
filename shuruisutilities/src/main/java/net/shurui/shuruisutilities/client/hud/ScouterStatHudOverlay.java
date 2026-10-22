package net.shurui.shuruisutilities.client.hud;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.lwjgl.opengl.GL11;

import com.mojang.authlib.GameProfile;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.GeneralUserConfig;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Resources;
import com.dragonminez.common.stats.character.Status;
import com.dragonminez.client.render.compat.CosmeticArmorCompat;
import com.dragonminez.client.gui.hud.HudBar;
import com.dragonminez.client.gui.hud.HudRender;
import com.dragonminez.client.gui.hud.HudSmoother;
import com.dragonminez.client.gui.hud.HudSprites;
import com.dragonminez.client.gui.hud.HudStatNumberAnimator;
import com.dragonminez.client.gui.hud.SurgeBarState;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHud;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudElement;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudFrame;
import net.shurui.dev.sdu.client.hud.ragnarok.RagnarokHudSlot;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * SU's custom scouter-style stat HUD. It REPLACES DragonMineZ's own xenoverse/alternative stat HUD with an assembled
 * panel drawn top-left: an orb holding a cached static player preview (framed on head and upper chest), a curved transformation
 * gauge under it, a green health bar that reddens as health drops, a row of ten discrete ki blocks, and a thin stamina
 * bar. It is a registered Forge GUI overlay (see {@link net.shurui.shuruisutilities.client.gui.SUClientMenus}), so it
 * draws in normal HUD space like {@code PlanetInfoOverlay} and {@code RegionHudOverlay}.
 *
 * <h2>The Ragnarok style</h2>
 * On DragonMineZ 2.2 this panel is the main element of the "Ragnarok" style in DMZ's own HUD editor
 * ({@link RagnarokHud}): selecting that style there replaces DMZ's main HUD with this panel, and the panel and its
 * currency cluster ({@link RagnarokHudSlot#STATS}, {@link RagnarokHudSlot#CURRENCY}) are moved, scaled and hidden in
 * DMZ's editor and saved in DMZ's {@code hud_layout.json}. DMZ's main HUD classes each draw only for their own style,
 * so nothing is cancelled. The panel animates with DMZ's own HUD classes (see {@code Animated}).
 *
 * <h2>Legacy path</h2>
 * Without DMZ's layout system the panel draws at its old top-left spot and {@code CustomStatHudScale}, gated by
 * {@code CustomStatHud}, and a FORGE-bus {@link RenderGuiOverlayEvent.Pre} handler cancels DMZ's main stat overlays
 * while it is on, leaving DMZ's other overlays (scouter item, technique charge, technique hotbar, beam clash) alone.
 * DMZ already cancels the vanilla health bar itself once a character exists.
 *
 * <h2>Client-only</h2>
 * The class is {@code Dist.CLIENT} guarded so a dedicated server never classloads it or the DMZ client stats accessors.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ScouterStatHudOverlay
{
    public static final String OVERLAY_ID = "scouter_stat_hud";

    // DMZ's main stat-HUD overlays, cancelled on the legacy path only (see onRenderOverlayPre).
    private static final ResourceLocation DMZ_XENOVERSE_HUD =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "xenoversehud");
    private static final ResourceLocation DMZ_ALTERNATIVE_HUD =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "alternativehud");

    // Reworked orb: the sheet's own 12x14 sphere, with a rectangular lens punched out of a copy so the live player
    // portrait still renders inside it exactly as before (backdrop -> portrait -> frame on top). The old 68x79 orb
    // cannot be used at native size, being five times the height of the whole new bar cluster.
    // Backdrop is the reworked sheet's orb; the frame stays the existing texture because its lens is punched to the
    // CIRCLE, not a rectangle, and the two are the same 68x79 design, so it lays over the new sphere exactly.
    private static final ResourceLocation TEX_ORB = rework("orb");
    private static final ResourceLocation TEX_ORB_FRAME = tex("orb_frame");
    private static final ResourceLocation TEX_TRANSFORM_ARC = tex("transform_arc");
    private static final ResourceLocation TEX_HEALTH_TRACK = tex("health_track");
    private static final ResourceLocation TEX_HEALTH_FILL = tex("health_fill");
    private static final ResourceLocation TEX_HEALTH_FILL_AMBER = tex("health_fill_amber");
    private static final ResourceLocation TEX_HEALTH_FILL_RED = tex("health_fill_red");
    private static final ResourceLocation TEX_KI_TRACK = tex("ki_track");
    private static final ResourceLocation TEX_KI_SEGMENTS = tex("ki_segments");
    private static final ResourceLocation TEX_STAMINA_TRACK = tex("stamina_track");
    private static final ResourceLocation TEX_STAMINA_FILL = tex("stamina_fill");
    // The two slanted glint strips that trim the bar cluster: one above the health bar, one below the ki blocks.
    // reason the original set was authored at 3x: every blit stays a strict 1:1 uniform copy, so nothing is ever
    // resampled at draw time and the pixels stay crisp at any HUD scale.
    private static final ResourceLocation TEX_RW_FRAME = rework("main_frame");
    private static final ResourceLocation TEX_RW_HEALTH = rework("health_fill");
    private static final ResourceLocation TEX_RW_HEALTH_AMBER = rework("health_fill_amber");
    private static final ResourceLocation TEX_RW_HEALTH_RED = rework("health_fill_red");
    // health_fill with its two bands in grey (255 / 171), tinted by DMZ's smooth health ramp so the loss and heal
    // chips can be drawn pale the way DMZ draws them; the coloured three stay for the static panel.
    private static final ResourceLocation TEX_RW_HEALTH_GREY = rework("health_fill_grey");
    private static final ResourceLocation TEX_RW_KI_TRACK = rework("ki_track");
    // Greyscale, because the aura tint below is a colour MULTIPLY: tinting the artwork's blue base would drag every
    // aura toward blue and a red aura would come out muddy purple.
    private static final ResourceLocation TEX_RW_KI_FILL = rework("ki_fill_grey");
    private static final ResourceLocation TEX_RW_STAM_TRACK = rework("stamina_track");
    private static final ResourceLocation TEX_RW_STAM_FILL = rework("stamina_fill");
    private static final ResourceLocation TEX_RW_ENERGY_TRACK = rework("energy_track");
    private static final ResourceLocation TEX_RW_DESTROYER = rework("destroyer_fill");
    private static final ResourceLocation TEX_RW_ANGEL = rework("angel_fill");
    private static final ResourceLocation TEX_RW_MALICE = rework("malice_fill");
    private static final ResourceLocation TEX_RW_ZENI_BAR = rework("zeni_bar");
    private static final ResourceLocation TEX_RW_ZENI_ORB = rework("zeni_orb");
    // The little gold "Z", which belongs INSIDE the coin. The pill beside it carries nothing but the number.
    private static final ResourceLocation TEX_RW_ZENI_Z = rework("zeni_z");

    /**
     * TWO different clouds, not one used twice. The sheet's parts area carries a wide one fused to the frame's
     * top-left corner and a separate narrower one off on its own; segmenting the sheet by connected component shows
     * the frame's component measuring 209x44 against the frame slice's own 209x31, which is the cloud riding along
     * with it. Cutting the gold out of that component gives the upper cloud, 58x26, and the loose slice is the
     * lower one, 58x25.
     *
     * <p>Both are shipped in the orientation they are DRAWN in, so each is a plain 1:1 blit. The lower one is the
     * loose slice mirrored, which is how it sits in the reference; GuiGraphics cannot flip a blit, and doing it with
     * a negative width would be a worse trick than shipping the pixels the right way round.
     */
    private static final ResourceLocation TEX_RW_GLINT_TOP = rework("decor_glint_top");
    private static final ResourceLocation TEX_RW_GLINT_LOW = rework("decor_glint_low");

    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;

    private static final int ORB_W = 68, ORB_H = 79;

    // ARC_Y nudged up 1px (58 -> 57) on the user's in-game read that the crescent sits one pixel too low. Note the artist
    // composite measures the crescent's topmost red at assembly y58 (so 58 matched the reference exactly), but the user is
    // looking at it live, so we honour their eye and shift it up by the one pixel they asked for.
    private static final int ARC_X = 11, ARC_Y = 57, ARC_W = 46, ARC_H = 12;

    // Health track/fill re-measured against the artist composite (assembly_reference.png at 3x, origin (6,6)). The track
    // texture is 154 wide and its right edge aligns with the assembly right edge, so the track is drawn at x62 (62+154=216).
    // The track carries a 2px pure-black left outline then a 1px white bevel then a navy (18,33,63) interior; the green fill
    // must start where that navy interior begins (track x62 + 3 = 65) or the exposed navy reads as a "black block" left of
    // the fill. So HEALTH_FILL_X is 65 (was 67): the fill now covers the navy from its first column and only the intended
    // 2px black + 1px white cap shows, exactly as in the reference. The fill right edge lands at 65+145=210, matching the
    // reference green (which ends at ~x210).
    private static final int HEALTH_TRACK_X = 62, HEALTH_TRACK_Y = 25, HEALTH_TRACK_W = 154, HEALTH_TRACK_H = 21;
    private static final int HEALTH_FILL_X = 65, HEALTH_FILL_Y = 29, HEALTH_FILL_W = 145, HEALTH_FILL_H = 13;

    // Ki track re-measured against the composite: the texture's navy body (texture x4..77, y4..11) lands on the assembly
    // navy at x63..135, y47..55, so the track top-left is (63-4, 47-4) = (59, 43).
    private static final int KI_TRACK_X = 59, KI_TRACK_Y = 43, KI_TRACK_W = 83, KI_TRACK_H = 16;
    // KI_SEG_Y nudged up 1px (49 -> 48) on the user's live in-game read; the navy housing (KI_TRACK_Y) does NOT move.
    private static final int KI_SEG_X = 67, KI_SEG_Y = 48, KI_SEG_W = 64, KI_SEG_H = 6;

    // Stamina track Y re-measured to 39 (was 40) by the same template match; the fill stays put.
    private static final int STAM_TRACK_X = 139, STAM_TRACK_Y = 39, STAM_TRACK_W = 73, STAM_TRACK_H = 8;
    private static final int STAM_FILL_X = 147, STAM_FILL_Y = 42, STAM_FILL_W = 58, STAM_FILL_H = 2;

    //
    // Right edges: every bar row in the reference ends at x249, checked row by row from y22 to y58. Since a sprite
    // at x with width w occupies x..x+w-1, the frame and the two right-hand tracks are placed to land on 249 rather
    // than on whatever an inset measurement suggested. That is what "the bars are not evenly lined up" was.
    //
    // Method, because it matters more than the numbers: each sprite was matched into the preview by exhaustive
    // pixel search over every offset. stamina_fill and angel_fill match at error 0.0 (exact), and orb, health_fill,
    // ki_fill and the zeni orb all land under 30. The TRACKS cannot be matched that way because their own fills
    // cover them, so each track is derived from geometry instead: its navy channel must hold its fill, so
    // track origin = fill origin - channel offset. That agrees with the search to within a pixel where both work.
    //
    // Rebuilding the panel from these numbers and diffing it against the preview leaves 7% of overlapping pixels
    // differing, and that residue is antialiasing plus the red transform arc, which is a state rather than a
    // position. Coverage matches too: 14696 opaque pixels against the reference's 14384.
    //
    // Coordinates are the PREVIEW's own, so the orb sits at y2 and nothing needs a negative offset (the zeni pill
    // genuinely sits above the orb's top at y1). Everything measured relative to the orb therefore adds ORB_Y.
    private static final int ORB_X = 0, ORB_Y = 2;

    private static final int RW_FRAME_X = 41, RW_FRAME_Y = 19, RW_FRAME_W = 209, RW_FRAME_H = 31;
    private static final int RW_HEALTH_X = 58, RW_HEALTH_Y = 26, RW_HEALTH_W = 188, RW_HEALTH_H = 20;
    private static final int RW_KI_TRACK_X = 56, RW_KI_TRACK_Y = 48, RW_KI_TRACK_W = 114, RW_KI_TRACK_H = 18;
    private static final int RW_KI_FILL_X = 60, RW_KI_FILL_Y = 52, RW_KI_FILL_W = 106, RW_KI_FILL_H = 10;
    private static final int RW_STAM_TRACK_X = 168, RW_STAM_TRACK_Y = 35, RW_STAM_TRACK_W = 82, RW_STAM_TRACK_H = 15;
    private static final int RW_STAM_FILL_X = 177, RW_STAM_FILL_Y = 38, RW_STAM_FILL_W = 69, RW_STAM_FILL_H = 8;
    private static final int RW_ENERGY_TRACK_X = 168, RW_ENERGY_TRACK_Y = 48, RW_ENERGY_TRACK_W = 82, RW_ENERGY_TRACK_H = 18;
    private static final int RW_ENERGY_X = 172, RW_ENERGY_Y = 52, RW_ENERGY_W = 74, RW_ENERGY_H = 10;
    // The pill sprite is 80 wide but only its right 69 columns are ever seen: it has a rounded cap on the right and a
    // hard cut on the left, because the left end is meant to disappear behind the coin. The reference proves it,
    // every row of the zeni assembly there starts at x183, which is the coin's own left edge, with nothing at all in
    // 172..182. Blitting the whole sprite hangs 11 columns of navy out to the left of the coin, which is what the
    // stray tab on the left of the zeni bar was. So the sprite is drawn from u=11 at the coin's left edge; the right
    // cap still lands on 251 either way, since 183+69-1 == 172+80-1.
    //
    // The whole cluster then shifts right by RW_ZENI_NUDGE_X, which is a deliberate departure from the reference
    // rather than a measurement: the coin sat too close to the health bar's right end in play. One constant so it
    // stays one number to change.
    private static final int RW_ZENI_NUDGE_X = 4;
    private static final int RW_ZENI_BAR_X = 183 + RW_ZENI_NUDGE_X, RW_ZENI_BAR_Y = 1,
            RW_ZENI_BAR_W = 80, RW_ZENI_BAR_H = 20;
    private static final int RW_ZENI_BAR_U = 11;
    private static final int RW_ZENI_BAR_DRAW_W = RW_ZENI_BAR_W - RW_ZENI_BAR_U;
    /**
     * The coin sits further left than the pill it caps, so it reads as a medallion beside the housing rather than a
     * disc stuck on its end. Separate from {@link #RW_ZENI_NUDGE_X} because it moves the coin ALONE.
     *
     * <p>It has to stay large enough to keep covering the pill's hard left cut. The pill is drawn from u=11 at
     * {@link #RW_ZENI_BAR_X}, so the coin must still reach that column: the coin spans 22px from
     * {@code 183 + NUDGE - OFFSET}, which covers the cut as long as the offset stays under 22.
     */
    private static final int RW_ZENI_ORB_OFFSET_X = 10;

    private static final int RW_ZENI_ORB_X = 183 + RW_ZENI_NUDGE_X - RW_ZENI_ORB_OFFSET_X, RW_ZENI_ORB_Y = 0,
            RW_ZENI_ORB_S = 22;
    // The Z is 4x8 and centres in the coin (coin 183..204, so centre 194). An earlier slice started three rows
    // too low and cut the glyph's top bar off. It travels with the coin, so it carries the same offset.
    private static final int RW_ZENI_Z_X = 192 + RW_ZENI_NUDGE_X - RW_ZENI_ORB_OFFSET_X, RW_ZENI_Z_Y = 7,
            RW_ZENI_Z_W = 4, RW_ZENI_Z_H = 8;
    // The number is LEFT aligned from just past the coin and runs into the pill, showing every digit. The coin
    // already carries the currency mark, so the text is digits only.
    //
    // Derived from the coin rather than written down, so it cannot drift out of step with it: the coin has moved
    // twice already and each time this was left behind, opening a band of empty navy between the two. Two pixels of
    // air after the coin's right edge, and the far end at the pill's inner right.
    private static final int ZENI_TEXT_LEFT = RW_ZENI_ORB_X + RW_ZENI_ORB_S + 2;
    private static final int ZENI_TEXT_RIGHT = RW_ZENI_BAR_X + RW_ZENI_BAR_DRAW_W - 2;
    private static final float ZENI_TEXT_Y = RW_ZENI_BAR_Y + RW_ZENI_BAR_H / 2.0f;
    private static final float ZENI_TEXT_SCALE = 0.75f;
    private static final int ZENI_TEXT_COLOR = 0xFFFFD34D;

    //
    // SHARDS: a twin of the zeni cluster, immediately to its left. The premium currency (internally "argent",
    // see ArgentCurrency for why the two names exist), pushed to this client by PacketShardSync.
    //
    // THERE IS ONLY ONE LAYOUT TO FOLLOW, and that is worth stating because DMZ ships two HUD styles. Both of
    // them (dragonminez:xenoversehud and dragonminez:alternativehud) are cancelled by onRenderOverlayPre while
    // SUConfig.customStatHud is on, and DMZ has no currency of its own: there is not one zeni glyph anywhere in
    // dragonminez-2.1.3.jar. So the only place a currency figure is ever drawn is this panel, and following the
    // zeni cluster means following these constants. With the custom HUD switched off, DMZ's own style comes back
    // and neither currency is drawn at all.
    //
    // Every number here is DERIVED from the zeni block above rather than measured again, so the two cannot drift:
    // the zeni coin has already moved twice, and each time something that was written down separately was left
    // behind.
    //
    // WHAT IS BLUE. The pill and the coin are reused as they are, because the shipped art is ALREADY navy blue
    // with a periwinkle rim; the only gold in the zeni cluster is the Z and the figure. So "blue" lands exactly
    // there: a blue currency mark in the Z's place and a blue figure, with identical housing, size and spacing.
    // No new texture is involved.
    private static final int RW_ZENI_ASSEMBLY_W = RW_ZENI_ORB_OFFSET_X + RW_ZENI_BAR_DRAW_W;

    /** Air between the shards pill's right cap and the zeni coin's left edge. */
    private static final int RW_SHARD_GAP_X = 2;

    private static final int RW_SHARD_ORB_X = RW_ZENI_ORB_X - RW_SHARD_GAP_X - RW_ZENI_ASSEMBLY_W;
    private static final int RW_SHARD_ORB_Y = RW_ZENI_ORB_Y;
    private static final int RW_SHARD_BAR_X = RW_SHARD_ORB_X + RW_ZENI_ORB_OFFSET_X;
    private static final int RW_SHARD_BAR_Y = RW_ZENI_BAR_Y;

    private static final int SHARD_TEXT_LEFT = RW_SHARD_ORB_X + RW_ZENI_ORB_S + 2;
    private static final int SHARD_TEXT_RIGHT = RW_SHARD_BAR_X + RW_ZENI_BAR_DRAW_W - 2;
    private static final float SHARD_TEXT_Y = RW_SHARD_BAR_Y + RW_ZENI_BAR_H / 2.0f;

    /**
     * The currency mark, drawn from the VANILLA FONT rather than from a sprite.
     *
     * <p>The font's dollar sign is five pixels wide and seven tall against the Z sprite's four by eight, so it
     * sits in the same slot at the same weight, and it needs no art to ship, no resource pack to override and no
     * second thing to keep in step when the coin moves. A sprite would only be worth it for the Z's three-tone
     * bevel, and at this size that bevel is three pixels.
     */
    private static final String SHARD_MARK = "$";

    /**
     * Ink row of the mark. The glyph's own ink is seven rows deep starting at the draw position, so eight puts
     * its middle on the coin's middle (the coin is 22 tall from {@link #RW_SHARD_ORB_Y}).
     */
    private static final int SHARD_MARK_DY = 8;

    /**
     * The blue, for both the mark and the figure.
     *
     * <p>It is the zeni gold with its HUE ROTATED and nothing else touched: {@code 0xFFD34D} is hue 45 at 70%
     * saturation and full value, and this is hue 205 at the same 70% and the same full value. That is what keeps
     * the two clusters reading as a matched pair rather than as two unrelated colours, and it is why this is a
     * light blue and not a mid one. A mid blue at this size would sit at the navy housing's own luminance and
     * the figure would stop being readable.
     */
    private static final int SHARD_TEXT_COLOR = 0xFF4DB5FF;

    //
    // ONE track, recoloured for whichever role bar the viewing player currently has, because the roles are held one
    // at a time; the server decides which that is and pushes only that one (EnergyClientCache). A player with no
    // role has no kind, and the track is not drawn at all rather than drawn empty, so the layout is unchanged for
    // everyone who is not a shadow dragon, a G.O.D. or an Angel.
    //
    // The scale is the flat 0..100 of EnergyManager.MAX, not a DMZ stat, so the fraction is simply value/100.

    /** Fill sprite for the active role bar, or null when the player has no role. */
    private static ResourceLocation energyFillTexture()
    {
        net.shurui.shuruisutilities.energy.EnergyKind kind = EnergyClientCache.kind();
        if (kind == null)
            return null;
        return switch (kind)
        {
            case MALICE -> TEX_RW_MALICE;
            case DESTRUCTION -> TEX_RW_DESTROYER;
            case ANGELIC -> TEX_RW_ANGEL;
        };
    }

    /** Readout colour for the active role bar: the light upper band of its fill, matching the other bars. */
    private static int energyFillRgb()
    {
        net.shurui.shuruisutilities.energy.EnergyKind kind = EnergyClientCache.kind();
        if (kind == null)
            return FILL_ANGEL_RGB;
        return switch (kind)
        {
            case MALICE -> FILL_MALICE_RGB;
            case DESTRUCTION -> FILL_DESTROYER_RGB;
            case ANGELIC -> FILL_ANGEL_RGB;
        };
    }

    /** Live fraction of the active role bar, 0 when there is no role. */
    private static float energyFrac()
    {
        if (EnergyClientCache.kind() == null)
            return 0.0f;
        return clamp01(EnergyClientCache.value() / net.shurui.shuruisutilities.energy.EnergyManager.MAX);
    }

    //
    // Same font and size as the zeni amount so the panel has one voice, but NO black outline: the whole point of
    // these glyphs is that they are the exact inverse of what they sit on, and an outline would put a third colour
    // between the text and its background and undo that.
    private static final float BAR_TEXT_SCALE = ZENI_TEXT_SCALE;

    // Colours measured off the shipped art, since inverting "the colour behind" means knowing what is behind. Every
    // track is the same navy. Each fill sprite is two-toned, a light upper band and a darker lower one, and the
    // light band is both the larger of the two and the one the glyphs mostly sit on, so it is the one inverted;
    // against the darker band the same inverse still reads, because the two bands are the same hue.
    private static final int BAR_TRACK_RGB = 0x12213F;
    private static final int FILL_HEALTH_RGB = 0x0CFF00;
    private static final int FILL_HEALTH_AMBER_RGB = 0xFFBB00;
    private static final int FILL_HEALTH_RED_RGB = 0xFF001D;
    private static final int FILL_KI_RGB = 0xA8F8FF;
    private static final int FILL_STAM_RGB = 0xF17D00;
    private static final int FILL_ANGEL_RGB = 0xD8FDFF;
    private static final int FILL_DESTROYER_RGB = 0xFF60F6;
    private static final int FILL_MALICE_RGB = 0xF02020;

    /**
     * How far left of centre the health readout sits, in panel pixels.
     *
     * <p>The health channel is by far the widest and its left end runs behind the orb, so a number centred in it
     * lands out in the middle of a long green expanse with nothing near it. Pulling it left seats it over the part
     * of the bar the eye is already on. It stays clear of the orb, which ends at x67: at this bias the string starts
     * around x95.
     */
    private static final int RW_HEALTH_TEXT_BIAS_X = -40;

    // Cloud positions, each pinned by matching its sprite against ONLY the part of the preview nothing covers: for
    // the upper cloud the rows above the frame and the columns clear of the orb, for the lower one the rows below
    // the bars. Scoring the whole sprite is what produced the earlier wrong answer, because most of each cloud is
    // hidden and the occluded pixels drowned the signal. Restricted that way the upper cloud reads 96% at (48,5)
    // and the lower one 100% at (198,50), both unambiguous.
    //
    // Both draw BEFORE everything else. The upper cloud's own gold starts at its x0 but the preview shows none of
    // it until x53, so its first five columns are behind the orb; the lower one shows nothing above y66, so its top
    // sixteen rows are behind the bars. Rebuilding with the clouds last instead nearly doubles the difference from
    // the reference, 7.5% against 3.9%.
    //
    // The upper cloud CANNOT be nudged left. It is drawn before the orb, and the orb is opaque out to x67, so every
    // pixel it moves left is a pixel the orb eats: pulling it 4px over took a bite out of the cloud's left side and
    // read as the cloud having holes in it. The reference already hides its first five columns that way, which is
    // as far as this goes. Moving it left would mean drawing it in FRONT of the orb, laying gold over the crystal
    // ball, so the measured position stands.
    // The lower cloud DELIBERATELY departs from its measured x. The reference art was composed with the role energy
    // bar present, so 198 tucked the cloud under that fourth bar (track x168 to x250, y48 to y66). Only a player
    // holding MALICE, DESTRUCTION or ANGELIC has one: for everyone else drawEnergyBars returns before drawing and
    // the cloud is left attached to nothing, sitting in the gap where the bar would have been. Centred under the
    // stamina track instead (168 + (82 - 58) / 2 = 180) it reads as part of the bar cluster whether or not the
    // fourth bar is there. The y is untouched at 50, so the vertical relationship the measurement established, and
    // the sixteen rows that hide behind the bars, are exactly as before.
    private static final int RW_GLINT_TOP_X = 48, RW_GLINT_TOP_Y = 5, RW_GLINT_TOP_W = 58, RW_GLINT_TOP_H = 26;
    private static final int RW_GLINT_LOW_X = 198, RW_GLINT_LOW_Y = 50, RW_GLINT_LOW_W = 58, RW_GLINT_LOW_H = 25;

    // Where the lower cloud goes when the player has NO fourth bar. The measured position above assumes the role
    // energy bar (track y 48 to 66) is there to hide the cloud's top rows; only a MALICE, DESTRUCTION or ANGELIC
    // holder has one, and for everyone else the cloud was left hanging in the gap where that bar would have been.
    // Tucking it under the STAMINA track instead (x 168 to 250, y 35 to 50) gives it the same "grows out from
    // behind a bar" read: aligning its top with the stamina track's top hides the same rows the energy bar used to,
    // and 180 centres its 58px width under the 82px track. A static move would have been wrong in the other
    // direction, detaching it for every player who DOES have the fourth bar.
    private static final int RW_GLINT_LOW_NOBAR_X = 180, RW_GLINT_LOW_NOBAR_Y = RW_STAM_TRACK_Y;

    // ki_segments.png (64x6) holds ten pale blocks separated by 1px dark alpha gaps. The earlier claim that only the top
    // and bottom edge rows carry those gaps was WRONG: scanning the texture shows every gap column has low alpha down the
    // FULL height (e.g. column 5 alpha reads 6,37,51,51,46,11 across the six rows), so the separators are baked into the
    // strip at full height. The reference composite proves the intended draw is simply the WHOLE strip blitted 1:1 over the
    // navy track: a pixel compare of "strip over navy (18,33,63)" against the reference's own ki row is within 3x-AA noise.
    // So we do exactly that, clipped left-to-right by the number of lit blocks, instead of the old ten per-block sub-rect
    // blits with a hardcoded width of 4 (that width clipped every 5-6px square down to a narrow bar and misplaced several by
    // a pixel, which is why the blocks "did not look like the sections"). KI_FILL_END[n-1] is the strip width to draw for n
    // lit blocks: the gap-column x that falls just after block n-1, derived from the texture's separator columns
    // (5,12,18,25,31,38,44,51,57) with the full 64 for all ten. Derived true block spans (start..end, inclusive) are
    // 0..4, 6..11, 13..17, 19..24, 26..30, 32..37, 39..43, 45..50, 52..56, 58..63 (widths 5/6, never 4).
    private static final int KI_BLOCKS = 10;
    private static final int[] KI_FILL_END = {5, 12, 18, 25, 31, 38, 44, 51, 57, 64};

    // Lens window (the punched interior of orb_frame, 68x79) in assembly-local coords. These are the MEASURED bounds of
    // the transparent window, not the naive circle box: the window is the lens circle minus the pale dome cap that
    // overlaps its top and minus the navy band arcing across its lower third, so it is shorter vertically than a circle.
    private static final int LENS_X0 = 7, LENS_Y0 = 16, LENS_X1 = 60, LENS_Y1 = 61;
    private static final int LENS_W = LENS_X1 - LENS_X0; // 53
    private static final int LENS_H = LENS_Y1 - LENS_Y0; // 45

    // The portrait is rendered ONCE into an offscreen framebuffer and blitted every frame, so the world player is never
    // touched. The rendered subject is a never-ticked stand-in entity (see PreviewStandIn), NOT mc.player, so the portrait
    // sits in the stock idle pose and never samples the world player mid-animation. The target's dimensions are the lens
    // window scaled by an integer supersample, so the cached texture -> lens blit is a pure uniform downscale with no
    // stretch. It is deliberately NOT power-of-two: matching the lens aspect exactly is the harder constraint, and
    // Minecraft's GL 3.2 context supports non-power-of-two targets. 8x of a 53x45 window (424x360) is crisp at any HUD scale.
    private static final int PREVIEW_SUPERSAMPLE = 8;
    private static final int PREVIEW_TARGET_W = LENS_W * PREVIEW_SUPERSAMPLE; // 424
    private static final int PREVIEW_TARGET_H = LENS_H * PREVIEW_SUPERSAMPLE; // 360

    // On-screen pixels per block for the entity render, before the config scale. The lens window is x7..60, y16..61 (53x45).
    // renderEntityInInventory places the FEET at feetY and the figure extends upward, so with the head-top anchored at
    // PREVIEW_HEAD_TOP_Y the visible span head-top..lens-bottom decides how much of the body shows. 40 (with the default
    // 0.9375 model scaling) pulls the camera back a touch further than the old 44 so the whole figure sits smaller inside
    // the lens: this both shows a little more torso and shrinks the hat/outer skin layer's overshoot above the base head in
    // absolute pixels, which was the cause of the crown being clipped by the lens top. Raise for a tighter head shot, lower
    // to pull further back and show more torso.
    private static final int PREVIEW_SCALE = 40;
    // Where the TOP of the head lands inside the lens, in assembly-local pixels. The head-top anchor here is the model's
    // bounding-box top, but the hat/outer skin layer and hair extend a couple of pixels ABOVE that box, which is exactly why
    // the old 22 (a nominal 6px below the y16 lens top) still clipped the crown. 24 puts the box-top a full 8px below the
    // lens top so the hat layer clears it with margin and the whole head is visible. feetY is derived from this plus the
    // model height, so this directly controls the head-top, not the head-centre. Nudged 24 -> 34 (down 10px assembly-local)
    // on the user's live in-game read: the figure now sits lower in the lens exactly where they asked.
    private static final int PREVIEW_HEAD_TOP_Y = 34;
    // Horizontal centre of the preview inside the orb (lens window x7..60 -> centre 33.5, rounded to 34).
    private static final int PREVIEW_CENTER_X = 34;

    // DMZ (XenoverseHUD.drawScaledText -> TextUtil.drawCenteredStringWithBorder) draws HUD numbers with the vanilla font,
    // scaled, with NO vanilla drop shadow but a 4-way 1px outline: the text stamped at x+-1 and y+-1 in opaque black, then
    // once on top in the text colour. We reproduce that exactly (same border pattern, same opaque-black outline) so SU's
    // readout looks native beside DMZ's own HUD text. DMZ's HUD numbers render at 0.5 scale.
    private static final float HUD_TEXT_SCALE = 0.5f;

    // Pale dome cap INTERIOR re-measured off orb.png (orb sits at assembly 0,0): the pale oval runs x17..50 (34px wide) at
    // its widest, y9..12, narrowing toward the rounded top (w9 at y2) and merging into the frame ring by y13; the usable
    // text band is roughly y7..13, centred on x33.5. The oval is far wider than it is tall, so the vertical extent is the
    // binding constraint, not the width. The user asked to nudge the % down and make it a tad bigger so it fills the oval.
    // RELEASE_OVAL_CY moves from 7.8 to 9.5 (down into the widest part of the oval) and RELEASE_TEXT_SCALE lifts the text
    // from DMZ's 0.5 to 0.65: at 0.65 a four-glyph string like "115%" is about 15px wide and 5.9px tall, so it sits inside
    // the 34px-wide band with a comfortable margin and never touches the black outline top or bottom. The text is centred on
    // (cx, cy) in BOTH axes inside the scaled pose (drawBorderedCentered offsets by half the text width/height inside the
    // same scale). Colour is DMZ's own power-release pink (#FACAF7) with the opaque-black outline, matching XenoverseHUD.
    // Nudged from the user's live read: RIGHT 1 (33.5 -> 34.5) and DOWN 3 (9.5 -> 12.5), assembly-local. Then the user's
    // latest live read moved it back UP 2 (12.5 -> 10.5); honoured directly, not re-derived.
    private static final float RELEASE_OVAL_CX = 34.5f;
    private static final float RELEASE_OVAL_CY = 10.5f;
    private static final float RELEASE_TEXT_SCALE = 0.65f;
    private static final int RELEASE_TEXT_COLOR = 0xFFFACAF7;

    // ki_segments.png is native white with a mild top-to-bottom shading ramp and its block edges are the alpha gaps between
    // blocks, so a colour multiply recolours the RGB while the shading and those alpha-defined edges survive untouched (the
    // multiply never touches alpha, so it can NOT override the squares or the gaps: the geometry was the fault, not the
    // tint). The tint was raised from the old muted (0.55, 0.74, 1.0) to (0.65, 0.85, 1.0) so the lit squares read as a
    // genuinely bright sky-blue that pops off the navy housing (roughly RGB 18,33,63) instead of blending into it. Because
    // the multiply drawn colour is source(~248) * tint * brightness, brightness is raised by lifting red and green toward the
    // pinned blue channel (lowering channels only darkens). Brightness scales 0.80..1.00 with the ki fraction (the floor was
    // lifted from 0.60 so a nearly-empty bar is still clearly visible), so the bar still glows brighter as ki rises.
    private static final float KI_BLUE_R = 0.65f, KI_BLUE_G = 0.85f, KI_BLUE_B = 1.0f;

    private static final float HEALTH_GREEN_ABOVE = 0.5f;   // >= 50% healthy: green fill
    private static final float HEALTH_AMBER_ABOVE = 0.25f;  // 25%..50% wounded: amber fill; below 25% critical: red


    // The framebuffer holding the last-captured portrait, the never-ticked stand-in we render into it, and the keys that
    // decide when to re-capture. previewPlayerRef is WEAK so a respawn/relog LocalPlayer can be garbage-collected; identity
    // mismatch against it forces a fresh stand-in and a re-capture. previewFormKey holds the last captured appearance
    // signature (race/gender/form/stack-form); a mismatch re-copies the stand-in's stats and re-captures. There is no
    // periodic refresh: the portrait is a still image that only changes when the character's appearance changes.
    // previewEquip holds the last-captured equipment snapshot the appearance check also compares against: indices 0..3
    // are the real armor slots (HEAD, CHEST, LEGS, FEET) and 4..7 the matching cosmetic-armor overrides (Cosmetic Armor
    // Reworked, read through DMZ's classload-safe CosmeticArmorCompat, which is the only cosmetic layer that draws on the
    // model; SU's curios have no body renderer). They are COPIES, not live references, so an in-place tag change is still
    // caught by the per-frame value compare; a mismatch re-equips the stand-in and re-captures.
    private static RenderTarget previewTarget;
    private static PreviewStandIn previewEntity;
    private static java.lang.ref.WeakReference<LocalPlayer> previewPlayerRef;
    private static String previewFormKey;
    private static final ItemStack[] previewEquip = new ItemStack[8];

    // The four armor EquipmentSlots, held in a constant so the per-frame equipment comparison never allocates an array.
    private static final EquipmentSlot[] PREVIEW_ARMOR_SLOTS =
            { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private ScouterStatHudOverlay() {}

    private static ResourceLocation tex(String name)
    {
        return ResourceLocation.fromNamespaceAndPath(ShuruisUtilities.MODID, "textures/gui/hud/" + name + ".png");
    }

    private static ResourceLocation rework(String name)
    {
        return ResourceLocation.fromNamespaceAndPath(ShuruisUtilities.MODID, "textures/gui/hud/rework/" + name + ".png");
    }

    // The cancelled DMZ main HUDs on the LEGACY path only (DMZ's layout system unavailable). With DMZ's system the
    // Ragnarok style is the switch: each DMZ main HUD draws only for its own style, so nothing needs cancelling.
    private static final ResourceLocation DMZ_MODERN_HUD = ResourceLocation.fromNamespaceAndPath("dragonminez", "modernhud");
    private static final ResourceLocation DMZ_MINECRAFT_HUD = ResourceLocation.fromNamespaceAndPath("dragonminez", "minecrafthud");

    @SubscribeEvent
    public static void onRenderOverlayPre(RenderGuiOverlayEvent.Pre event)
    {
        if (RagnarokHud.available() || !SUConfig.customStatHud)
            return;
        ResourceLocation id = event.getOverlay().id();
        if (DMZ_XENOVERSE_HUD.equals(id) || DMZ_ALTERNATIVE_HUD.equals(id) || DMZ_MODERN_HUD.equals(id)
                || DMZ_MINECRAFT_HUD.equals(id))
            event.setCanceled(true);
    }

    /**
     * Overlay hook, registered in SUClientMenus: the stat panel and the currency cluster, each a separate element of
     * DMZ's HUD layout (the Ragnarok style), positioned, scaled and animated by {@link RagnarokHud}.
     */
    public static void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight)
    {
        RagnarokHud.draw(RagnarokHudSlot.STATS, g, partialTick, screenWidth, screenHeight);
        RagnarokHud.draw(RagnarokHudSlot.CURRENCY, g, partialTick, screenWidth, screenHeight);
    }

    /** Where the currency cluster starts on the assembled panel, in panel pixels (its x offset from the panel). */
    public static int currencyOffsetX()
    {
        return CURRENCY_X0;
    }

    /** Bind the stat panel and the currency cluster to their Ragnarok HUD slots. */
    public static void registerElements()
    {
        // only consulted when DMZ's layout system is unavailable: the old CustomStatHud switch
        RagnarokHud.setFallbackMainEnabled(() -> SUConfig.customStatHud);
        RagnarokHud.register(new StatsElement());
        RagnarokHud.register(new CurrencyElement());
    }

    // The stat panel's box: orb at x0 to the frame's right end, orb top to orb bottom.
    private static final int STATS_W = RW_FRAME_X + RW_FRAME_W;
    private static final int STATS_H = ORB_Y + ORB_H;
    // The currency cluster's box, cut out of the panel's coordinates: the shards coin's left edge to the zeni pill's cap.
    private static final int CURRENCY_X0 = RW_SHARD_ORB_X;
    private static final int CURRENCY_W = RW_ZENI_BAR_X + RW_ZENI_BAR_DRAW_W - RW_SHARD_ORB_X;
    private static final int CURRENCY_H = RW_ZENI_ORB_S;

    // Sample figures for DMZ's HUD editor when there is no live value to show.
    private static final long PREVIEW_ZENI = 1_250_000L;
    private static final long PREVIEW_SHARDS = 4_200L;

    /**
     * DMZ 2.2's HUD animation classes (HudBar, HudSmoother, HudStatNumberAnimator), probed once. With them the panel
     * animates exactly like DMZ's own HUD; without them (a DMZ that moved them) it draws the static panel.
     */
    private static final boolean ANIMATED = probeAnimated();

    private static boolean probeAnimated()
    {
        try
        {
            ClassLoader cl = ScouterStatHudOverlay.class.getClassLoader();
            for (String name : new String[] {"HudBar", "HudBarAnimator", "HudSmoother", "HudStatNumberAnimator",
                    "HudRender", "HudSprites$Sprite", "SurgeBarState"})
                Class.forName("com.dragonminez.client.gui.hud." + name, false, cl);
            return true;
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** What the panel draws this frame, captured by {@link #snapshot}. */
    private static final class Snapshot
    {
        StatsData data;
        float health, maxHealth = 1.0f;
        float energy, maxEnergy = 1.0f;
        float stamina, maxStamina = 1.0f;
        float transform;
        int release;
        int aura = 0x7FD4FF;
        boolean chargingKi;
    }

    private static final Snapshot SNAP = new Snapshot();

    /** Refresh {@link #SNAP} from the live player. Returns false (keep the last snapshot) before a character exists. */
    private static boolean snapshot(LocalPlayer player, boolean preview)
    {
        if (player == null)
            return false;
        StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        Status status = stats == null ? null : stats.getStatus();
        Resources resources = stats == null ? null : stats.getResources();
        if (status == null || resources == null || !status.isHasCreatedCharacter())
        {
            if (!preview)
                return false;
            // the editor still needs something to drag before a character exists
            SNAP.data = null;
            SNAP.maxHealth = 20.0f;
            SNAP.health = 14.0f;
            SNAP.maxEnergy = SNAP.maxStamina = 100.0f;
            SNAP.energy = 65.0f;
            SNAP.stamina = 80.0f;
            SNAP.transform = 0.0f;
            SNAP.release = 50;
            SNAP.chargingKi = false;
            return true;
        }
        SNAP.data = stats;
        // HEALTH: vanilla attribute, matching DMZ's own HUD (it reads the vanilla max-health attribute too).
        SNAP.maxHealth = Math.max(1.0f, (float) player.getAttributeValue(Attributes.MAX_HEALTH));
        SNAP.health = player.getHealth();
        // KI and STAMINA: DMZ resource values over the computed maxima.
        SNAP.maxEnergy = Math.max(1.0f, stats.getMaxEnergy());
        SNAP.energy = resources.getCurrentEnergy();
        SNAP.maxStamina = Math.max(1.0f, stats.getMaxStamina());
        SNAP.stamina = resources.getCurrentStamina();
        // TRANSFORMATION: the charge-toward-transform meter, 0..100; idles at 0 and only fills while charging a form.
        SNAP.transform = clamp01(resources.getActionCharge() / 100.0f);
        SNAP.release = resources.getPowerRelease();
        SNAP.aura = auraRgb(stats.getCharacter());
        SNAP.chargingKi = status.isChargingKi();
        return true;
    }

    /** The orb, the bars and the readouts: the Ragnarok style's main element. */
    private static final class StatsElement extends RagnarokHudElement
    {
        StatsElement()
        {
            super(RagnarokHudSlot.STATS);
        }

        @Override
        public float width()
        {
            return STATS_W;
        }

        @Override
        public float height()
        {
            return STATS_H;
        }

        @Override
        public float[] defaultPlacement()
        {
            // the panel's old home: top-left, MARGIN in
            return new float[] {0.0f, 0.0f, MARGIN_X, MARGIN_Y, 1.0f};
        }

        @Override
        public float[] fallbackBox(int screenWidth, int screenHeight)
        {
            return new float[] {MARGIN_X, MARGIN_Y, (float) SUConfig.customStatHudScale};
        }

        @Override
        public Appear appear()
        {
            return Appear.FADE;
        }

        @Override
        public boolean update(boolean preview, float partialTick)
        {
            return snapshot(Minecraft.getInstance().player, preview);
        }

        @Override
        public void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame)
        {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null)
                return;
            if (ANIMATED)
                Animated.drawStats(g, mc, player, SNAP, partialTick, frame.preview);
            else
                drawStatsStatic(g, mc, player, SNAP);
        }
    }

    /** The shards and zeni pills: their own element, so they can be moved off the panel. */
    private static final class CurrencyElement extends RagnarokHudElement
    {
        CurrencyElement()
        {
            super(RagnarokHudSlot.CURRENCY);
        }

        @Override
        public float width()
        {
            return CURRENCY_W;
        }

        @Override
        public float height()
        {
            return CURRENCY_H;
        }

        @Override
        public float[] defaultPlacement()
        {
            // exactly where the cluster sat in the assembled panel
            return new float[] {0.0f, 0.0f, MARGIN_X + CURRENCY_X0, MARGIN_Y, 1.0f};
        }

        @Override
        public float[] fallbackBox(int screenWidth, int screenHeight)
        {
            float scale = (float) SUConfig.customStatHudScale;
            return new float[] {MARGIN_X + CURRENCY_X0 * scale, MARGIN_Y, scale};
        }

        @Override
        public boolean listed()
        {
            // both currencies are private (economy, cosmetic shards): absent keyless, editor included
            return ClientGate.key();
        }

        @Override
        public Appear appear()
        {
            return Appear.FADE;
        }

        @Override
        public boolean update(boolean preview, float partialTick)
        {
            return preview || ZeniClientCache.known() || ShardClientCache.known();
        }

        @Override
        public void render(GuiGraphics g, float partialTick, RagnarokHudFrame frame)
        {
            Minecraft mc = Minecraft.getInstance();
            boolean preview = frame.preview;
            // a pill draws nothing at all until the server has said what the balance is (see drawZeni / drawShards)
            Long shards = ShardClientCache.known() ? Long.valueOf(ShardClientCache.get()) : preview ? Long.valueOf(PREVIEW_SHARDS) : null;
            Long zeni = ZeniClientCache.known() ? Long.valueOf(ZeniClientCache.get()) : preview ? Long.valueOf(PREVIEW_ZENI) : null;
            g.pose().pushPose();
            g.pose().translate(-CURRENCY_X0, 0.0f, 0.0f);
            try
            {
                if (ANIMATED)
                    Animated.drawCurrency(g, mc, zeni, shards, partialTick);
                else
                {
                    if (shards != null)
                        drawShards(g, mc, grouped(shards), SHARD_TEXT_COLOR, 0.0f, 0.0f);
                    if (zeni != null)
                        drawZeni(g, mc, grouped(zeni), ZENI_TEXT_COLOR, 0.0f, 0.0f);
                }
            }
            finally
            {
                g.pose().popPose();
            }
        }
    }

    /** The panel without DMZ's animation classes: the old static draw, in the element's local space. */
    private static void drawStatsStatic(GuiGraphics g, Minecraft mc, LocalPlayer player, Snapshot s)
    {
        float healthFrac = clamp01(s.health / s.maxHealth);
        float kiFrac = clamp01(s.energy / s.maxEnergy);
        float stamFrac = clamp01(s.stamina / s.maxStamina);

        // Draw order matches the artist composite: the bars and their fills draw FIRST so the orb laps over their left
        // ends, the crescent after the orb (the orb texture is opaque), the release % last on the dome cap. Glints
        // first: the reference shows the bar's top edge cutting across the upper glint.
        drawGlintBelow(g);
        drawGlintAbove(g);
        drawFrameAndHealth(g, healthFrac);
        drawKi(g, kiFrac, s.aura);
        drawStamina(g, stamFrac);
        drawEnergyBars(g);

        drawOrbAndPreview(g, mc, player);
        drawTransformArc(g, s.transform);
        drawReleasePercent(g, mc, s.release);

        // Values last, so nothing drawn later can cover a number, and gated on DMZ's own hide-HUD-numbers option.
        if (!dmzHudNumbersHidden())
        {
            drawBarValue(g, mc, amount(s.health, s.maxHealth), RW_HEALTH_X, RW_HEALTH_Y, RW_HEALTH_W, RW_HEALTH_H,
                    RW_HEALTH_TEXT_BIAS_X, 0.0f, 0.0f, 0xFFFFFF, 1.0f);
            drawBarValue(g, mc, amount(s.energy, s.maxEnergy), RW_KI_FILL_X, RW_KI_FILL_Y, RW_KI_FILL_W, RW_KI_FILL_H,
                    0, 0.0f, 0.0f, 0xFFFFFF, 1.0f);
            drawBarValue(g, mc, amount(s.stamina, s.maxStamina), RW_STAM_FILL_X, RW_STAM_FILL_Y, RW_STAM_FILL_W, RW_STAM_FILL_H,
                    0, 0.0f, 0.0f, 0xFFFFFF, 1.0f);
            // Role energy reads directly as points out of a flat 100, its real unit (EnergyManager.MAX).
            if (EnergyClientCache.kind() != null)
                drawBarValue(g, mc, amount(EnergyClientCache.value(), net.shurui.shuruisutilities.energy.EnergyManager.MAX),
                        RW_ENERGY_X, RW_ENERGY_Y, RW_ENERGY_W, RW_ENERGY_H, 0, 0.0f, 0.0f, 0xFFFFFF, 1.0f);
        }
    }

    /**
     * The panel animated with DragonMineZ 2.2's own HUD machinery, so it moves exactly like DMZ's HUD: bars ease toward
     * their value (tau 0.55 s), a lost chunk lingers as a pale chip for 1.5 s and then drains (tau 0.32 s), a burst
     * heal shows a breathing green chip while the bar catches up, a hit flashes the health fill white and shakes the
     * panel, a glint rides the moving edge, a shine sweeps each bar every 5.5 s (cascaded health, ki, stamina), ki
     * flows and sweeps while charging, health below 25% beats (and pulses the frame red), and every number counts to
     * its value, pulses green on a gain and red with a shake on damage, and fades out after 2.5 s unchanged unless
     * DMZ's "always visible values" option is on.
     *
     * <p>A separate class so the static panel never loads DMZ's HUD classes; only touched when {@link #ANIMATED}.
     */
    private static final class Animated
    {
        private static final HudBar HP = new HudBar(HudStatNumberAnimator.StatKind.HEALTH);
        private static final HudBar KI = new HudBar(HudStatNumberAnimator.StatKind.KI).flowing();
        private static final HudBar STM = new HudBar(HudStatNumberAnimator.StatKind.STAMINA);
        // the role bar continues the shine cascade one step after stamina (DMZ staggers each bar by 0.16 s)
        private static final HudBar ROLE = new HudBar(HudStatNumberAnimator.StatKind.STAMINA).shineOffset(0.48f);

        // DMZ's HUD smoothing constants (XenoverseHUD / ModernHUD): release 0.10, form charge 0.08, charge glow 0.15,
        // hit shake 0.12; numbers count with HudBar's 0.11.
        private static final HudSmoother RELEASE = new HudSmoother(0.10f, 0.05f);
        private static final HudSmoother FORM = new HudSmoother(0.08f);
        private static final HudSmoother GLOW = new HudSmoother(0.15f);
        private static final HudSmoother SHAKE = new HudSmoother(0.12f);
        private static final HudSmoother HP_NUM = number();
        private static final HudSmoother KI_NUM = number();
        private static final HudSmoother STM_NUM = number();
        private static final HudSmoother ROLE_NUM = number();
        private static final HudSmoother ZENI_NUM = number();
        private static final HudSmoother SHARD_NUM = number();
        private static final HudStatNumberAnimator HP_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.HEALTH);
        private static final HudStatNumberAnimator KI_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.KI);
        private static final HudStatNumberAnimator STM_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.STAMINA);
        private static final HudStatNumberAnimator ROLE_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.KI);
        // the currencies use DMZ's ki-sense kind: always visible, green on a gain, red with a shake on a loss
        private static final HudStatNumberAnimator ZENI_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.KISENSE_HEALTH);
        private static final HudStatNumberAnimator SHARD_TEXT = new HudStatNumberAnimator(HudStatNumberAnimator.StatKind.KISENSE_HEALTH);

        private static final HudSprites.Sprite HEALTH_FILL = sprite(TEX_RW_HEALTH_GREY, RW_HEALTH_W, RW_HEALTH_H);
        private static final HudSprites.Sprite KI_FILL = sprite(TEX_RW_KI_FILL, RW_KI_FILL_W, RW_KI_FILL_H);
        private static final HudSprites.Sprite STAM_FILL = sprite(TEX_RW_STAM_FILL, RW_STAM_FILL_W, RW_STAM_FILL_H);
        private static final HudSprites.Sprite FRAME = sprite(TEX_RW_FRAME, RW_FRAME_W, RW_FRAME_H);
        private static final HudSprites.Sprite ORB_RING = sprite(TEX_ORB_FRAME, ORB_W, ORB_H);

        // DMZ's low-health alert red (XenoverseHUD.ALERT_COLOR) and surge band (SURGE_TOP)
        private static final int ALERT_RGB = 0xFF3B3B;
        private static final float SURGE_TOP = 0.55f;

        private static float lastHealth = -1.0f;

        private static HudSmoother number()
        {
            return new HudSmoother(0.11f, 0.45f);
        }

        private static HudSprites.Sprite sprite(ResourceLocation texture, int w, int h)
        {
            // one sprite per PNG, the whole sheet, 1:1 texels (our art is not on DMZ's 2x sheets)
            return new HudSprites.Sprite(texture, w, h, 0, 0, w, h);
        }

        static void drawStats(GuiGraphics g, Minecraft mc, LocalPlayer player, Snapshot s, float partialTick, boolean preview)
        {
            HP.update(s.health, s.maxHealth);
            KI.update(s.energy, s.maxEnergy);
            STM.update(s.stamina, s.maxStamina);
            boolean role = EnergyClientCache.kind() != null;
            if (role)
                ROLE.update(EnergyClientCache.value(), net.shurui.shuruisutilities.energy.EnergyManager.MAX);
            float release = RELEASE.update(s.release);
            float form = FORM.update(s.transform);
            float glow = GLOW.update(s.chargingKi ? 1.0f : 0.0f);
            float surge = s.data == null ? 0.0f : SurgeBarState.fraction(s.data);

            // DMZ's hit shake: a loss of 2% or more kicks the panel, proportionally to the loss
            if (lastHealth >= 0.0f && s.health < lastHealth)
            {
                float lost = (lastHealth - s.health) / s.maxHealth;
                if (lost >= 0.02f)
                    SHAKE.snap(Math.min(1.0f, SHAKE.value() + lost * 6.0f));
            }
            lastHealth = s.health;
            float shake = preview ? 0.0f : SHAKE.update(0.0f);

            float seconds = HudBar.time();
            float tickTime = player.tickCount + partialTick;
            float low = HudBar.heartbeat(HP.targetFraction());
            KI.setFlowBoost(glow);

            HudRender.setAlphaScale(RagnarokHud.alpha());
            g.pose().pushPose();
            g.pose().translate(Mth.sin(seconds * 61.0f) * 1.6f * shake, Mth.cos(seconds * 47.0f) * 1.0f * shake, 0.0f);
            try
            {
                drawGlintBelow(g);
                drawGlintAbove(g);

                // housing, its heartbeat alert, then health
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();
                g.blit(TEX_RW_FRAME, RW_FRAME_X, RW_FRAME_Y, 0.0f, 0.0f, RW_FRAME_W, RW_FRAME_H, RW_FRAME_W, RW_FRAME_H);
                if (low > 0.0f)
                    HudRender.sprite(g, FRAME, RW_FRAME_X, RW_FRAME_Y, RW_FRAME_W, RW_FRAME_H, ALERT_RGB, low * 0.6f);
                HP.drawFill(g, HEALTH_FILL, null, RW_HEALTH_X, RW_HEALTH_Y, RW_HEALTH_W, RW_HEALTH_H, healthRgb(HP.fraction()), low);
                RagnarokHud.restoreColor();

                // ki: aura tinted, flowing, swept while charging, the surge band on top
                g.blit(TEX_RW_KI_TRACK, RW_KI_TRACK_X, RW_KI_TRACK_Y, 0.0f, 0.0f, RW_KI_TRACK_W, RW_KI_TRACK_H, RW_KI_TRACK_W, RW_KI_TRACK_H);
                KI.drawFill(g, KI_FILL, null, RW_KI_FILL_X, RW_KI_FILL_Y, RW_KI_FILL_W, RW_KI_FILL_H, s.aura, 0.0f);
                KI.drawFillSweep(g, KI_FILL, RW_KI_FILL_X, RW_KI_FILL_Y, RW_KI_FILL_W, RW_KI_FILL_H, seconds * 1.1f, glow);
                if (surge > 0.0f)
                    HudRender.spritePart(g, KI_FILL, RW_KI_FILL_X, RW_KI_FILL_Y, RW_KI_FILL_W, RW_KI_FILL_H,
                            0.0f, SURGE_TOP, surge, 1.0f, HudRender.mix(s.aura, 0xFFFFFF, 0.75f), 0.95f, false);
                RagnarokHud.restoreColor();

                g.blit(TEX_RW_STAM_TRACK, RW_STAM_TRACK_X, RW_STAM_TRACK_Y, 0.0f, 0.0f, RW_STAM_TRACK_W, RW_STAM_TRACK_H, RW_STAM_TRACK_W, RW_STAM_TRACK_H);
                STM.drawFill(g, STAM_FILL, null, RW_STAM_FILL_X, RW_STAM_FILL_Y, RW_STAM_FILL_W, RW_STAM_FILL_H, 0xFFFFFF, 0.0f);
                RagnarokHud.restoreColor();

                ResourceLocation roleFill = energyFillTexture();
                if (role && roleFill != null)
                {
                    g.blit(TEX_RW_ENERGY_TRACK, RW_ENERGY_TRACK_X, RW_ENERGY_TRACK_Y, 0.0f, 0.0f,
                            RW_ENERGY_TRACK_W, RW_ENERGY_TRACK_H, RW_ENERGY_TRACK_W, RW_ENERGY_TRACK_H);
                    ROLE.drawFill(g, sprite(roleFill, RW_ENERGY_W, RW_ENERGY_H), null, RW_ENERGY_X, RW_ENERGY_Y, RW_ENERGY_W, RW_ENERGY_H, 0xFFFFFF, 0.0f);
                    RagnarokHud.restoreColor();
                }

                drawOrbAndPreview(g, mc, player);
                // the orb ring takes the same heartbeat red as DMZ's portrait frame
                if (low > 0.0f)
                {
                    HudRender.sprite(g, ORB_RING, ORB_X, ORB_Y, ORB_W, ORB_H, 0xFF3030, low * 0.5f);
                    RagnarokHud.restoreColor();
                }
                drawTransformArc(g, form);
                drawReleasePercent(g, mc, Math.round(release));

                drawValue(g, mc, HP_NUM, HP_TEXT, s.health, s.maxHealth, RW_HEALTH_X, RW_HEALTH_Y, RW_HEALTH_W, RW_HEALTH_H,
                        RW_HEALTH_TEXT_BIAS_X, tickTime);
                drawValue(g, mc, KI_NUM, KI_TEXT, s.energy, s.maxEnergy, RW_KI_FILL_X, RW_KI_FILL_Y, RW_KI_FILL_W, RW_KI_FILL_H,
                        0, tickTime);
                drawValue(g, mc, STM_NUM, STM_TEXT, s.stamina, s.maxStamina, RW_STAM_FILL_X, RW_STAM_FILL_Y, RW_STAM_FILL_W,
                        RW_STAM_FILL_H, 0, tickTime);
                if (role)
                    drawValue(g, mc, ROLE_NUM, ROLE_TEXT, EnergyClientCache.value(), net.shurui.shuruisutilities.energy.EnergyManager.MAX,
                            RW_ENERGY_X, RW_ENERGY_Y, RW_ENERGY_W, RW_ENERGY_H, 0, tickTime);
            }
            finally
            {
                g.pose().popPose();
                HudRender.setAlphaScale(1.0f);
                RagnarokHud.restoreColor();
            }
        }

        // One bar readout: the figure counts toward the value and wears DMZ's change pulse, shake and fade.
        private static void drawValue(GuiGraphics g, Minecraft mc, HudSmoother number, HudStatNumberAnimator anim,
                                      float current, float max, int x, int y, int w, int h, int biasX, float tickTime)
        {
            float shown = number.update(current);
            float changeValue = dmzPercentages() ? Math.round(current / Math.max(1.0f, max) * 100.0f) : Math.round(current);
            HudStatNumberAnimator.RenderState state = anim.update(String.valueOf(changeValue), changeValue, tickTime);
            if (state.isHidden())
                return;
            drawBarValue(g, mc, amount(shown, max), x, y, w, h, biasX, state.offsetX(), state.offsetY(),
                    state.rgbColor(), state.alpha());
        }

        static void drawCurrency(GuiGraphics g, Minecraft mc, Long zeni, Long shards, float partialTick)
        {
            float tickTime = (mc.player == null ? 0 : mc.player.tickCount) + partialTick;
            if (shards != null)
            {
                float shown = SHARD_NUM.update(shards);
                HudStatNumberAnimator.RenderState state = SHARD_TEXT.update(Long.toString(shards), shards, tickTime);
                drawShards(g, mc, grouped(Math.round((double) shown)), pulse(SHARD_TEXT_COLOR, state.rgbColor()),
                        state.offsetX(), state.offsetY());
            }
            if (zeni != null)
            {
                float shown = ZENI_NUM.update(zeni);
                HudStatNumberAnimator.RenderState state = ZENI_TEXT.update(Long.toString(zeni), zeni, tickTime);
                drawZeni(g, mc, grouped(Math.round((double) shown)), pulse(ZENI_TEXT_COLOR, state.rgbColor()),
                        state.offsetX(), state.offsetY());
            }
        }

        // DMZ pulses from white to green (gain) or red (loss); our figures are gold and blue, so carry the same
        // pulse strength over onto their own colour.
        private static int pulse(int argb, int dmzRgb)
        {
            int rgb = dmzRgb & 0xFFFFFF;
            if (rgb == 0xFFFFFF)
                return argb;
            boolean gain = ((rgb >> 16) & 0xFF) < 0xFF;
            float t = gain ? (0xFF - ((rgb >> 16) & 0xFF)) / (float) (0xFF - 0x55)
                    : (0xFF - ((rgb >> 8) & 0xFF)) / (float) (0xFF - 0x55);
            return (argb & 0xFF000000) | HudRender.mix(argb & 0xFFFFFF, gain ? 0x55FF55 : 0xFF5555, t);
        }

        // DMZ's smooth health colour ramp, on our three fills, centred on the old 50% and 25% thresholds
        private static int healthRgb(float fraction)
        {
            float toAmber = smoothStep(HEALTH_AMBER_ABOVE - 0.05f, HEALTH_AMBER_ABOVE + 0.05f, fraction);
            float toGreen = smoothStep(HEALTH_GREEN_ABOVE - 0.05f, HEALTH_GREEN_ABOVE + 0.05f, fraction);
            return HudRender.mix(HudRender.mix(FILL_HEALTH_RED_RGB, FILL_HEALTH_AMBER_RGB, toAmber), FILL_HEALTH_RGB, toGreen);
        }

        private static float smoothStep(float edge0, float edge1, float value)
        {
            float t = clamp01((value - edge0) / (edge1 - edge0));
            return t * t * (3.0f - 2.0f * t);
        }
    }

    // orb backdrop, then the CACHED portrait blitted from an offscreen framebuffer, then the frame on top to mask the
    // rectangular lens corners and restore the ring. The portrait is never rendered per-frame and mc.player is never
    // written: the entity is drawn once into an offscreen target (capturePreview) and only re-drawn when it changes.
    private static void drawOrbAndPreview(GuiGraphics g, Minecraft mc, LocalPlayer player)
    {
        g.blit(TEX_ORB, ORB_X, ORB_Y, 0.0f, 0.0f, ORB_W, ORB_H, ORB_W, ORB_H);

        // Flush the batched orb backdrop to the currently-bound (main) target BEFORE any offscreen render or immediate
        // draw, so the backdrop is not left pending in the shared buffer while we bind the preview target, and so the
        // immediate portrait quad lands on top of the backdrop rather than being overwritten by a late flush.
        g.flush();

        RenderTarget target = ensurePreviewTexture(mc, player);
        if (target != null)
            blitPreviewTexture(g, target.getColorTextureId());

        g.blit(TEX_ORB_FRAME, ORB_X, ORB_Y, 0.0f, 0.0f, ORB_W, ORB_H, ORB_W, ORB_H);
    }

    // Return the cached preview target, re-capturing first if the portrait would have changed. Invalidation triggers are
    // exactly the things that change the character's APPEARANCE: the DMZ race/gender/form/stack-form changed, the player's
    // real or cosmetic armor changed, or the LocalPlayer instance was replaced (respawn/dimension/relog). There is no
    // periodic refresh; between appearance changes the portrait is a frozen still image. When dirty we refresh the stand-in
    // (copying the player's current stats and armor onto it) and re-render it into the offscreen target. The equipment
    // check is a per-slot value compare against the cached snapshot, so an unchanged frame allocates nothing and the
    // portrait is NOT rebuilt every frame.
    private static RenderTarget ensurePreviewTexture(Minecraft mc, LocalPlayer player)
    {
        String formKey = buildFormKey(player);
        LocalPlayer cached = previewPlayerRef == null ? null : previewPlayerRef.get();
        boolean playerChanged = cached != player;
        boolean appearanceChanged = !formKey.equals(previewFormKey);
        boolean equipmentChanged = equipmentChanged(player);
        boolean dirty = previewTarget == null || playerChanged || appearanceChanged || equipmentChanged;
        if (dirty)
        {
            LivingEntity subject = refreshStandIn(mc, player, playerChanged, appearanceChanged);
            // Real armor must be copied onto the stand-in for DMZ's armor layer to draw it; cosmetic armor is resolved by
            // DMZ from the stand-in's UUID (which matches the player), so only the real slots need copying here.
            if (subject == previewEntity)
                applyEquipment(player, previewEntity);
            capturePreview(mc, player, subject);
            previewPlayerRef = new java.lang.ref.WeakReference<>(player);
            previewFormKey = formKey;
            snapshotEquipment(player);
        }
        return previewTarget;
    }

    // Cheap per-frame check for a real or cosmetic armor change, comparing each of the eight tracked stacks against the
    // cached snapshot and bailing on the first mismatch. Allocates nothing on an unchanged frame: it reads live stacks and
    // value-compares them, and it only touches Cosmetic Armor Reworked when that mod is actually loaded (else the cosmetic
    // slots read as empty for free). Cosmetic stacks come from DMZ's CosmeticArmorCompat, the same source DMZ's own armor
    // layer draws from, so the preview stays in lockstep with what DMZ renders on the live player.
    private static boolean equipmentChanged(LocalPlayer player)
    {
        boolean carLoaded = CosmeticArmorCompat.isLoaded();
        for (int i = 0; i < PREVIEW_ARMOR_SLOTS.length; i++)
        {
            EquipmentSlot slot = PREVIEW_ARMOR_SLOTS[i];
            if (!sameStack(previewEquip[i], player.getItemBySlot(slot)))
                return true;
            ItemStack cosmetic = carLoaded ? CosmeticArmorCompat.getCosmeticStack(player, slot) : ItemStack.EMPTY;
            if (!sameStack(previewEquip[4 + i], cosmetic))
                return true;
        }
        return false;
    }

    // Re-snapshot the eight tracked stacks after a capture. Stores COPIES so a later in-place tag change on the live stack
    // is caught by the next equipmentChanged compare rather than aliasing the cached value. Only run on a re-capture, never
    // per frame.
    private static void snapshotEquipment(LocalPlayer player)
    {
        boolean carLoaded = CosmeticArmorCompat.isLoaded();
        for (int i = 0; i < PREVIEW_ARMOR_SLOTS.length; i++)
        {
            EquipmentSlot slot = PREVIEW_ARMOR_SLOTS[i];
            previewEquip[i] = player.getItemBySlot(slot).copy();
            ItemStack cosmetic = carLoaded ? CosmeticArmorCompat.getCosmeticStack(player, slot) : null;
            previewEquip[4 + i] = cosmetic == null ? ItemStack.EMPTY : cosmetic.copy();
        }
    }

    // Copy the player's four real armor stacks onto the stand-in so DMZ's armor layer (which reads the rendered entity's
    // own inventory) draws them. Cosmetic armor is NOT copied here: DMZ resolves it from the entity UUID, and the stand-in
    // carries the player's UUID, so DMZ overrides the real piece with the cosmetic one exactly as on the live player.
    private static void applyEquipment(LocalPlayer player, PreviewStandIn standin)
    {
        for (EquipmentSlot slot : PREVIEW_ARMOR_SLOTS)
            standin.setItemSlot(slot, player.getItemBySlot(slot).copy());
    }

    // Null-safe appearance equality for a tracked stack: a null cached slot reads as empty, and stacks match when they are
    // the same item with the same tags (count is irrelevant to how a piece renders).
    private static boolean sameStack(ItemStack cached, ItemStack current)
    {
        ItemStack a = cached == null ? ItemStack.EMPTY : cached;
        ItemStack b = current == null ? ItemStack.EMPTY : current;
        return ItemStack.isSameItemSameTags(a, b);
    }

    // Build/refresh the stand-in entity we render instead of mc.player, and return the entity to capture. The stand-in is a
    // never-ticked RemotePlayer that carries a COPY of the player's DMZ stats (race/gender/form drive DMZ's renderer and
    // tint layers) and delegates its skin to the real player, so it renders as the correct race/form/skin but sits in the
    // stock idle pose (tickCount stays 0, no limb swing). Because it is never ticked and never added to the world, nothing
    // here touches or animates mc.player. If the stand-in cannot be built for any reason we fall back to rendering the real
    // player (worst case the old behaviour), never a crash: the caller is already inside the overlay's try/catch.
    private static LivingEntity refreshStandIn(Minecraft mc, LocalPlayer player, boolean playerChanged, boolean appearanceChanged)
    {
        try
        {
            ClientLevel level = mc.level;
            if (level == null)
                return player;
            if (previewEntity == null || playerChanged)
            {
                // The stand-in carries the player's REAL UUID (not a random one): DMZ's cosmetic-armor layer resolves the
                // worn Cosmetic Armor Reworked pieces from the rendered entity's UUID, so matching it makes the preview show
                // the player's cosmetics. The stand-in is never added to the level, so this shares no entity-map slot.
                GameProfile profile = new GameProfile(player.getGameProfile().getId(), player.getGameProfile().getName());
                previewEntity = new PreviewStandIn(level, profile, player);
                copyStats(player, previewEntity);
            }
            else if (appearanceChanged)
            {
                copyStats(player, previewEntity);
            }
            return previewEntity;
        }
        catch (Throwable ignored)
        {
            previewEntity = null;
            return player;
        }
    }

    // Copy the player's DMZ stats onto the stand-in so its Character (race/gender/form/stack-form) matches. DMZ's custom
    // renderer and its tint layers both read the entity's StatsData, so this is what makes the stand-in look like the player.
    private static void copyStats(LocalPlayer player, PreviewStandIn standin)
    {
        StatsData src = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        StatsData dst = StatsProvider.get(StatsCapability.INSTANCE, standin).resolve().orElse(null);
        if (src != null && dst != null)
            dst.copyFrom(src);
    }

    // Build the appearance-identity key the cache compares against: race + gender + active form + stack-form (group + id
    // each). Any transformation, form/stack-form swap, or race/gender change (which is also what a character-slot change
    // surfaces as) alters this string, which is exactly when the portrait must be re-captured.
    private static String buildFormKey(LocalPlayer player)
    {
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            Character character = stats == null ? null : stats.getCharacter();
            if (character == null)
                return "";
            return character.getRaceName() + "|" + character.getGender() + "|"
                    + character.getActiveFormGroup() + "|" + character.getActiveForm() + "|"
                    + character.getActiveStackFormGroup() + "|" + character.getActiveStackForm();
        }
        catch (Throwable ignored)
        {
            return "";
        }
    }

    // Render the stand-in once into the offscreen preview target. This is READ-ONLY with respect to mc.player: the subject
    // is the never-ticked stand-in (or, on fallback, mc.player, which renderEntityInInventory only samples). The forward
    // facing is produced by a pose quaternion. The previously-bound framebuffer and the projection/modelview state are
    // saved up front and restored in the finally, so a fault here cannot leave the main target unbound.
    private static void capturePreview(Minecraft mc, LocalPlayer player, LivingEntity subject)
    {
        if (previewTarget == null)
        {
            previewTarget = new TextureTarget(PREVIEW_TARGET_W, PREVIEW_TARGET_H, true, Minecraft.ON_OSX);
            previewTarget.setFilterMode(GL11.GL_NEAREST);
        }

        // vertical model scale for the current race (default 0.9375 when unresolved) plus DMZ's oversized-race shrink.
        float scaleY = 0.9375f;
        int previewScale = PREVIEW_SCALE;
        try
        {
            StatsData stats = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
            Character character = stats == null ? null : stats.getCharacter();
            Float[] resolved = character == null ? null : character.getResolvedModelScaling();
            if (resolved != null && resolved.length >= 2 && resolved[0] != null && resolved[1] != null)
            {
                scaleY = resolved[1];
                float currentScale = (resolved[0] + resolved[1]) / 2.0f;
                if (currentScale > 1.0f)
                    previewScale = (int) (PREVIEW_SCALE * (0.9375f / currentScale));
            }
        }
        catch (Throwable ignored)
        {
            // keep the default scale factor.
        }

        // Framing in TARGET pixels: the target is the lens window at PREVIEW_SUPERSAMPLE x, so multiply the assembly-local
        // anchors by the supersample. renderEntityInInventory anchors the FEET and draws upward, so feetY is the head-top
        // anchor plus the scaled model height; the torso below simply runs off the bottom of the target and is dropped.
        int renderScale = previewScale * PREVIEW_SUPERSAMPLE;
        int headTop = (PREVIEW_HEAD_TOP_Y - LENS_Y0) * PREVIEW_SUPERSAMPLE;
        int cx = (PREVIEW_CENTER_X - LENS_X0) * PREVIEW_SUPERSAMPLE;
        int feetY = headTop + Math.round(subject.getBbHeight() * scaleY * renderScale);

        // Forward facing. LivingEntityRenderer.setupRotations applies Ry(180 - yBodyRot) to the body; adding
        // Ry(yBodyRot - 180) to the outer pose cancels the yBodyRot term, pinning the view to the front. The stand-in's
        // yBodyRot is 0 (a fresh, never-ticked entity), so this reduces to a plain front pose; on the mc.player fallback it
        // still only READS yBodyRot.
        float bodyYaw = subject.yBodyRot;
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI)
                .rotateY((float) Math.toRadians(bodyYaw - 180.0));
        Quaternionf cameraOrientation = new Quaternionf().rotateX(0.0f);

        Matrix4f savedProjection = RenderSystem.getProjectionMatrix();
        // the capture is drawn opaque whatever the panel's fade is right now; the blit applies the fade
        float[] savedColor = RenderSystem.getShaderColor().clone();
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        RenderTarget previousTarget = mc.getMainRenderTarget();
        PoseStack modelView = RenderSystem.getModelViewStack();

        Matrix4f ortho = new Matrix4f().setOrtho(0.0f, PREVIEW_TARGET_W, PREVIEW_TARGET_H, 0.0f, 1000.0f, 21000.0f);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
        modelView.pushPose();
        modelView.setIdentity();
        modelView.translate(0.0, 0.0, -11000.0);
        RenderSystem.applyModelViewMatrix();
        try
        {
            previewTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            previewTarget.clear(Minecraft.ON_OSX);
            previewTarget.bindWrite(true);

            GuiGraphics gg = new GuiGraphics(mc, mc.renderBuffers().bufferSource());
            gg.pose().pushPose();
            gg.pose().translate(0.0, 0.0, 150.0);
            InventoryScreen.renderEntityInInventory(gg, cx, feetY, renderScale, pose, cameraOrientation, subject);
            gg.pose().popPose();
            gg.flush();
        }
        finally
        {
            previousTarget.bindWrite(true);
            modelView.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProjection, savedSorting);
            RenderSystem.setShaderColor(savedColor[0], savedColor[1], savedColor[2], savedColor[3]);
        }
    }

    // Blit the cached framebuffer colour texture into the lens window (assembly-local coords, inside the HUD pose). This
    // is a plain textured quad, no entity rendering. Framebuffer textures have a bottom-left origin, so the V coordinate
    // is flipped (top edge samples v=1, bottom edge v=0) to draw the portrait upright.
    private static void blitPreviewTexture(GuiGraphics g, int textureId)
    {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, textureId);
        // the element's fade alpha, so the portrait fades with the rest of the panel
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, RagnarokHud.alpha());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        float x0 = LENS_X0, y0 = LENS_Y0 + ORB_Y, x1 = LENS_X1, y1 = LENS_Y1 + ORB_Y;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder buf = Tesselator.getInstance().getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        buf.vertex(m, x0, y1, 0.0f).uv(0.0f, 0.0f).endVertex();
        buf.vertex(m, x1, y1, 0.0f).uv(1.0f, 0.0f).endVertex();
        buf.vertex(m, x1, y0, 0.0f).uv(1.0f, 1.0f).endVertex();
        buf.vertex(m, x0, y0, 0.0f).uv(0.0f, 1.0f).endVertex();
        BufferUploader.drawWithShader(buf.end());

        RenderSystem.disableBlend();
    }

    // Curved transformation gauge. A straight rectangular clip would slice through the crescent's curved edges, so
    // instead we reveal it column by column from the left: each 1px source column of transform_arc.png only contains the
    // crescent's pixels present at that x (the rest of the column is transparent), so blitting whole columns up to the
    // filled fraction reveals the shape ALONG its curve rather than behind a vertical straight cut.
    private static void drawTransformArc(GuiGraphics g, float frac)
    {
        int cols = Math.round(ARC_W * clamp01(frac));
        for (int c = 0; c < cols; c++)
        {
            g.blit(TEX_TRANSFORM_ARC, ARC_X + c, ARC_Y + ORB_Y, (float) c, 0.0f, 1, ARC_H, ARC_W, ARC_H);
        }
    }

    // Health track then a horizontally-clipped fill whose texture swaps by threshold. The three fills are pixel-identical
    // in shape, so straight horizontal clipping reads correctly (the notch/tip near 100% disappear first as it drops).
    // The housing that carries the health track and the top rail, then health as ONE bar in three states (green, amber,
    // red) exactly as DMZ does it and as the design sheet lays it out: three fills of identical size, one drawn at a time.
    private static void drawFrameAndHealth(GuiGraphics g, float frac)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_FRAME, RW_FRAME_X, RW_FRAME_Y, 0.0f, 0.0f, RW_FRAME_W, RW_FRAME_H, RW_FRAME_W, RW_FRAME_H);
        int w = Math.round(RW_HEALTH_W * clamp01(frac));
        if (w <= 0)
            return;
        ResourceLocation fill = frac >= HEALTH_GREEN_ABOVE ? TEX_RW_HEALTH
                : frac >= HEALTH_AMBER_ABOVE ? TEX_RW_HEALTH_AMBER
                : TEX_RW_HEALTH_RED;
        g.blit(fill, RW_HEALTH_X, RW_HEALTH_Y, 0.0f, 0.0f, w, RW_HEALTH_H, RW_HEALTH_W, RW_HEALTH_H);
    }

    // Ki as ten DISCRETE blocks. The lit run is one strict-1:1 left-to-right clipped blit of ki_segments.png over the navy
    // track: source width == destination width == KI_FILL_END[filled-1], source/destination height == KI_SEG_H, so nothing
    // is stretched. The block separators are the texture's own full-height alpha gaps, so drawing the whole strip up to the
    // gap after the last lit block reproduces the reference row exactly (which is itself just the whole strip over navy).
    // The number of lit blocks is the ki fraction rounded to the ten steps, so a partial fill always ends on a whole block.
    // Ki is now a continuous bar rather than ten blocks, and it is tinted with the player's AURA colour the way DMZ
    // tints its own ki display. The fill art is greyscale on purpose: the tint is a colour multiply, so a coloured base
    // would contaminate every aura (a red aura over blue art reads muddy purple). Falls back to the artwork's original
    // blue when the aura colour is missing or unparseable, so a DMZ change can never leave the bar black.
    private static void drawKi(GuiGraphics g, float frac, int auraRgb)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_KI_TRACK, RW_KI_TRACK_X, RW_KI_TRACK_Y, 0.0f, 0.0f,
                RW_KI_TRACK_W, RW_KI_TRACK_H, RW_KI_TRACK_W, RW_KI_TRACK_H);
        int w = Math.round(RW_KI_FILL_W * clamp01(frac));
        if (w <= 0)
            return;
        float r = ((auraRgb >> 16) & 0xFF) / 255.0f;
        float gg = ((auraRgb >> 8) & 0xFF) / 255.0f;
        float b = (auraRgb & 0xFF) / 255.0f;
        g.setColor(r, gg, b, RagnarokHud.alpha());
        try
        {
            g.blit(TEX_RW_KI_FILL, RW_KI_FILL_X, RW_KI_FILL_Y, 0.0f, 0.0f,
                    w, RW_KI_FILL_H, RW_KI_FILL_W, RW_KI_FILL_H);
        }
        finally
        {
            g.setColor(1.0f, 1.0f, 1.0f, RagnarokHud.alpha());
        }
    }

    // The aura colour DMZ is currently using for this player, as 0xRRGGBB. DMZ stores it as a "#RRGGBB" string on the
    // character; anything unparseable falls back to the artwork's own blue so the bar is never drawn black.
    private static int auraRgb(Character character)
    {
        int fallback = 0x7FD4FF;
        if (character == null)
            return fallback;
        try
        {
            String hex = character.getAuraColor();
            if (hex == null)
                return fallback;
            String body = hex.trim();
            if (body.startsWith("#"))
                body = body.substring(1);
            if (body.length() != 6)
                return fallback;
            return Integer.parseInt(body, 16);
        }
        catch (Throwable t)
        {
            return fallback;
        }
    }

    // The role energy track: one slot recoloured for whichever bar the viewing player currently has (malice red over
    // blood red, destruction purple, angelic silvery blue). Drawn from the value the server pushed for THIS player
    // only. A player with no role gets no track at all, not an empty one, so the HUD is unchanged for them.
    private static void drawEnergyBars(GuiGraphics g)
    {
        ResourceLocation fill = energyFillTexture();
        if (fill == null)
            return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_ENERGY_TRACK, RW_ENERGY_TRACK_X, RW_ENERGY_TRACK_Y, 0.0f, 0.0f,
                RW_ENERGY_TRACK_W, RW_ENERGY_TRACK_H, RW_ENERGY_TRACK_W, RW_ENERGY_TRACK_H);
        int w = Math.round(RW_ENERGY_W * energyFrac());
        if (w > 0)
            g.blit(fill, RW_ENERGY_X, RW_ENERGY_Y, 0.0f, 0.0f, w, RW_ENERGY_H, RW_ENERGY_W, RW_ENERGY_H);
    }

    // The zeni pill: coin, housing, then the amount. The balance comes from ZeniClientCache, which the server pushes to
    // each player for their OWN account only. Draws nothing until the server has said what it is, so a player with money
    // never flashes a 0 on join.
    private static void drawZeni(GuiGraphics g, Minecraft mc, String text, int colour, float dx, float dy)
    {
        // The WHOLE cluster (the pill, the coin, the Z and the figure) stays hidden until BOTH of these hold, so
        // an instance with no economy shows no currency box at all, housing included. This mirrors drawShards.
        //   1. ClientGate.key(): the server told us on login that it holds the Ragnarok Key. The Zeni economy is a
        //      private module, so a keyless instance (dedicated, LAN or singleplayer: RagnarokKey.restricted() no
        //      longer exempts singleplayer since U1) tears the Economy module down in PublicContent.enforce().
        //   2. ZeniClientCache.known(): the server has actually pushed this player their balance. Only a running
        //      Economy module ever sends PacketZeniSync, so this is also the "economy is on server-side" signal;
        //      there is no separate economy-enabled sync, this is it. It is -1 ("not told") until the first push.
        // Before this the three sprites blitted unconditionally, so a keyless server drew an empty gold pill next
        // to the scouter with no number in it, which is exactly the box the owner did not want without the key.
        // (the gates now sit in CurrencyElement: listed() needs the key, render() passes null until the balance is known)
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_ZENI_BAR, RW_ZENI_BAR_X, RW_ZENI_BAR_Y, RW_ZENI_BAR_U, 0.0f,
                RW_ZENI_BAR_DRAW_W, RW_ZENI_BAR_H, RW_ZENI_BAR_W, RW_ZENI_BAR_H);
        g.blit(TEX_RW_ZENI_ORB, RW_ZENI_ORB_X, RW_ZENI_ORB_Y, 0.0f, 0.0f,
                RW_ZENI_ORB_S, RW_ZENI_ORB_S, RW_ZENI_ORB_S, RW_ZENI_ORB_S);
        g.blit(TEX_RW_ZENI_Z, RW_ZENI_Z_X, RW_ZENI_Z_Y, 0.0f, 0.0f,
                RW_ZENI_Z_W, RW_ZENI_Z_H, RW_ZENI_Z_W, RW_ZENI_Z_H);
        drawPillAmount(g, mc, text, ZENI_TEXT_LEFT, ZENI_TEXT_RIGHT, ZENI_TEXT_Y, colour, dx, dy);
    }


    /**
     * A figure inside one of the currency pills: left aligned, shrinking to fit, black stamped.
     *
     * <p>Shared by zeni and shards so the two cannot drift apart. It is the zeni routine unchanged, only with the
     * band and the colour passed in, which is the same reason {@link ZeniReadout} exists for the screens.
     */
    private static void drawPillAmount(GuiGraphics g, Minecraft mc, String text, int left, int right, float centreY,
            int colour, float dx, float dy)
    {
        int available = right - left;
        float scale = ZENI_TEXT_SCALE;
        int width = mc.font.width(text);
        if (width * scale > available)
            scale = (float) available / width;

        var pose = g.pose();
        pose.pushPose();
        // dx, dy: DMZ's damage shake on the figure (0 when still)
        pose.translate(left + dx, centreY - mc.font.lineHeight * scale / 2.0f + dy, 0.0f);
        pose.scale(scale, scale, 1.0f);
        // same 4-way outline DMZ uses for its own HUD numbers, so this reads native beside them
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 1; oy++)
                if (ox != 0 || oy != 0)
                    g.drawString(mc.font, text, ox, oy, 0xFF000000, false);
        g.drawString(mc.font, text, 0, 0, colour, false);
        pose.popPose();
    }

    /**
     * The shards pill: the zeni cluster's twin, one pill to its left, in blue.
     *
     * <p>DRAWS NOTHING AT ALL until the server has said what the balance is, housing included. That single rule
     * is also the feature gate: {@code ArgentSync} only ever sends to a server where the currency module is
     * entitled and switched on, so a server that does not run it leaves {@link ShardClientCache} at "not told"
     * for ever and this element simply does not exist. It is not a zero and not an empty pill, which is what
     * "when the feature is off, draw nothing" has to mean for something that shows how much real money somebody
     * has spent.
     *
     * <p>Drawn from the same three sprites the zeni cluster uses. Only the currency mark and the figure differ,
     * because the housing art is already blue.
     */
    private static void drawShards(GuiGraphics g, Minecraft mc, String text, int colour, float dx, float dy)
    {
        // Shards (the CosmeticShards module) is private too (it lives in the Ragnarok Key), so gate on the synced key as well as on the balance being known. known() alone already kept
        // the box off a keyless server (ArgentSync never sends there), but the explicit key() gate matches drawZeni
        // and is defence-in-depth against any future push path.
        // (the gates now sit in CurrencyElement, as for drawZeni)
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_ZENI_BAR, RW_SHARD_BAR_X, RW_SHARD_BAR_Y, RW_ZENI_BAR_U, 0.0f,
                RW_ZENI_BAR_DRAW_W, RW_ZENI_BAR_H, RW_ZENI_BAR_W, RW_ZENI_BAR_H);
        g.blit(TEX_RW_ZENI_ORB, RW_SHARD_ORB_X, RW_SHARD_ORB_Y, 0.0f, 0.0f,
                RW_ZENI_ORB_S, RW_ZENI_ORB_S, RW_ZENI_ORB_S, RW_ZENI_ORB_S);
        drawShardMark(g, mc);
        drawPillAmount(g, mc, text, SHARD_TEXT_LEFT, SHARD_TEXT_RIGHT, SHARD_TEXT_Y, colour, dx, dy);
    }

    /**
     * The blue dollar sign, centred in the shards coin exactly where the gold Z sits in the zeni one.
     *
     * <p>Black stamped, unlike the Z. The Z is a sprite with its own dark bevel and it is four pixels wide, so it
     * keeps clear of the coin's light rim; the font's dollar sign is five wide and its edges reach that rim, and
     * without the stamp its left and right strokes wash out against it.
     */
    private static void drawShardMark(GuiGraphics g, Minecraft mc)
    {
        // font.width counts the one column of advance that follows the glyph, which is not ink. Taking it off
        // before centring is the difference between the mark sitting on the coin's middle and a pixel left of it.
        int ink = Math.max(1, mc.font.width(SHARD_MARK) - 1);
        int x = RW_SHARD_ORB_X + (RW_ZENI_ORB_S - ink) / 2;
        int y = RW_SHARD_ORB_Y + SHARD_MARK_DY;
        for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
                if (dx != 0 || dy != 0)
                    g.drawString(mc.font, SHARD_MARK, x + dx, y + dy, 0xFF000000, false);
        g.drawString(mc.font, SHARD_MARK, x, y, SHARD_TEXT_COLOR, false);
    }


    /**
     * Thousands separators, grouped by hand rather than through {@code String.format("%,d")}.
     *
     * <p>That formatter follows the JVM's default locale, which on a French or German client groups with a space or a
     * full stop. The separator here is part of the HUD's look, not of the player's locale, so it is fixed.
     */
    private static String grouped(long amount)
    {
        String digits = Long.toString(Math.abs(amount));
        StringBuilder out = new StringBuilder(digits.length() + digits.length() / 3 + 1);
        if (amount < 0)
            out.append('-');
        for (int i = 0; i < digits.length(); i++)
        {
            if (i > 0 && (digits.length() - i) % 3 == 0)
                out.append(',');
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    // Release percentage (DMZ's ki power-release/suppression level, ~5..100) drawn centred in the orb's pale dome cap.
    // DMZ draws its own power-release readout unconditionally (it is NOT gated by hideHudNumbers/alwaysVisibleHudValues),
    // so this one is always shown too, in DMZ's power-release pink with the same bordered font method.
    private static void drawReleasePercent(GuiGraphics g, Minecraft mc, int release)
    {
        drawBorderedCentered(g, mc.font, release + "%", RELEASE_OVAL_CX, RELEASE_OVAL_CY + ORB_Y, RELEASE_TEXT_SCALE, RELEASE_TEXT_COLOR);
    }

    // Draw text centred on (cx, cy) in BOTH axes at the given scale with DMZ's exact HUD-text style: the vanilla font
    // stamped four times at +-1px in opaque black (the outline) then once on top in the text colour, with NO vanilla drop
    // shadow. This matches com.dragonminez.client.util.TextUtil.drawCenteredStringWithBorder (which DMZ's XenoverseHUD calls
    // through its drawScaledText helper). The half-width/half-height offsets are applied INSIDE the scaled pose, so the
    // scaled text dimensions decide the centre.
    private static void drawBorderedCentered(GuiGraphics g, Font font, String text, float cx, float cy, float scale, int color)
    {
        final int border = 0xFF000000; // opaque black outline, exactly like DMZ
        int x = -font.width(text) / 2;
        int y = -font.lineHeight / 2;
        g.pose().pushPose();
        g.pose().translate(cx, cy, 10.0);
        g.pose().scale(scale, scale, 1.0f);
        g.drawString(font, text, x + 1, y, border, false);
        g.drawString(font, text, x - 1, y, border, false);
        g.drawString(font, text, x, y + 1, border, false);
        g.drawString(font, text, x, y - 1, border, false);
        g.drawString(font, text, x, y, color, false);
        g.pose().popPose();
    }

    // Stamina track then a horizontally-clipped fill (a plain left-to-right rectangle).
    private static void drawStamina(GuiGraphics g, float frac)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_STAM_TRACK, RW_STAM_TRACK_X, RW_STAM_TRACK_Y, 0.0f, 0.0f,
                RW_STAM_TRACK_W, RW_STAM_TRACK_H, RW_STAM_TRACK_W, RW_STAM_TRACK_H);
        int w = Math.round(RW_STAM_FILL_W * clamp01(frac));
        if (w <= 0)
            return;
        g.blit(TEX_RW_STAM_FILL, RW_STAM_FILL_X, RW_STAM_FILL_Y, 0.0f, 0.0f,
                w, RW_STAM_FILL_H, RW_STAM_FILL_W, RW_STAM_FILL_H);
    }

    /**
     * DMZ's own "hide HUD numbers" switch, from its character config menu.
     *
     * <p>These readouts follow that switch rather than getting one of their own. A player who has turned DMZ's HUD
     * numbers off has already said what they want; a second toggle somewhere else would only be a way for the two
     * settings to disagree.
     *
     * <p>Failing open on any error is deliberate: if the config is not up yet, showing the numbers is the harmless
     * outcome and hiding them would look like the readouts were broken.
     */
    private static boolean dmzHudNumbersHidden()
    {
        try
        {
            GeneralUserConfig cfg = ConfigManager.getUserConfig();
            return cfg != null && Boolean.TRUE.equals(cfg.getHideHudNumbers());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /**
     * What a bar reads: "current/max" normally, or a percentage when DMZ's own percentage option is on.
     *
     * <p>Following DMZ's switch rather than adding one means a player sets this once, in the menu they already know,
     * and both HUDs agree. The percentage is floored rather than rounded, so a bar never reads 100% until it is
     * actually full, and never 0% while a sliver remains.
     */
    private static String amount(float current, float max)
    {
        float cur = Math.max(0.0f, current);
        float cap = Math.max(0.0f, max);
        if (!dmzPercentages())
            return Math.round(cur) + "/" + Math.round(cap);
        if (cap <= 0.0f)
            return "0%";
        int pct = (int) Math.floor(100.0f * cur / cap);
        if (pct >= 100 && cur < cap)
            pct = 99;
        if (pct <= 0 && cur > 0.0f)
            pct = 1;
        return Math.max(0, Math.min(100, pct)) + "%";
    }

    /**
     * DMZ's "show percentages" option, from the same config menu as its hide-HUD-numbers switch.
     *
     * <p>Fails to FALSE on any error, which shows the raw figures: if the config is not up yet, a number is the
     * honest thing to draw, and a percentage of an unknown maximum would not be.
     */
    private static boolean dmzPercentages()
    {
        try
        {
            GeneralUserConfig cfg = ConfigManager.getUserConfig();
            return cfg != null && Boolean.TRUE.equals(cfg.getAdvancedDescriptionPercentage());
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** The fill colour the health bar is currently drawn in, so its readout inverts the right thing. */
    private static int healthFillRgb(float frac)
    {
        return frac >= HEALTH_GREEN_ABOVE ? FILL_HEALTH_RGB
                : frac >= HEALTH_AMBER_ABOVE ? FILL_HEALTH_AMBER_RGB
                : FILL_HEALTH_RED_RGB;
    }

    /** Multiply two colours channel-wise, matching what {@code setColor} does to a tinted fill. */
    private static int tint(int rgb, int by)
    {
        int r = ((rgb >> 16) & 0xFF) * ((by >> 16) & 0xFF) / 255;
        int g = ((rgb >> 8) & 0xFF) * ((by >> 8) & 0xFF) / 255;
        int b = (rgb & 0xFF) * (by & 0xFF) / 255;
        return (r << 16) | (g << 8) | b;
    }

    /**
     * One bar's value, centred in its channel ({@code biasX} panel pixels off centre) and drawn white with a black
     * outline, matching the zeni amount and DMZ's own HUD numbers. {@code dx}, {@code dy}, {@code rgb} and
     * {@code alpha} carry DMZ's number animation (damage shake, change pulse, idle fade); 0, 0, white, 1 when still.
     *
     * <p>Not the inverse of the bar behind it any more: an inverted glyph changed hue as the bar filled and drained
     * and as the ki aura shifted, so the same number never looked the same twice. A fixed white-on-black reads
     * identically on green, amber, red, blue, purple or grey, which is what a readout should do.
     */
    private static void drawBarValue(GuiGraphics g, Minecraft mc, String text, int x, int y, int w, int h, int biasX,
            float dx, float dy, int rgb, float alpha)
    {
        if (text == null || text.isEmpty())
            return;
        int a = Math.round(clamp01(alpha) * 255.0f);
        if (a <= 3)
            return;
        float tw = mc.font.width(text) * BAR_TEXT_SCALE;
        float th = mc.font.lineHeight * BAR_TEXT_SCALE;
        float tx = x + (w - tw) / 2.0f + biasX + dx;
        float ty = y + (h - th) / 2.0f + dy;

        var pose = g.pose();
        pose.pushPose();
        pose.translate(tx, ty, 0.0f);
        pose.scale(BAR_TEXT_SCALE, BAR_TEXT_SCALE, 1.0f);
        for (int ox = -1; ox <= 1; ox++)
            for (int oy = -1; oy <= 1; oy++)
                if (ox != 0 || oy != 0)
                    g.drawString(mc.font, text, ox, oy, a << 24, false);
        g.drawString(mc.font, text, 0, 0, (a << 24) | (rgb & 0xFFFFFF), false);
        pose.popPose();
    }

    /** The string drawn once in one colour, clipped to one horizontal slice of the bar. */
    // The upper cloud, tucked into the frame's top-left corner with its left columns behind the orb. 1:1 blit at the
    // slice's native size; blend is on because the cloud's edges are alpha-feathered.
    private static void drawGlintAbove(GuiGraphics g)
    {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_GLINT_TOP, RW_GLINT_TOP_X, RW_GLINT_TOP_Y, 0.0f, 0.0f,
                RW_GLINT_TOP_W, RW_GLINT_TOP_H, RW_GLINT_TOP_W, RW_GLINT_TOP_H);
    }

    // The lower cloud, spilling out from under the bars' bottom right. Same 1:1 blit, and the sprite is already
    // mirrored on disk so nothing needs flipping here.
    //
    // The position depends on whether this player HAS a fourth bar, because the cloud only reads correctly when a
    // bar sits above it to hide its top rows. With the role energy bar present it stays exactly where the art was
    // measured; without one it moves up and left to tuck under the stamina track instead. Same test the energy bar
    // itself uses, so the two can never disagree about whether that bar is on screen.
    private static void drawGlintBelow(GuiGraphics g)
    {
        boolean hasEnergyBar = energyFillTexture() != null;
        int x = hasEnergyBar ? RW_GLINT_LOW_X : RW_GLINT_LOW_NOBAR_X;
        int y = hasEnergyBar ? RW_GLINT_LOW_Y : RW_GLINT_LOW_NOBAR_Y;
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        g.blit(TEX_RW_GLINT_LOW, x, y, 0.0f, 0.0f,
                RW_GLINT_LOW_W, RW_GLINT_LOW_H, RW_GLINT_LOW_W, RW_GLINT_LOW_H);
    }

    private static float clamp01(float v)
    {
        return v < 0.0f ? 0.0f : v > 1.0f ? 1.0f : v;
    }

    // The offscreen portrait subject: a never-ticked, never-spawned client player used purely as a render target so the
    // portrait sits in the stock idle pose without ever touching mc.player. It carries a copy of the real player's DMZ stats
    // (set via copyStats), which is what DMZ's renderer and tint layers read to pick the race/form, and it delegates its
    // skin/model to the real player so the portrait shows the player's actual skin regardless of the stand-in's own profile
    // UUID. It is constructed once per LocalPlayer instance and is never added to the level, ticked, or moved.
    private static final class PreviewStandIn extends RemotePlayer
    {
        private final AbstractClientPlayer source;

        PreviewStandIn(ClientLevel level, GameProfile profile, AbstractClientPlayer source)
        {
            super(level, profile);
            this.source = source;
            this.noPhysics = true;
            // Pin every rotation to the front so the portrait faces the camera; a fresh entity is already at 0 but set it
            // explicitly so a future construction path cannot drift the pose.
            this.setYRot(0.0f);
            this.setXRot(0.0f);
            this.yRotO = 0.0f;
            this.xRotO = 0.0f;
            this.yBodyRot = 0.0f;
            this.yBodyRotO = 0.0f;
            this.yHeadRot = 0.0f;
            this.yHeadRotO = 0.0f;
        }

        @Override
        public ResourceLocation getSkinTextureLocation()
        {
            return source.getSkinTextureLocation();
        }

        @Override
        public String getModelName()
        {
            return source.getModelName();
        }

        @Override
        public boolean isModelPartShown(PlayerModelPart part)
        {
            return source.isModelPartShown(part);
        }
    }
}
