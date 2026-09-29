package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;

import net.shurui.shuruisutilities.clone.MiniClone;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Builds the {@code su_mini_clone} technique and puts it in DMZ's predefined registry, so it appears in the skill
 * menu, equips to a slot and gets DMZ's charge, overcharge and cooldown handling for free. Names DMZ types directly,
 * so only {@link DragonTechniqueBridge} may call it, and only with DMZ present (the optional-dependency pattern).
 *
 * <p>The move charges but launches nothing: its effect is spawning miniature clones, applied by
 * {@code MixinDmzTechniqueDispatcher} when the cast is intercepted. It is therefore given an invisible, stationary
 * orb and no ki cost, and its animation is left empty so DMZ plays no ki charge clip: the charge and cast are shown
 * with DMZ's BLOCK pose, driven by {@code MoveChargeTracker} / the dispatcher mixin instead (see
 * {@link MiniClone#CHARGE_ANIMATION}).
 */
final class MiniCloneTechniqueDefs
{
    private MiniCloneTechniqueDefs() {}

    private static final int CAST_TIME_TICKS = 20;
    private static final int COOLDOWN_SECONDS = 12;

    /** Small enough to be invisible: the move charges but shows no ball. */
    private static final float INVISIBLE_ORB_SIZE = 0.05f;

    /** Near-zero, so the (suppressed) charge orb stays on the caster rather than flying off. */
    private static final float STATIONARY_SPEED = 0.01f;

    static void register()
    {
        try
        {
            PredefinedTechniques.REGISTRY.put(MiniClone.TECHNIQUE_ID, build());
            LoggingHandler.sulog.info("[miniclone] registered technique {}", MiniClone.TECHNIQUE_ID);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[miniclone] could not register technique {}: {}",
                    MiniClone.TECHNIQUE_ID, t.toString());
        }
    }

    private static KiAttackData build()
    {
        KiAttackData data = new KiAttackData();
        data.setId(MiniClone.TECHNIQUE_ID);
        data.setName("Mini Clone");
        data.setAuthor("Ragnarok");

        // MEDIUM_BALL, deliberately: only SMALL_BALL and LASER are instant, and an instant type never accrues
        // overcharge. MEDIUM_BALL charges and lets the charge climb past 100% to the 175% overcharge cap, which is
        // what a second clone keys off. It is also not movement-restricted, so the caster is free to move while
        // charging.
        data.setKiType(KiAttackData.KiType.MEDIUM_BALL);
        data.setUtility(KiAttackData.Utility.DAMAGE);

        // Colours are irrelevant (the orb is invisible and suppressed), but set to neutral values rather than left
        // at defaults.
        data.setColorInterior(0xFFFFFF);
        data.setColorExterior(0xEAF6FF);
        data.setColorOutline(0xBEE8F7);

        // Empty animation prefix: DMZ appends "_cast"/"_fire", neither of which resolves, so DMZ plays no ki clip.
        // The BLOCK pose is driven by our own packets instead, so the two do not fight over the controller.
        data.setAnimation("");

        data.setCastTime(CAST_TIME_TICKS);
        data.setCooldown(COOLDOWN_SECONDS);

        // No ki and no TP cost: the technique is a quest reward, not a paid ability.
        data.setBaseCost(0.0);
        data.setTpCost(0.0f);

        // No allowedRaces: available to every race. Race decides the clone's LOOK (Buu / Cell Jr. / player copy),
        // not whether the move may be cast, so there is nothing for DMZ's race filter to gate on here.

        data.setDamageMultiplier(1.0f);
        data.setSpeed(STATIONARY_SPEED);
        data.setSize(INVISIBLE_ORB_SIZE);
        data.setArmorPenetration(0);
        return data;
    }
}
