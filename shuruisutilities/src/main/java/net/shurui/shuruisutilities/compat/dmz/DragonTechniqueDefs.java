package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;

import net.shurui.shuruisutilities.dragons.DragonMove;
import net.shurui.shuruisutilities.dragons.DragonRaces;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Builds the shadow dragon moves as DMZ techniques and puts them in DMZ's own predefined registry, so they appear in
 * the skill menu, equip to a technique slot, and get DMZ's cast time, cooldown and race filter without us
 * reimplementing any of it.
 *
 * <p>Names DMZ types directly, so only {@link DragonTechniqueBridge} may call it, and only with DMZ present
 * (the optional-dependency pattern).
 *
 * <p>WHAT DMZ PROVIDES AND WHAT WE PROVIDE. The registry entry supplies the menu row, the projectile shape, the
 * colours, the cast time, the cooldown and the race gate. It CANNOT express any of the actual signature effects, so
 * freezing, burning, tornadoes, gas and lightning are all applied by our own code when the cast is intercepted.
 *
 * <p>COST IS NOT KI. Every one of these is registered with a base cost of zero, so DMZ charges no ki for them.
 * They are paid for in malice instead, taken at cast time. Do not give these a ki cost as well: the bar IS the cost.
 */
final class DragonTechniqueDefs
{
    private DragonTechniqueDefs() {}

    /**
     * Cast time, in ticks, and cooldown in SECONDS.
     *
     * <p>COOLDOWN IS SECONDS, NOT TICKS. {@code getActualCooldown()} returns {@code cooldown * 20}, so the 60 this
     * used to hold was a full MINUTE between casts, not three seconds. DMZ's own techniques pass 10 to 45 here.
     *
     * <p>Deliberately identical across the moves: they all cost the same quarter of the malice bar, so pacing is the
     * bar's job and letting individual moves differ here would quietly make some of them strictly better for the
     * same price.
     *
     * <p>Note that {@link #CAST_TIME_TICKS} does NOT drive the charge: {@code getBaseChargeTicks} reads DMZ's own
     * per-KiType technique config, so the charge length is DMZ's to decide and setting cast time here only affects
     * what DMZ does with the value elsewhere.
     */
    private static final int CAST_TIME_TICKS = 20;
    private static final int COOLDOWN_SECONDS = 8;

    /**
     * Omega's ball, the one genuine projectile. Sized like DMZ's spirit bomb rather than to its splash radius: the
     * splash is 9 blocks in every direction, and a ball drawn that wide would be a wall.
     */
    private static final float OMEGA_BALL_SIZE = 6.0f;
    private static final float BALL_SPEED = 0.5f;
    private static final int BALL_ARMOR_PEN = 10;

    /** Near-zero, so an area move's ball stays centred on its caster rather than flying off. */
    private static final float STATIONARY_SPEED = 0.01f;

    /** Small enough to be invisible: the role moves charge, but neither of them shows a ball. */
    private static final float ROLE_CHARGE_ORB_SIZE = 0.05f;

    /**
     * SIZE IS THE ORB'S DIAMETER IN BLOCKS.
     *
     * <p>Not a guess: {@code KiMeshFactory.getSphereMesh()} builds a UNIT sphere, and {@code KiProjectileRenderer}
     * scales it by {@code size} and then by {@code 0.5}, so the drawn radius is {@code size / 2}. An area move's orb
     * therefore has to be given twice its effect radius to actually stand for the ground it covers, which is why
     * these used to read as a small ball floating inside a much larger effect.
     */
    private static float orbDiameterFor(DragonMove move)
    {
        if (move == DragonMove.MINUS_ENERGY_POWER_BALL)
            return OMEGA_BALL_SIZE;
        // The hurricane's body is DMZ's modelled funnel and the wall of wind around it. A ball on top of that reads
        // as a second, unrelated attack, so it charges with none.
        if (move == DragonMove.HURRICANE_FURY)
            return ROLE_CHARGE_ORB_SIZE;
        return (float) (move.effectRadius() * 2.0);
    }

