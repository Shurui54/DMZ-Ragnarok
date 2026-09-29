package net.shurui.dev.shuruis_raid_bosses.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * Default raid sign-up host: a stationary, invulnerable humanoid. Just the fallback; a raid can use any
 * registered entity type as its host. Sign-up interaction is handled globally via the {@link RaidNpcs}
 * tag, not here.
 */
public class RaidNpc extends PathfinderMob {

    /** How far away a player can be for the host to turn and look at them. */
    private static final float LOOK_RADIUS = 8.0F;

    /** Max degrees the body rotates per tick when catching up to the head. */
    private static final float BODY_TURN_SPEED = 10.0F;

    public RaidNpc(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        this.setInvulnerable(true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    @Override
    protected void registerGoals() {
        // Stationary host: only head-tracking goals, no movement.
        this.goalSelector.addGoal(0, new LookAtPlayerGoal(this, Player.class, LOOK_RADIUS));
        this.goalSelector.addGoal(1, new RandomLookAroundGoal(this));
    }

    @Override
    public void aiStep() {
        super.aiStep();
        // LookAtPlayerGoal only turns the head, so drag the body to follow it, or the host stays
        // head-cocked over its shoulder instead of facing the player.
        float diff = Mth.degreesDifference(this.yBodyRot, this.getYHeadRot());
        this.yBodyRot += Mth.clamp(diff, -BODY_TURN_SPEED, BODY_TURN_SPEED);
        this.setYRot(this.yBodyRot);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void pushEntities() {
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean canBeLeashed(Player player) {
        return false;
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return null;
    }
}
