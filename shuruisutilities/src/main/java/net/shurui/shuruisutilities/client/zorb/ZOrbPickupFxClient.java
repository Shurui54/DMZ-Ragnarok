package net.shurui.shuruisutilities.client.zorb;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.joml.Matrix4f;
import org.joml.Vector4f;

import net.shurui.dev.sdu.api.ClientGate;
import net.shurui.shuruisutilities.zorb.ZOrbEntity;
import net.shurui.shuruisutilities.zorb.ZOrbKind;

/**
 * Client-side pickup popups for Z orbs, driven by {@code PacketZOrbPickupFx} (id 107). When the collector picks an
 * orb up the server sends the orb id, the kind, the amount, and the orb's index / chain length; this floats a
 * rising, fading "+N TP" / "+N Zeni" / item-name label over the orb, and a small combo counter that climbs while a
 * player keeps collecting and resets after a short lull.
 *
 * <p>It is drawn in TWO passes: the world position (rising over the orb) is projected to a screen point during the
 * level render ({@link #onRenderLevel}), then the label is drawn on the HUD at that point ({@link #onRenderGui}).
 * Drawing the glyphs on the HUD rather than into the level buffer keeps them crisp and always legible, and sidesteps
 * the camera-facing / depth subtleties of in-level text.
 *
 * <p>PRIVATE: {@link #onPickup} ignores the packet unless {@code ClientGate.feature("zorbs")}, matching the
 * renderer's gate, so a keyless client shows no popup.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ZOrbPickupFxClient
{
    private ZOrbPickupFxClient() {}

    /** How long a popup lives, in client ticks (about 1.6 s). */
    private static final int LIFETIME_TICKS = 32;
    /** How far a popup rises over its life, in blocks. */
    private static final float RISE_BLOCKS = 0.7F;
    /** A combo resets if this many ticks pass with no pickup. */
    private static final int COMBO_GAP_TICKS = 50;
    /** Guard against a runaway list if popups ever outpace expiry. */
    private static final int MAX_POPUPS = 32;

    private static final List<Popup> POPUPS = new ArrayList<>();
    private static int combo = 0;
    private static long lastPickupTick = Long.MIN_VALUE;

    private static final class Popup
    {
        final double x;
        final double y;
        final double z;
        final Component text;
        final int rgb;
        final int combo;
        final long birthTick;
        // filled each frame by the level pass, read by the HUD pass.
        float screenX;
        float screenY;
        int alpha;
        boolean onScreen;

        Popup(double x, double y, double z, Component text, int rgb, int combo, long birthTick)
        {
            this.x = x;
            this.y = y;
            this.z = z;
            this.text = text;
            this.rgb = rgb;
            this.combo = combo;
            this.birthTick = birthTick;
        }
    }

    /** Called on the client thread by the pickup fx packet. */
    public static void onPickup(int entityId, ZOrbKind kind, int amount, int index, int length)
    {
        if (!ClientGate.feature("zorbs"))
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        Entity e = mc.level.getEntity(entityId);
        double px;
        double py;
        double pz;
        Component itemName = null;
        if (e != null)
        {
            px = e.getX();
            py = e.getY() + 0.45D;
            pz = e.getZ();
            if (e instanceof ZOrbEntity orb && kind == ZOrbKind.ITEM)
                itemName = orb.getStack().isEmpty() ? null : orb.getStack().getHoverName();
        }
        else if (mc.player != null)
        {
            // the orb may already be gone client-side; fall back to the player's position so the popup still shows.
            px = mc.player.getX();
            py = mc.player.getEyeY();
            pz = mc.player.getZ();
        }
        else
        {
            return;
        }

        long now = mc.level.getGameTime();
        if (now - lastPickupTick > COMBO_GAP_TICKS)
            combo = 0;
        combo++;
        lastPickupTick = now;

        Component text;
        int rgb;
        switch (kind)
        {
            case ZENI ->
            {
                text = Component.translatable("gui.dmz_ragnarok.core.zorb.fx.zeni", amount);
                rgb = ZOrbKind.ZENI.defaultColour();
            }
            case ITEM ->
            {
                text = itemName != null ? itemName : Component.translatable("gui.dmz_ragnarok.core.zorb.fx.item");
                rgb = ZOrbKind.ITEM.defaultColour();
            }
            default ->
            {
                text = Component.translatable("gui.dmz_ragnarok.core.zorb.fx.tp", amount);
                rgb = ZOrbKind.TP.defaultColour();
            }
        }

        POPUPS.add(new Popup(px, py, pz, text, rgb, combo, now));
        while (POPUPS.size() > MAX_POPUPS)
            POPUPS.remove(0);
    }

    /** Clears any live popups (disconnect, dimension change), so nothing carries into the next world. */
    public static void clear()
    {
        POPUPS.clear();
        combo = 0;
        lastPickupTick = Long.MIN_VALUE;
    }

    // Project each popup's rising world position to a screen point (and compute its fade), while the level render's
    // projection and view matrices are current. The HUD pass then draws the label there.
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event)
    {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        if (POPUPS.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;

        long now = mc.level.getGameTime();
        POPUPS.removeIf(p -> now - p.birthTick >= LIFETIME_TICKS);
        if (POPUPS.isEmpty())
            return;

        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack pose = event.getPoseStack();
        Matrix4f proj = event.getProjectionMatrix();
        float partialTick = event.getPartialTick();
        int gw = mc.getWindow().getGuiScaledWidth();
        int gh = mc.getWindow().getGuiScaledHeight();

        for (Popup p : POPUPS)
        {
            float age = (now - p.birthTick) + partialTick;
            float t = Math.min(1.0F, age / LIFETIME_TICKS);
            float rise = RISE_BLOCKS * t;
            // fade in fast, hold, fade out over the last third.
            p.alpha = (int) (255 * (t < 0.15F ? t / 0.15F : (t > 0.66F ? Math.max(0.0F, (1.0F - t) / 0.34F) : 1.0F)));

            pose.pushPose();
            pose.translate(p.x - cam.x, p.y - cam.y + rise, p.z - cam.z);
            Matrix4f mv = pose.last().pose();
            pose.popPose();

            Vector4f v = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);
            mv.transform(v);   // eye space
            proj.transform(v); // clip space
            if (v.w() <= 1.0E-4F)
            {
                p.onScreen = false; // behind the camera
                continue;
            }
            float ndcX = v.x() / v.w();
            float ndcY = v.y() / v.w();
            p.screenX = (ndcX * 0.5F + 0.5F) * gw;
            p.screenY = (1.0F - (ndcY * 0.5F + 0.5F)) * gh;
            p.onScreen = p.alpha > 4 && p.screenX >= -40 && p.screenX <= gw + 40 && p.screenY >= -20
                    && p.screenY <= gh + 20;
        }
    }

    // The HUD overlay that draws the projected labels; registered as a proper overlay so it is flushed with the HUD.
    private static final IGuiOverlay OVERLAY = (gui, g, partialTick, width, height) ->
    {
        if (POPUPS.isEmpty())
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.level == null)
            return;
        Font font = mc.font;
        for (Popup p : POPUPS)
        {
            if (!p.onScreen)
                continue;
            int argb = (p.alpha << 24) | (p.rgb & 0xFFFFFF);
            int x = (int) (p.screenX - font.width(p.text) / 2.0F);
            int y = (int) p.screenY;
            g.drawString(font, p.text, x, y, argb, true);
            if (p.combo > 1)
            {
                Component comboText = Component.translatable("gui.dmz_ragnarok.core.zorb.fx.combo", p.combo);
                int comboArgb = (p.alpha << 24) | 0xE0E0E0;
                g.drawString(font, comboText, (int) (p.screenX - font.width(comboText) / 2.0F), y + 10, comboArgb,
                        true);
            }
        }
    };

    @Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus
    {
        private ModBus() {}

        @SubscribeEvent
        public static void onRegisterOverlays(RegisterGuiOverlaysEvent event)
        {
            event.registerAboveAll("zorb_popups", OVERLAY);
        }
    }
}