    /**
     * Register every move, replacing any entry from a previous run.
     *
     * <p>Idempotent, because it runs on every server start and the registry is a static map that survives between
     * them in single player. Re-putting the same id simply refreshes the definition, which is also what makes a
     * definition change take effect on reload rather than only on a full restart.
     */
    static void register()
    {
        int count = 0;
        for (DragonMove move : DragonMove.values())
        {
            try
            {
                PredefinedTechniques.REGISTRY.put(move.id, build(move));
                count++;
            }
            catch (Throwable t)
            {
                // One malformed move must not stop the rest registering.
                LoggingHandler.sulog.warn("[dragons] could not register technique {}: {}", move.id, t.toString());
            }
        }
        LoggingHandler.sulog.info("[dragons] registered {} shadow dragon techniques", count);

        int roleCount = 0;
        for (net.shurui.shuruisutilities.god.RoleMove move : net.shurui.shuruisutilities.god.RoleMove.values())
        {
            try
            {
                PredefinedTechniques.REGISTRY.put(move.id, buildRole(move));
                roleCount++;
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.warn("[god] could not register technique {}: {}", move.id, t.toString());
            }
        }
        LoggingHandler.sulog.info("[god] registered {} role techniques", roleCount);
        forceDispatcherClassLoad();
    }

