package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.ki.KiBlastEntity;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/** The only class naming DMZ's blast entity for the trapping orb. See {@link TrapOrb}. */
final class TrapOrbImpl
{
    private TrapOrbImpl() {}

    /** Resize a live orb. Only meaningful for the ki blast this class spawns. */
    static void resize(Entity orb, float diameter)
    {
        try
        {
            if (orb instanceof KiBlastEntity blast && blast.getSize() != diameter)
                blast.setSize(diameter);
        }
        catch (Throwable ignored)
        {
        }
    }

    static Entity spawn(LivingEntity owner, ServerLevel level, float diameter,
                        int colorMain, int colorBorder, int colorOutline)
    {
        try
        {
            KiBlastEntity orb = new KiBlastEntity(level, owner);
            // setupKiBlastPlayer is the Big Bang shape (render type 1) and adds the entity to the world itself.
            orb.setupKiBlastPlayer(owner, 0.0f, 0.0f, colorMain, colorBorder, colorOutline, diameter);
            orb.setOwner(owner);

            // IT HAS TO CLAIM TO BE FIRING, or DMZ deletes it within a tick.
            //
            // TickHandler tidies up after a cast by calling findChargingEntity, which is "any ki projectile within
            // thirty blocks that this player owns and that is NOT firing", and discarding it. A sphere cast is
            // exactly the moment that tidy-up runs, so an orb left un-fired on its caster is destroyed on the very
            // next tick. That is why this move looked like it was spawning no orb at all.
            //
            // Everything the firing branch would otherwise do is defused instead of avoided:
            //  - maxLife 99999 keeps the "life expired, explode" check out of reach;
            //  - cast time 0 stops DMZ dragging the ball back onto its OWNER every tick, which would fight follow();
            //  - the area pulse, the only damage a firing ball deals on its own, is neutered by heal + zero damage:
            //    applyDamageOrHeal's heal branch heals by zero and returns without touching anything hostile;
            //  - an empty technique id means no XP, no secondary effect and no animation packet, and it also keeps
            //    the ball out of DMZ's movement-restriction check on nearby firing projectiles.
            // Every point this move deals is dealt by the prison ticker.
            orb.setFiring(true);
            orb.setHeal(true);
            orb.setKiDamage(0.0f);
            orb.setMaxLife(99999);
            orb.setCastTime(0);
            orb.setCastOffsets(0.0f, 0.0f, 0.0f);
            orb.setDeltaMovement(0.0, 0.0, 0.0);
            return orb;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[visuals] trap orb failed: {}", t.toString());
            return null;
        }
    }
}
