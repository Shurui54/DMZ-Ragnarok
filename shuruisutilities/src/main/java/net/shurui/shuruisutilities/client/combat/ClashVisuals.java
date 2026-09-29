package net.shurui.shuruisutilities.client.combat;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import com.dragonminez.client.animation.IPlayerAnimatable;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Makes the two fighters in a melee clash actually throw punches.
 *
 * <p>The clash is ten seconds of two people trading blows, but nothing about the struggle swings on its own: the
 * server holds both players still while a rhythm game decides the winner. Without this they stand frozen and nose to
 * nose in the idle pose while punch sounds play out of thin air.
 *
 * <h2>Why this is driven by its own packet</h2>
 * The rhythm overlay only ever knows about the local player, because that is the only client the start packet is sent
 * to. Onlookers are exactly who this is for, so the server tells everyone nearby which two entities are locked
 * together and this fires the animation on both, whether the viewer is in the fight or watching it.
 *
 * <h2>Alternating hands, on a cadence</h2>
 * DragonMineZ's punch clips are one shots. Re-triggering one every tick would reset it to frame zero forever and it
 * would never visibly play, the same trap the dash clip had. So punches fire on a fixed cadence a little longer than
 * the clip, alternating left and right so it reads as an exchange rather than one arm twitching.
 *
 * <p>Everything DragonMineZ facing is guarded and the clip name is resolved through their own resolver where possible,
 * so a renamed animation costs the punches and never the clash.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class ClashVisuals
{
    private ClashVisuals() {}

    // Ticks between punches. Comfortably longer than a punch clip so each one plays out instead of being restarted.
    private static final int PUNCH_INTERVAL_TICKS = 7;

    // Entity id -> ticks until this fighter's next punch. Presence in the map IS "currently clashing".
    private static final Map<Integer, Integer> FIGHTERS = new HashMap<>();

    // Which hand each fighter swings next, so the two alternate rather than mirroring each other.
    private static final Map<Integer, Boolean> OFFHAND = new HashMap<>();

    /** Server says these two started or stopped clashing. */
    public static void set(int entityA, int entityB, boolean active)
    {
        if (active)
        {
            // Staggered, so the two are not perfectly in sync and the exchange reads as a trade rather than a salute.
            FIGHTERS.put(entityA, 1);
            FIGHTERS.put(entityB, 1 + PUNCH_INTERVAL_TICKS / 2);
            OFFHAND.put(entityA, false);
            OFFHAND.put(entityB, true);
        }
        else
        {
            FIGHTERS.remove(entityA);
            FIGHTERS.remove(entityB);
            OFFHAND.remove(entityA);
            OFFHAND.remove(entityB);
        }
    }

    public static void clear()
    {
        FIGHTERS.clear();
        OFFHAND.clear();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
        {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
        {
            clear();
            return;
        }
        if (FIGHTERS.isEmpty())
        {
            return;
        }

        for (Iterator<Map.Entry<Integer, Integer>> it = FIGHTERS.entrySet().iterator(); it.hasNext();)
        {
            Map.Entry<Integer, Integer> entry = it.next();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (!(entity instanceof Player player))
            {
                // Out of view or gone. Drop it rather than hold an id that will never resolve again.
                it.remove();
                OFFHAND.remove(entry.getKey());
                continue;
            }
            int left = entry.getValue() - 1;
            if (left > 0)
            {
                entry.setValue(left);
                continue;
            }
            entry.setValue(PUNCH_INTERVAL_TICKS);
            punch(player, entry.getKey());
        }
    }

    private static void punch(Player player, int id)
    {
        try
        {
            if (!(player instanceof IPlayerAnimatable animatable))
            {
                return;
            }
            boolean offhand = Boolean.TRUE.equals(OFFHAND.get(id));
            OFFHAND.put(id, !offhand);
            animatable.dragonminez$playMeleeAnimation(clip(offhand), offhand, 1.0F);
        }
        catch (Throwable ignored)
        {
            // A punch is decoration. It must never take the client tick down with it.
        }
    }

    // DragonMineZ's own resolver where it will answer, and the raw clip name if it will not. The clips live in
    // combat.animation.json as combat.one_handed_punch_left / _right.
    private static String clip(boolean offhand)
    {
        try
        {
            String resolved = com.dragonminez.client.animation.CombatAnimationResolver
                    .resolveAttack("one_handed_punch", offhand);
            if (resolved != null && !resolved.isBlank())
            {
                return resolved;
            }
        }
        catch (Throwable ignored)
        {
        }
        return "combat.one_handed_punch_" + (offhand ? "left" : "right");
    }
}