    /**
     * Touch {@code TechniqueDispatcher} so its mixin applies (and logs) at BOOT rather than the first time anyone
     * casts a technique.
     *
     * <p>WHY THIS EXISTS. Every move in this batch is delivered by an injection into that class, and that injection
     * is {@code require = 0}, so if it ever stops resolving it fails SILENTLY: the techniques still register, still
     * appear in the menu, and simply do nothing when cast. Mixins apply lazily on first classload, so a server that
     * nobody has cast on yet gives no evidence either way, which makes "did it bind" unanswerable from a boot log.
     *
     * <p>Referencing the class here forces the transform during startup, so the mixin's own "Mixing ... into
     * com.dragonminez.common.stats.techniques.TechniqueDispatcher" line appears in the boot log. Its ABSENCE from a
     * boot log is then real evidence the injection did not bind. Same idea as the one-shot confirmation in
     * {@code MixinDmzGiantBallClash}.
     *
     * <p>Costs one class load at startup and changes no behaviour.
     */
    private static void forceDispatcherClassLoad()
    {
        try
        {
            LoggingHandler.sulog.info("[dragons] technique dispatcher loaded: {}",
                    com.dragonminez.common.stats.techniques.TechniqueDispatcher.class.getName());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragons] could not load the technique dispatcher; the moves will not fire: {}",
                    t.toString());
        }
    }

    /**
     * A role technique (God of Destruction). Deliberately has NO {@code allowedRaces}: the holder can be any race, so
     * there is nothing for DMZ's race filter to key on and the gate is the title, applied at cast time by
     * {@code RoleMoveHandler}. Consequence to be aware of: the technique is visible to anyone who has it unlocked,
     * and refuses on use rather than being hidden.
     */
    private static KiAttackData buildRole(net.shurui.shuruisutilities.god.RoleMove move)
    {
        KiAttackData data = new KiAttackData();
        data.setId(move.id);
        data.setName(move.displayName);
        data.setAuthor("Ragnarok");
        // GIANT_BALL, not AREA, for the same reason as the dragon moves: an AREA technique never charges, so it
        // would fire on key-down. Both of these are aimed by look ray in our own code and the cast seam cancels the
        // projectile, so nothing is launched - the ball type is here purely to get a real charge and its animation.
        // MEDIUM_BALL, not GIANT_BALL. isMovementRestrictedType is true for WAVE, GIANT_BALL, BEAM, EXPLOSION and
        // BARRAGE, so a giant ball roots the caster in place for the whole charge - which is why these moves could
        // not be moved during. MEDIUM_BALL still charges (only SMALL_BALL and LASER are instant), so the charged
        // release path stays reachable, but it leaves the caster free to move.
        data.setKiType(KiAttackData.KiType.MEDIUM_BALL);
        data.setUtility(KiAttackData.Utility.DAMAGE);
        data.setColorInterior(move.colorInterior);
        data.setColorExterior(move.colorExterior);
        data.setColorOutline(move.colorExterior);
        // Same reasoning as the dragon moves: without this the role techniques play no animation either.
        data.setAnimation(move.animation);
        data.setCastTime(CAST_TIME_TICKS);
        data.setCooldown(COOLDOWN_SECONDS);
        // Paid in destruction energy, never ki.
        data.setBaseCost(0.0);
        data.setTpCost(0.0f);
        data.setDamageMultiplier(1.0f);
        // The sphere and hakai are aimed by look ray in our own code, so their ball stays put.
        applyBallShape(data, false);
        // NEITHER ROLE MOVE SHOWS A BALL. A charging entity has to exist - it is the only thing that makes DMZ's
        // charged-release path reachable at all - but nothing says it has to be visible, and hakai in particular
        // must read as an erasure rather than as something thrown. A hair-thin orb is the charge with no ball.
        data.setSize(ROLE_CHARGE_ORB_SIZE);
        return data;
    }

    private static KiAttackData build(DragonMove move)
    {
        KiAttackData data = new KiAttackData();
        data.setId(move.id);
        data.setName(move.displayName);
        data.setAuthor("Ragnarok");

        // SHAPE, AND WHY NOT "AREA".
        //
        // The obvious choice for a caster-centred move is KiType.AREA, and that is what these used to be. It is
        // wrong: an AREA technique never spawns a charging entity, and DMZ's TickHandler only takes its CHARGED
        // path when one exists. Everything else falls to the tap-fire path, which fires the instant the key goes
        // down. So AREA moves could not be charged at all - they went off immediately, with no wind-up and no
        // charge animation, however long the key was held.
        //
        // Giving them a charging shape instead means DMZ builds the charge, plays the _cast clip and hands us the
        // release, and our own cast seam then cancels the projectile so nothing is actually launched. The charge
        // and the animation are DMZ's; only the effect is ours.
        // MEDIUM_BALL, not GIANT_BALL. isMovementRestrictedType is true for WAVE, GIANT_BALL, BEAM, EXPLOSION and
        // BARRAGE, so a giant ball roots the caster in place for the whole charge - which is why these moves could
        // not be moved during. MEDIUM_BALL still charges (only SMALL_BALL and LASER are instant), so the charged
        // release path stays reachable, but it leaves the caster free to move.
        data.setKiType(KiAttackData.KiType.MEDIUM_BALL);
        data.setUtility(KiAttackData.Utility.DAMAGE);

        data.setColorInterior(move.colorInterior);
        data.setColorExterior(move.colorExterior);
        data.setColorOutline(move.colorOutline);

        // THE ANIMATION. DMZ drives a technique's charge-up and firing clips from this field
        // ({@code KiAttackData.getAnimationPrefix}); leaving it unset is why these moves originally played
        // nothing at all. The value is one of DMZ's own technique animation prefixes, chosen per move for the
        // pose that matches it.
        data.setAnimation(move.animation);

        data.setCastTime(CAST_TIME_TICKS);
        data.setCooldown(COOLDOWN_SECONDS);

        // Zero ki cost and zero TP: these are paid for in malice. See the class note.
        data.setBaseCost(0.0);
        data.setTpCost(0.0f);

        // DMZ's own race filter. This is what gates each move to its dragon, and what gives Omega the whole kit,
        // since DragonRaces adds the base race to every move's list.
        data.setAllowedRaces(DragonRaces.allowedRacesFor(move));

        // Damage is computed by us from the caster's live stats at cast time, so DMZ's own multiplier is left at a
        // neutral 1 rather than being used as a second, hidden scaling factor.
        data.setDamageMultiplier(1.0f);
        // Only Omega's ball is a travelling projectile; every other dragon move is an area effect whose ball
        // should sit on the caster.
        boolean travels = move == DragonMove.MINUS_ENERGY_POWER_BALL;
        if (travels)
        {
            // The one move that IS a ball: big, travelling, and allowed to root the caster like any other giant ball.
            data.setKiType(KiAttackData.KiType.GIANT_BALL);
        }
        applyBallShape(data, travels);
        // The charge orb stands for the area the move covers, so it is sized from that area. See orbDiameterFor.
        data.setSize(orbDiameterFor(move));
        return data;
    }

    /**
     * Speed and armour penetration. The SIZE is set by the caller, because it means something different for an area
     * move's orb (the area it stands for) than for Omega's projectile or a role move's invisible charge.
     *
     * <p>These were once missing entirely, which is why the ball rendered as a speck: {@code getActualSize()} returns
     * the raw {@code size} field, which defaults to nothing, so a technique that never sets it has no visible body at
     * all.
     */
    private static void applyBallShape(KiAttackData data, boolean travels)
    {
        // An area move's ball must NOT fly away: at almost zero speed it hangs on the caster, which is the
        // "giant ball around the player, like Final Explosion" this wants. Only the moves that are genuinely
        // projectiles travel.
        data.setSpeed(travels ? BALL_SPEED : STATIONARY_SPEED);
        data.setArmorPenetration(BALL_ARMOR_PEN);
    }
}
