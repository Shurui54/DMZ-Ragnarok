package net.shurui.dev.shuruis_raid_bosses.entity;

import java.util.List;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkHooks;

import net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks;

/**
 * An open dimensional tear standing in the world: the thing a player walks up to, reads, and steps through.
 *
 * <p>Carries no state of its own. The rift, the raid on the other side, close time, claim state all live in
 * the rift manager (private, in the Ragnarok Key, reached through {@link RaidKeyHooks}); the entity holds only
 * the rift id (so a click can be routed) and a synched label to draw. Deliberate: an entity can be lost, duplicated or unloaded, and none of that may decide whether a
 * fight happens.
 *
 * <p>Never saved ({@link #shouldBeSaved()} refuses). Nothing else about a rift survives a restart (timers
 * reset, the arena table is in memory, an interrupted run is a failed one), so a saved tear would come back
 * with no manager entry and be scenery that swallows clicks.
 *
 * <p>A camera-facing swirl floating off the ground ({@code DimensionalTearRenderer}) with portal particles
 * and an always-visible label. The quad is the tear; particles only add depth.
 */
public class DimensionalTear extends Entity {

    /** Synched so a click can be attributed without a server lookup. */
    private static final EntityDataAccessor<String> RIFT_ID =
            SynchedEntityData.defineId(DimensionalTear.class, EntityDataSerializers.STRING);

    /**
     * Synched cosmetic LOOK, from the rift's {@code RiftDef.look}. Blank draws the normal per-uuid coloured
     * swirl; {@code "halloween"} draws the orange/purple jack-o'-lantern portal and emits ghost particles.
     * Synched so every client draws the same look with no lookup.
     */
    private static final EntityDataAccessor<String> LOOK =
            SynchedEntityData.defineId(DimensionalTear.class, EntityDataSerializers.STRING);

    /** How close a player must be for a click to be accepted, on top of vanilla's own reach check. */
    private static final double INTERACT_RANGE = 6.0;

    /** How far outside its own box a tear counts as touched. Slack, so a walk-past at speed cannot skip it. */
    private static final double CONTACT_MARGIN = 0.35;

    /**
     * Ticks after opening before a tear takes anybody. A tear opens at a surface spot chosen without regard
     * to who stands there; without this, a tear opening ON somebody pulls them into a boss fight having done
     * nothing. The grace also lets it be seen before it is walked into.
     */
    private static final int ARM_DELAY_TICKS = 20;

    /** Players already refused while standing in this tear, so they are told once and not once a tick. */
    private final java.util.Set<java.util.UUID> turnedAway = new java.util.HashSet<>();

    public DimensionalTear(EntityType<? extends DimensionalTear> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setInvulnerable(true);
    }

    public String riftId() {
        return this.entityData.get(RIFT_ID);
    }

    public void setRiftId(String id) {
        this.entityData.set(RIFT_ID, id == null ? "" : id);
    }

    /** The cosmetic look ("" / "default" = normal swirl, "halloween" = jack-o'-lantern portal). Never null. */
    public String look() {
        return this.entityData.get(LOOK);
    }

