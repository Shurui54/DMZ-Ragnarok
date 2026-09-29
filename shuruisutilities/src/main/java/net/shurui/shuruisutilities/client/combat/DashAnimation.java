package net.shurui.shuruisutilities.client.combat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.dragonminez.client.animation.IPlayerAnimatable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Puts a dashing player into DragonMineZ's flight pose and fires its dash clip.
 *
 * <p>Without this a dash moved the player and nothing else: they slid through the air in the standing idle pose, which
 * is the whole of "it does not do a flight animation". The dash is our system, so DMZ has no reason to know a player is
 * in one, and its pose controller keeps drawing whatever it last decided.
 *
 * <p>DMZ mixes {@link IPlayerAnimatable} into every {@code AbstractClientPlayer}, and its pose controller reads exactly
 * two things off it: a flying flag that selects the flight branch, and a one shot dash direction that plays a dash clip
 * over the top. Both are plain client side render state with no server authority behind them, so setting them for the
 * duration of our dash is the same thing DMZ does for its own flight and costs nothing anywhere else.
 *
 * <p>The previous flying value is remembered per player and put back when the dash ends, so a dash taken WHILE already
 * flying does not drop the player out of the flight pose the moment it finishes.
 *
 * <p>Everything DMZ facing here is guarded: the cast is an {@code instanceof} and the whole body is wrapped, so if a
 * future DMZ build drops the interface a dash goes back to being pose-less rather than throwing once per player per
 * tick inside the client tick.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class DashAnimation
{
    private DashAnimation() {}

    // DMZ's dash clip selector. Its pose controller switches on these, and anything it does not recognise falls through
    // to the forward clip, so these are the only four worth sending.
    private static final int DASH_FORWARD = 1;
    private static final int DASH_BACKWARD = 2;
    private static final int DASH_RIGHT = 3;
    private static final int DASH_LEFT = 4;

    // How square onto the facing a heading has to be before it counts as a forward or backward dash rather than a
    // sideways one. A dash at 45 degrees reads better as the sideways clip, hence a threshold above the diagonal.
    private static final double FACING_DOT = 0.55D;

    // Players we put into the flight pose, and what their flag was before we did. Only these are ever restored, so a
    // player who was genuinely flying keeps flying and one who was not is put back to standing exactly once.
    private static final Map<Integer, Boolean> FORCED_FLYING = new HashMap<>();

    // Players whose dash clip has already been fired. The clip is a one shot, so re-triggering it every tick would keep
    // resetting it to frame zero and it would never visibly play.
    private static final Set<Integer> CLIP_FIRED = new HashSet<>();

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            FORCED_FLYING.clear();
            CLIP_FIRED.clear();
            return;
        }
        for (Player player : mc.level.players())
        {
            try
            {
                apply(player);
            }
            catch (Throwable ignored)
            {
                // A pose is decoration. It must never take the client tick down with it.
            }
        }
        // Anyone who left view stops being tracked. Their flag goes with them, which is correct: the entity we would
        // restore it on no longer exists.
        FORCED_FLYING.keySet().removeIf(id -> mc.level.getEntity(id) == null);
        CLIP_FIRED.removeIf(id -> mc.level.getEntity(id) == null);
    }

    private static void apply(Player player)
    {
        if (!(player instanceof IPlayerAnimatable animatable))
            return;
        int id = player.getId();
        // Only a REAL dash gets this treatment. Ordinary fast flight sets the same decoration state, and firing DMZ's
        // dash clip for it meant every time anyone accelerated into fast flight they played what looks like a dodge,
        // because DMZ's dash and evasion clips share one controller and look alike. Fast flight already has its own
        // pose; it does not need a one shot animation on top.
        if (!DashAuraState.isRealDash(id))
        {
            Boolean previous = FORCED_FLYING.remove(id);
            CLIP_FIRED.remove(id);
            if (previous != null)
                animatable.dragonminez$setFlying(previous);
            return;
        }
        if (!FORCED_FLYING.containsKey(id))
            FORCED_FLYING.put(id, animatable.dragonminez$isFlying());
        // Re-asserted every tick rather than set once. DMZ drives this same flag from its own flight packets, and a
        // packet arriving mid dash would otherwise drop the player out of the pose for the rest of the dash.
        animatable.dragonminez$setFlying(true);
        if (CLIP_FIRED.add(id))
            animatable.dragonminez$triggerDash(direction(player));
    }

    // Which of DMZ's four dash clips fits this dash, worked out from the heading against the player's own facing. Done
    // from the heading rather than from the movement keys because it has to be right for REMOTE dashers too, and their
    // keys are something this client will never see.
    private static int direction(Player player)
    {
        Vec3 heading = DashAuraState.heading(player.getId());
        if (heading == null || heading.lengthSqr() < 1.0E-8D)
            return DASH_FORWARD;
        heading = heading.normalize();
        // Flattened onto the horizontal: a dash aimed steeply up or down is still a forward dash as far as the clip is
        // concerned, and comparing against the full look vector would call it sideways.
        Vec3 forward = Vec3.directionFromRotation(0.0F, player.getYRot());
        Vec3 flat = new Vec3(heading.x, 0.0D, heading.z);
        if (flat.lengthSqr() < 1.0E-8D)
            return DASH_FORWARD;
        flat = flat.normalize();
        double ahead = flat.dot(forward);
        if (ahead >= FACING_DOT)
            return DASH_FORWARD;
        if (ahead <= -FACING_DOT)
            return DASH_BACKWARD;
        // Right of the facing is forward rotated a quarter turn clockwise about the vertical.
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        return flat.dot(right) >= 0.0D ? DASH_RIGHT : DASH_LEFT;
    }
}
