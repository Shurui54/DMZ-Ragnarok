package net.shurui.shuruisutilities.client.space;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.client.hud.RadarBackgrounds;
import net.shurui.shuruisutilities.space.PacketSpaceLayoutSync;
import net.shurui.shuruisutilities.space.SpaceDimension;
import net.shurui.shuruisutilities.space.SpaceLayout;
import net.shurui.shuruisutilities.space.SurfaceDimension;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Our own planet-aware dragon-radar HUD, drawn in place of DragonMineZ's {@code RadarRenderEvent.renderRadar} whenever the
 * player is in one of SU's space dimensions (the {@link net.shurui.shuruisutilities.core.mixin.client.MixinDmzRadarDraw}
 * inject cancels DMZ's draw there and calls this instead). It reuses DMZ's radar BACKGROUND texture and DMZ's exact dot
 * geometry (same 61/87 centre, same 50-pixel reach, same yaw-relative angle) so the item still looks and feels identical;
 * only the per-blip POSITION maths and the blip drawing are ours.
 *
 * <h3>Why our own maths</h3>
 * DMZ computes a flat {@code dx = pos.getX() - player.getX()} with no dimension or planet awareness. SU's Super and Black
 * Star balls all live on ONE shared {@code planet_surface} dimension, in cells {@link SurfaceDimension#CELL_SPACING}
 * (65,536) blocks apart, so a ball on another planet is tens of thousands of blocks away in raw coordinates and DMZ would
 * draw a rim arrow pointing at a spot the player cannot walk to. Instead we map each ball by context:
 * <ul>
 *   <li><b>Player on a planet surface, ball on the SAME planet</b> (same surface cell): the ball's true relative
 *       position, exactly like DMZ.</li>
 *   <li><b>Player in SPACE, ball on a planet surface</b>: the ball's PLANET, i.e. its space body. Super balls resolve
 *       through {@link SpaceLayout#clientSuperBodies()} (a super id's surface cell matched to its synced position); Black
 *       Star balls resolve through {@link SpaceLayout#clientSurfaceBallBody(long)} (the scatter anchors). The blip then
 *       points at the body the player must fly to.</li>
 *   <li><b>Player on a planet surface, ball on a DIFFERENT planet</b>: a distinct-colour OFF-WORLD rim marker (no
 *       walkable bearing exists from one surface to another, so we never draw a misleading in-world arrow; the marker just
 *       says "there is a ball elsewhere, leave this planet").</li>
 * </ul>
 * The Super radar additionally shows every UNCLAIMED super body even when no ball exists yet (an un-beaten body has no
 * dragon-ball block, so it is absent from DMZ's position list), because those bodies are the whole point of the hunt and
 * only render within 1,000 blocks.
 *
 * <h3>What it draws</h3>
 * The radar background, then a small bordered square per blip in DMZ's own radar-dot yellow for a trackable in-world
 * target (a dot scaled by distance while within the radar range, a rim marker at the edge when further), and a hollow ring
 * in DMZ's rim-arrow gold for an off-world/unknown target so it stays distinct from a walkable in-world dot. A single
 * distance number (to the nearest trackable target) sits at the bottom of the dial. All of it is server-sourced; this class
 * only reads synced client state, never a new trust path.
 */
public final class SuRadarHud
{
    // DMZ's radar dial background. The dot sprite it also ships in this atlas is unused now: we draw blips procedurally.
    private static final ResourceLocation RADAR_TEXTURE =
            new ResourceLocation("dragonminez", "textures/gui/radar.png");

    // dial geometry, matching DMZ's renderRadar so the overlay lines up with the same item art.
    private static final int TEX_W = 121;
    private static final int TEX_H = 146;
    private static final int CENTRE_DX = 61;
    private static final int CENTRE_DY = 87;
    private static final double REACH = 50.0;

    // blip colours (ARGB), pulled straight from DMZ's own radar.png sprites so blips read native on its dial. COL_TRACK is
    // the colour of DMZ's in-range dot sprite (RGB 251,255,75); COL_OFFWORLD is the colour of DMZ's rim-arrow sprite
    // (RGB 255,205,83), drawn as a hollow ring so an off-world ball never looks like a walkable in-world dot.
    private static final int COL_TRACK = 0xFFFBFF4B;
    private static final int COL_OFFWORLD = 0xFFFFCD53;
    private static final int COL_BORDER = 0xFF000000;
    private static final int COL_DISTANCE = 0xFFFFD54A;

    // latched one-shot warning so a persistent draw fault logs once per run, never once per frame.
    private static boolean warned = false;

    private SuRadarHud()
    {
    }

    // one blip to draw: either an in-world target (a real relative XZ bearing) or an off-world marker (no bearing). dist
    // is the value fed to the nearest-distance readout, NaN when the blip has no meaningful distance.
    private static final class Blip
    {
        final double relDx;
        final double relDz;
        final boolean offWorld;
        final double dist;

        Blip(double relDx, double relDz, boolean offWorld, double dist)
        {
            this.relDx = relDx;
            this.relDz = relDz;
            this.offWorld = offWorld;
            this.dist = dist;
        }
    }

    /**
     * Draw the planet-aware radar. Called from the DMZ renderRadar inject with the same arguments DMZ resolved (the set's
     * ball positions, the range ring, and the dial's top-left corner). Never throws: any fault degrades to a blank dial.
     */
    public static void render(GuiGraphics gui, Player player, List<BlockPos> targets, int range, int centerX,
            int centerY)
    {
        try
        {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            // Same per-set dial the DMZ draw path uses (RadarBackgrounds is the single mapping both paths share), so a
            // Super/Cerulean radar keeps its custom dial in space and on planet surfaces; null falls back to DMZ's.
            ResourceLocation background = RadarBackgrounds.forHeldRadar(player);
            gui.blit(background != null ? background : RADAR_TEXTURE, centerX, centerY, 0, 0, TEX_W, TEX_H);

            int radarCenterX = centerX + CENTRE_DX;
            int radarCenterY = centerY + CENTRE_DY;

            Level level = player.level();
            boolean inSpace = SpaceDimension.isSpace(level);
            boolean onSurface = SurfaceDimension.isSurface(level);
            String set = detectSet(player);

            List<Blip> blips = new ArrayList<>();
            Set<Long> ballCells = new HashSet<>();

            if (targets != null)
            {
                for (BlockPos pos : targets)
                {
                    if (pos == null)
                    {
                        continue;
                    }
                    long bgx = SurfaceDimension.cellIndexOf(pos.getX());
                    long bgz = SurfaceDimension.cellIndexOf(pos.getZ());
                    ballCells.add(SurfaceDimension.packCell(bgx, bgz));
                    blips.add(ballBlip(player, pos, bgx, bgz, inSpace, onSurface));
                }
            }

            // Super only: add every UNCLAIMED super body that has no ball on the dial yet, so an un-beaten body (which
            // owns no dragon-ball block, hence no DMZ position) still guides the player in. A body whose ball IS already
            // on the dial (dropped, uncollected) is skipped so it does not double up.
            if ("super".equals(set))
            {
                for (PacketSpaceLayoutSync.SuperBody sb : SpaceLayout.clientSuperBodies())
                {
                    if (sb.claimed())
                    {
                        continue;
                    }
                    long gx = SurfaceDimension.cellX(sb.id());
                    long gz = SurfaceDimension.cellZ(sb.id());
                    if (ballCells.contains(SurfaceDimension.packCell(gx, gz)))
                    {
                        continue;
                    }
                    Blip b = bodyBlip(player, sb.pos(), gx, gz, inSpace, onSurface);
                    if (b != null)
                    {
                        blips.add(b);
                    }
                }
            }

            drawBlips(gui, player, blips, range, radarCenterX, radarCenterY, centerX, centerY);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        }
        catch (Throwable t)
        {
            if (!warned)
            {
                warned = true;
                LoggingHandler.sulog.warn("[Radar] Planet-aware radar HUD draw failed; dial left blank this run.", t);
            }
        }
    }

    // map a ball position (a planet_surface cell centre) to a blip for the player's current context.
    private static Blip ballBlip(Player player, BlockPos pos, long bgx, long bgz, boolean inSpace, boolean onSurface)
    {
        double ballX = pos.getX() + 0.5;
        double ballZ = pos.getZ() + 0.5;
        if (onSurface)
        {
            long pgx = SurfaceDimension.cellIndexOf(player.getX());
            long pgz = SurfaceDimension.cellIndexOf(player.getZ());
            if (bgx == pgx && bgz == pgz)
            {
                // same planet: the true, walkable relative position.
                double dx = ballX - player.getX();
                double dz = ballZ - player.getZ();
                return new Blip(dx, dz, false, Math.sqrt(dx * dx + dz * dz));
            }
            // a different planet: no walkable bearing exists from here, so an off-world marker.
            return new Blip(0.0, 0.0, true, Double.NaN);
        }
        if (inSpace)
        {
            Vec3 body = spaceBodyForCell(bgx, bgz);
            if (body != null)
            {
                double dx = body.x - player.getX();
                double dz = body.z - player.getZ();
                return new Blip(dx, dz, false, body.distanceTo(player.position()));
            }
            // no known body for this ball's planet: mark it off-world/unknown rather than pointing at a raw surface cell.
            return new Blip(0.0, 0.0, true, Double.NaN);
        }
        // not one of our dimensions (defensive; the inject only fires in space/surface): raw relative, as DMZ would.
        double dx = ballX - player.getX();
        double dz = ballZ - player.getZ();
        return new Blip(dx, dz, false, Math.sqrt(dx * dx + dz * dz));
    }

    // a marker for a super BODY (used when no ball exists yet). In space it points at the body; on a surface it is an
    // off-world marker unless the player is standing on that very body, in which case it is dropped (they are already
    // here).
    private static Blip bodyBlip(Player player, Vec3 body, long gx, long gz, boolean inSpace, boolean onSurface)
    {
        if (inSpace)
        {
            double dx = body.x - player.getX();
            double dz = body.z - player.getZ();
            return new Blip(dx, dz, false, body.distanceTo(player.position()));
        }
        if (onSurface)
        {
            long pgx = SurfaceDimension.cellIndexOf(player.getX());
            long pgz = SurfaceDimension.cellIndexOf(player.getZ());
            if (gx == pgx && gz == pgz)
            {
                return null; // standing on this body's surface: the god/ball is here, no marker needed.
            }
            return new Blip(0.0, 0.0, true, Double.NaN);
        }
        return null;
    }

    // the space-body position of the planet on a surface cell, or null. Super bodies first (derived from the synced
    // super-body list), then the Black Star scatter anchors.
    private static Vec3 spaceBodyForCell(long gx, long gz)
    {
        for (PacketSpaceLayoutSync.SuperBody sb : SpaceLayout.clientSuperBodies())
        {
            if (SurfaceDimension.cellX(sb.id()) == gx && SurfaceDimension.cellZ(sb.id()) == gz)
            {
                return sb.pos();
            }
        }
        return SpaceLayout.clientSurfaceBallBody(SurfaceDimension.packCell(gx, gz));
    }

    // draw every blip and, at the bottom of the dial, the distance to the nearest trackable one.
    private static void drawBlips(GuiGraphics gui, Player player, List<Blip> blips, int range, int radarCenterX,
            int radarCenterY, int centerX, int centerY)
    {
        int offCount = 0;
        for (Blip b : blips)
        {
            if (b.offWorld)
            {
                offCount++;
            }
        }
        double playerYaw = Math.toRadians(player.getYRot()) + Math.PI / 2.0;
        int offIdx = 0;
        double nearest = Double.NaN;

        for (Blip b : blips)
        {
            if (b.offWorld)
            {
                // off-world markers carry no real bearing, so spread them evenly around the rim as a hollow ring.
                double ang = offIdx * (Math.PI * 2.0 / Math.max(1, offCount)) - Math.PI / 2.0;
                offIdx++;
                double x = radarCenterX + REACH * Math.cos(ang);
                double y = radarCenterY + REACH * Math.sin(ang);
                ringMarker(gui, x, y, COL_OFFWORLD);
                continue;
            }
            double dist2d = Math.sqrt(b.relDx * b.relDx + b.relDz * b.relDz);
            double renderAngle = Math.atan2(b.relDz, b.relDx) - playerYaw - Math.PI / 2.0;
            double reach = dist2d <= range ? dist2d / range * REACH : REACH;
            double x = radarCenterX + reach * Math.cos(renderAngle);
            double y = radarCenterY + reach * Math.sin(renderAngle);
            marker(gui, x, y, COL_TRACK);

            double d = Double.isNaN(b.dist) ? dist2d : b.dist;
            if (Double.isNaN(nearest) || d < nearest)
            {
                nearest = d;
            }
        }

        if (!Double.isNaN(nearest))
        {
            Font font = Minecraft.getInstance().font;
            Component text = Component.literal(Long.toString(Math.round(nearest)));
            int textX = radarCenterX - font.width(text) / 2;
            int textY = centerY + TEX_H - 18;
            gui.drawString(font, text, textX, textY, COL_DISTANCE, true);
        }
    }

    // a small bordered square blip, drawn procedurally (no dot texture). The 1-pixel black border keeps it legible over
    // both the dial art and the star sky behind a transparent dial.
    private static void marker(GuiGraphics gui, double x, double y, int colour)
    {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        gui.fill(ix - 3, iy - 3, ix + 3, iy + 3, COL_BORDER);
        gui.fill(ix - 2, iy - 2, ix + 2, iy + 2, colour);
    }

    // a hollow ring blip for an off-world target: the same procedural box as marker() but with a punched-out centre, so it
    // reads as "a ball elsewhere, no walkable bearing" rather than a solid in-world dot. The caller rim-places it.
    private static void ringMarker(GuiGraphics gui, double x, double y, int colour)
    {
        int ix = (int) Math.round(x);
        int iy = (int) Math.round(y);
        gui.fill(ix - 3, iy - 3, ix + 3, iy + 3, COL_BORDER);
        gui.fill(ix - 2, iy - 2, ix + 2, iy + 2, colour);
        gui.fill(ix - 1, iy - 1, ix + 1, iy + 1, COL_BORDER);
    }

    // which special set the player's held radar is for ("super" / "blackstar"), main hand first to match DMZ's own
    // resolution order, or "" for any other radar. Item path only, so no DragonMineZ class is loaded here.
    private static String detectSet(Player player)
    {
        String main = setOf(player.getMainHandItem());
        return main.isEmpty() ? setOf(player.getOffhandItem()) : main;
    }

    private static String setOf(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return "";
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null)
        {
            return "";
        }
        String path = id.getPath();
        if (!path.contains("radar"))
        {
            return "";
        }
        if (path.contains("super"))
        {
            return "super";
        }
        if (path.contains("blackstar"))
        {
            return "blackstar";
        }
        return "";
    }
}