    public void setLook(String look) {
        this.entityData.set(LOOK, look == null ? "" : look);
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(RIFT_ID, "");
        this.entityData.define(LOOK, "");
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            spawnAmbience();
            return;
        }
        if (this.tickCount >= ARM_DELAY_TICKS) {
            checkForWalkIn();
        }
    }

    /**
     * A tear is entered by walking into it, portal-style. Contact is checked here, not via collision,
     * because the tear is deliberately not solid: a player must be able to stand in it, and a solid tear
     * would shove entities around the spot it opened in. Spectators pass through.
     */
    private void checkForWalkIn() {
        if (!RaidKeyHooks.get().tearsLive()) {
            return;
        }
        List<Player> touching = this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(CONTACT_MARGIN));
        // Drop anyone who walked out (told again next time), keep anyone still inside, or a player who
        // cannot take the tear gets the same refusal twenty times a second.
        turnedAway.retainAll(touching.stream().map(Player::getUUID).collect(java.util.stream.Collectors.toSet()));
        for (Player player : touching) {
            if (!(player instanceof ServerPlayer serverPlayer) || player.isSpectator()) {
                continue;
            }
            if (!turnedAway.add(player.getUUID())) {
                continue; // already refused while standing here
            }
            RaidKeyHooks.get().enterTear(serverPlayer, this);
            return; // the first one through takes it; the manager has already removed this tear
        }
    }

    /**
     * Portal-particle churn with a reverse-portal core, so it reads as something being pulled through.
     * Client-side only, no authority: a client that skips it just sees a label floating in the air.
     */
    private void spawnAmbience() {
        if ("halloween".equalsIgnoreCase(look())) {
            spawnHalloweenAmbience();
            return;
        }
        Level level = this.level();
        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        for (int i = 0; i < 4; i++) {
            double ox = (this.random.nextDouble() - 0.5) * 1.6;
            double oy = this.random.nextDouble() * 2.2;
            double oz = (this.random.nextDouble() - 0.5) * 1.6;
            level.addParticle(ParticleTypes.PORTAL, x + ox, y + oy, z + oz,
                    -ox * 0.35, 0.05, -oz * 0.35);
        }
        if (this.random.nextInt(3) == 0) {
            level.addParticle(ParticleTypes.REVERSE_PORTAL, x, y + 1.0 + this.random.nextDouble() * 0.4, z,
                    0.0, 0.02, 0.0);
        }
        if (this.random.nextInt(12) == 0) {
            level.addParticle(ParticleTypes.DRAGON_BREATH,
                    x + (this.random.nextDouble() - 0.5) * 1.2,
                    y + 0.2 + this.random.nextDouble() * 1.8,
                    z + (this.random.nextDouble() - 0.5) * 1.2,
                    0.0, 0.01, 0.0);
        }
    }

    /**
     * The Halloween tear's GHOST ambience: vanilla soul and sculk-soul motes drifting UP, plus our tinted glow
     * particle in pale purple. Client-side only and rate-limited so it reads as a haunted portal without
     * spamming particles. No server authority: a client that skips it still sees the swirl and the label.
     */
    private void spawnHalloweenAmbience() {
        Level level = this.level();
        double x = this.getX();
        double y = this.getY();
        double z = this.getZ();
        // A gentle upward drift of souls, the ghost core of the look.
        for (int i = 0; i < 2; i++) {
            double ox = (this.random.nextDouble() - 0.5) * 1.4;
            double oy = this.random.nextDouble() * 2.2;
            double oz = (this.random.nextDouble() - 0.5) * 1.4;
            level.addParticle(ParticleTypes.SOUL, x + ox, y + oy, z + oz, 0.0, 0.04, 0.0);
        }
        if (this.random.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.SCULK_SOUL,
                    x + (this.random.nextDouble() - 0.5) * 1.2,
                    y + 0.3 + this.random.nextDouble() * 1.8,
                    z + (this.random.nextDouble() - 0.5) * 1.2,
                    0.0, 0.05, 0.0);
        }
        // Pale-purple glow motes rising through the portal, additive so a cluster reads as spectral light.
        if (this.random.nextInt(2) == 0) {
            level.addParticle(HALLOWEEN_GLOW,
                    x + (this.random.nextDouble() - 0.5) * 1.4,
                    y + 0.2 + this.random.nextDouble() * 2.0,
                    z + (this.random.nextDouble() - 0.5) * 1.4,
                    0.0, 0.03, 0.0);
        }
    }

    /**
     * Pale-purple tinted glow, built once (the options are immutable, so one instance serves every spawn: a fresh
     * one per particle would allocate every client tick). Uses the suite's own tinted glow so the colour is drawn
     * exactly, unlike vanilla dust which muddies a picked hex.
     */
    private static final net.shurui.shuruisutilities.particle.TintedParticleOptions HALLOWEEN_GLOW =
            net.shurui.shuruisutilities.particle.RgParticles.glow(0xC79BFF, 0.8F, 40, -0.6F);

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (this.level().isClientSide || hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.sidedSuccess(this.level().isClientSide);
        }
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }
        if (this.distanceTo(player) > INTERACT_RANGE) {
            return InteractionResult.PASS;
        }
        // The manager owns the claim (open? this player allowed? remove entity?), so two players clicking
        // in the same tick cannot both get in.
        if (!RaidKeyHooks.get().tearsLive()) {
            return InteractionResult.PASS;
        }
        RaidKeyHooks.get().enterTear(serverPlayer, this);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        // Clickable despite having no model: without this the interact above can never fire.
        return true;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setRiftId(tag.getString("riftId"));
        setLook(tag.getString("look"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("riftId", riftId());
        tag.putString("look", look());
    }

    @Override
    public Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
