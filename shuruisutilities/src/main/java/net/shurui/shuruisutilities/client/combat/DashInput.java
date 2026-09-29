package net.shurui.shuruisutilities.client.combat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.MovementInputUpdateEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.combat.DashMode;
import net.shurui.shuruisutilities.combat.PacketDashRequest;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;

/**
 * Turns a double tap of the dash key into a dash request.
 *
 * <p>A double tap rather than a single press, because the key doubles as an ordinary movement key for most players and
 * a single press would fire a dash every time they moved. The two presses must land inside a window that is long enough
 * to be comfortable and short enough that two deliberate separate presses are not read as one gesture.
 *
 * <p>The movement keys held at the moment of the second press choose the dash mode, so the shape of the dash is decided
 * by how the player was already moving rather than by a separate selector. Nothing is held: the mode is read once, sent,
 * and forgotten.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DashInput
{
    private DashInput() {}

    // The gap either side of which two presses are NOT one gesture. The lower bound rejects key chatter and the machine
    // gun repeat some keyboards send while a key is held.
    private static final long MIN_GAP_MILLIS = 50L;
    private static final long MAX_GAP_MILLIS = 650L;

    private static boolean wasDown = false;
    private static long lastPressMillis = 0L;

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null)
        {
            wasDown = false;
            return;
        }
        boolean down = net.shurui.shuruisutilities.client.SUKeybinds.DASH.isDown();
        // Edge triggered. Reading the raw held state would fire on every tick the key stayed down.
        if (down && !wasDown)
        {
            long now = System.currentTimeMillis();
            long gap = now - lastPressMillis;
            if (gap >= MIN_GAP_MILLIS && gap <= MAX_GAP_MILLIS)
            {
                request(mc);
                // Consumed, so a third press starts a fresh gesture rather than chaining off the second.
                lastPressMillis = 0L;
            }
            else
            {
                lastPressMillis = now;
            }
        }
        wasDown = down;
    }

    /**
     * Hold the player still while the melee clash rhythm game is running, so the WASD presses that play it do not also
     * walk them out of the fight.
     *
     * <p>This zeroes the derived movement impulses on the frame's {@code Input} only. The minigame reads the RAW
     * physical keyboard in {@link ClashRhythmOverlay} through {@code InputConstants.isKeyDown}, which is a completely
     * separate signal from this object, so it keeps seeing every press and scores normally while the body stays put.
     * The keybinds themselves are deliberately left alone: cancelling the key events would break that scoring.
     */
    @SubscribeEvent
    public static void onMovementInput(MovementInputUpdateEvent event)
    {
        if (!ClashRhythmOverlay.isActive())
            return;
        Input input = event.getInput();
        input.forwardImpulse = 0.0F;
        input.leftImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
    }

    private static void request(Minecraft mc)
    {
        // A HINT, nothing more. The server acquires the target itself over the dash's whole range, because this value
        // cannot do that job: crosshairPickEntity is bounded by the player's attack reach, so past a few blocks it is
        // always empty. Relying on it was what made the dash almost never find anyone. It is still sent because it is
        // free and it settles the one case the server cannot see, a target the player had centred at the moment they
        // pressed the key but has since looked away from.
        int targetId = 0;
        Entity aimed = mc.crosshairPickEntity;
        if (aimed != null && aimed != mc.player)
            targetId = aimed.getId();
        NetworkUtils.sendToServer(new PacketDashRequest(targetId, mode(mc).ordinal()));
    }

    /**
     * Which dash the movement keys are asking for. One key, one place you end up:
     * <ul>
     *   <li>S, back: over the top of them and down behind.</li>
     *   <li>A, left: round to their left.</li>
     *   <li>D, right: round to their right.</li>
     *   <li>W, or nothing at all: straight into them. The default, and the only one that hits or clashes, so a player
     *       who holds nothing is the one who gets the exchange.</li>
     * </ul>
     * Back is checked first and left and right cancel each other, so a player holding two directions at once always
     * gets one definite answer rather than whichever happened to be tested first.
     */
    private static DashMode mode(Minecraft mc)
    {
        if (mc.options == null)
            return DashMode.STRAIGHT;
        if (mc.options.keyDown.isDown())
            return DashMode.OVER_TOP;
        boolean left = mc.options.keyLeft.isDown();
        boolean right = mc.options.keyRight.isDown();
        if (left && !right)
            return DashMode.LEFT_ARC;
        if (right && !left)
            return DashMode.RIGHT_ARC;
        return DashMode.STRAIGHT;
    }
}
