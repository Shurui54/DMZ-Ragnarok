package net.shurui.shuruisutilities.client.gui.preview;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import org.joml.Quaternionf;

/**
 * Draws the REAL local player, in their DragonMineZ race model, inside a GUI.
 *
 * <h2>Why this works, and why nothing here is a mixin</h2>
 * DragonMineZ's {@code com.dragonminez.mixin.client.PlayerRendererMixin} injects at the HEAD of vanilla
 * {@code PlayerRenderer.render}, asks {@code DMZRendererCache.getRenderer(player)} for a race renderer and, when
 * it gets one, delegates to {@code DMZPlayerRenderer} and cancels the vanilla body. A GUI portrait reaches that
 * same vanilla method through {@code EntityRenderDispatcher}, so {@link InventoryScreen#renderEntityInInventory}
 * draws the race model with no help from us. That is verified against the bytecode of
 * {@code libs/dragonminez-2.1.3.jar}, not inferred, and it is why the vanilla inventory portrait already shows a
 * Namekian rather than a Steve on this pack.
 *
 * <p>The one trap that path has is the first-person rig: {@code DMZRendererCache.getRenderer} picks
 * {@code DMZPOVPlayerRenderer} when {@code FirstPersonManager.shouldRenderFirstPerson} is true. That method
 * returns FALSE whenever {@code Minecraft.screen} is a non-chat screen, which every screen using this helper is,
 * so the third-person rig is selected for us and nothing has to force it. Stated here because it is a
 * behavioural dependency on DMZ, so if a future DMZ changes that predicate this is the comment to find.
 *
 * <h2>The real player, not a dummy</h2>
 * {@code FormPreview} (sdu) builds a throwaway {@code RemotePlayer} and seeds it, which is correct for previewing
 * a form that the player is NOT in. For a wardrobe or a shop the subject is the player as they are, so we render
 * {@code Minecraft.player} itself. Three things fall out of that: the GeckoLib animations play, because the
 * entity is being ticked by the game; the race, gender, form, hair and colours are right with no copy to keep in
 * step with whatever DMZ changes next; and nothing is added to {@code DMZRendererCache}'s per-UUID map, which a
 * fresh dummy per screen does leak until the next resource reload.
 *
 * <h2>Rotations are borrowed and given back</h2>
 * {@link InventoryScreen#renderEntityInInventory} does NOT save the entity's orientation, and the subject here is
 * the live local player whose yaw is also the direction they are facing in the world. So every field this touches
 * is captured first and restored in a {@code finally}, exactly as vanilla's own
 * {@code renderEntityInInventoryFollowsAngle} does for the inventory portrait. Zeroing them (rather than setting
 * them to the wanted angle) makes the pose quaternion the single source of rotation, which is the convention
 * {@code FormPreview} already established, so a drag reads the same in both screens.
 *
 * <p>Client only. Never classload on a dedicated server.
 */
public final class LivePlayerPreview
{
    private LivePlayerPreview()
    {
    }

    /** Whether there is a local player to draw at all. */
    public static boolean available()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.player != null && mc.level != null;
    }

    /**
     * Draw the local player.
     *
     * <p>Coordinates are REAL screen (gui-scaled) pixels, not virtual canvas units, because
     * {@code renderEntityInInventory} multiplies into the current pose. A screen laid out on {@code ScaledScreen}
     * converts with {@code originX() + vx * guiScale} and calls this AFTER {@code super.render} has popped its
     * scaled pose, which is what {@code FormCosmeticsScreen} already does.
     *
     * @param cx     horizontal centre
     * @param feetY  the baseline the model stands on (it grows upward)
     * @param scale  pixel size, as the inventory portrait means it
     * @param yaw    turn, radians. {@code Math.PI} faces the viewer
     * @param pitch  tilt, radians
     */
    public static void render(GuiGraphics g, int cx, int feetY, int scale, float yaw, float pitch)
    {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null)
            return;
        render(g, mc.player, cx, feetY, scale, yaw, pitch);
    }

    /**
     * As {@link #render(GuiGraphics, int, int, int, float, float)} but for a named subject, so a later shop or
     * inspect screen can draw somebody else's character without a second copy of this code.
     */
    public static void render(GuiGraphics g, LivingEntity subject, int cx, int feetY, int scale,
            float yaw, float pitch)
    {
        if (g == null || subject == null || scale <= 0)
            return;

        // rotateZ(PI) puts the model upright (the inventory scales Y by -scale); yaw then pitch orbit it.
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        pose.rotateY(yaw);
        pose.rotateX(pitch);

        float bodyRot = subject.yBodyRot;
        float bodyRotO = subject.yBodyRotO;
        float yRot = subject.getYRot();
        float yRotO = subject.yRotO;
        float xRot = subject.getXRot();
        float xRotO = subject.xRotO;
        float headRot = subject.yHeadRot;
        float headRotO = subject.yHeadRotO;
        try
        {
            // Freeze the subject's own orientation so the quaternion above is the only rotation applied,
            // otherwise the interpolated yBodyRot fights the drag and the model wobbles as the player turns.
            subject.yBodyRot = 0.0F;
            subject.yBodyRotO = 0.0F;
            subject.setYRot(0.0F);
            subject.yRotO = 0.0F;
            subject.setXRot(0.0F);
            subject.xRotO = 0.0F;
            subject.yHeadRot = 0.0F;
            subject.yHeadRotO = 0.0F;
            InventoryScreen.renderEntityInInventory(g, cx, feetY, scale, pose, null, subject);
        }
        catch (Throwable ignored)
        {
            // A render hiccup must cost the portrait, never the screen it sits in.
        }
        finally
        {
            // Unconditional: the subject is the live local player, and leaving their yaw at zero would turn them
            // in the world and send that turn to the server on the next tick.
            subject.yBodyRot = bodyRot;
            subject.yBodyRotO = bodyRotO;
            subject.setYRot(yRot);
            subject.yRotO = yRotO;
            subject.setXRot(xRot);
            subject.xRotO = xRotO;
            subject.yHeadRot = headRot;
            subject.yHeadRotO = headRotO;
        }
    }

    /**
     * How much bigger than a default body this character is drawn, so a preview box can divide by it and a
     * Namekian in a tall form still fits the frame instead of growing out of the top.
     *
     * <p>Reads DMZ's RESOLVED model scaling (form applied), the same number the world render uses, and returns
     * 1.0 for anything it cannot read rather than guessing. Never throws.
     */
    public static float modelInflation()
    {
        Minecraft mc = Minecraft.getInstance();
        return mc == null ? 1.0F : modelInflation(mc.player);
    }

    /** As {@link #modelInflation()} for a named subject. */
    public static float modelInflation(Player subject)
    {
        if (subject == null)
            return 1.0F;
        try
        {
            StatsData stats = StatsProvider.<StatsData>get(StatsCapability.INSTANCE, subject).resolve().orElse(null);
            if (stats == null)
                return 1.0F;
            Float[] scaling = stats.getCharacter().getResolvedModelScaling();
            if (scaling == null)
                scaling = stats.getCharacter().getModelScaling();
            if (scaling == null)
                return 1.0F;
            float max = 0.0F;
            for (Float f : scaling)
                if (f != null)
                    max = Math.max(max, f);
            return max <= 0.0F ? 1.0F : Math.max(0.25F, max);
        }
        catch (Throwable t)
        {
            return 1.0F;
        }
    }
}
