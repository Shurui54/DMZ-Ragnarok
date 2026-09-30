package net.shurui.shuruisutilities.space;

import java.util.Optional;
import java.util.UUID;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.clone.CloneAppearance;

/**
 * The OWNER AVATAR that defends a personally-conquered planet: a stand-in for the planet's owner that renders as a copy
 * of that owner (their real Minecraft skin repainted with their DragonMineZ race body, drawn at full size) and fights
 * with a snapshot of the owner's combat stats. See {@link PlanetOwnerAvatar} for the spawn/despawn lifecycle, the stat
 * snapshot and the destruction gate.
 *
 * <h2>Chassis vs look</h2>
 * It keeps the {@link DBSagasEntity} chassis (like {@link PlanetSaiyanGarrisonEntity}) so it inherits the full saga
 * fighter AI, the confinement goal and the suppress-transform stability, but it is drawn by SU's own
 * {@code MiniClonePlayerRenderer} (a vanilla player model with the owner's skin plus the DMZ race body pass), NOT
 * DragonMineZ's saga model. Its look is a {@link CloneAppearance}: the fields below are synced so the client renders the
 * owner and mirrored to NBT so a chunk reload keeps the same face.
 *
 * <h2>Never turns on its own owner</h2>
 * {@link #canAttack} refuses the owning player, so even if the owner and a challenger are both on the planet the avatar
 * only fights the challenger; {@link PlanetOwnerAvatar} also only ever spawns it for a non-owner and drives its target.
 */
public class PlanetOwnerAvatarEntity extends DBSagasEntity implements CloneAppearance, PlanetGarrisonHome
{
    private static final EntityDataAccessor<String> RACE_NAME =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> BODY_TYPE =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR1 =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR2 =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BODY_COLOR3 =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> HAIR_COLOR =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> OWNER_NAME =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Optional<UUID>> OWNER_PROFILE =
            SynchedEntityData.defineId(PlanetOwnerAvatarEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    private static final int NO_TINT = 0xFFFFFF;

    private static final String KEY_RACE = "su_avatar_race";
    private static final String KEY_BODY_TYPE = "su_avatar_body_type";
    private static final String KEY_BODY1 = "su_avatar_body1";
    private static final String KEY_BODY2 = "su_avatar_body2";
    private static final String KEY_BODY3 = "su_avatar_body3";
    private static final String KEY_HAIR = "su_avatar_hair";
    private static final String KEY_OWNER_NAME = "su_avatar_owner_name";
    private static final String KEY_OWNER_PROFILE = "su_avatar_owner_profile";
    private static final String KEY_HOME = "su_avatar_home";

    // the planet this avatar defends, so its confinement goal keeps it on that planet's surface disc.
    private String homePlanetId = "";

    public PlanetOwnerAvatarEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
        // grounded on purpose: the avatar defends the planet's SURFACE, so it must not fly off the disc into the void.
        this.setCanFly(false);
    }

    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
    }

    @Override
    protected void defineSynchedData()
    {
        super.defineSynchedData();
        this.entityData.define(RACE_NAME, "");
        this.entityData.define(BODY_TYPE, 0);
        this.entityData.define(BODY_COLOR1, NO_TINT);
        this.entityData.define(BODY_COLOR2, NO_TINT);
        this.entityData.define(BODY_COLOR3, NO_TINT);
        this.entityData.define(HAIR_COLOR, NO_TINT);
        this.entityData.define(OWNER_NAME, "");
        this.entityData.define(OWNER_PROFILE, Optional.empty());
    }

    /** Copy the owner's DMZ race appearance onto the avatar (server side). Colours are packed 0xRRGGBB. */
    public void setRaceAppearance(String race, int bodyType, int bodyColor1, int bodyColor2, int bodyColor3,
                                  int hairColor)
    {
        this.entityData.set(RACE_NAME, race == null ? "" : race);
        this.entityData.set(BODY_TYPE, Math.max(0, bodyType));
        this.entityData.set(BODY_COLOR1, bodyColor1 & 0xFFFFFF);
        this.entityData.set(BODY_COLOR2, bodyColor2 & 0xFFFFFF);
        this.entityData.set(BODY_COLOR3, bodyColor3 & 0xFFFFFF);
        this.entityData.set(HAIR_COLOR, hairColor & 0xFFFFFF);
    }

    /** Set the owning player's name + uuid (drives the client skin resolve, the name plate, and the target exclusion). */
    public void setOwner(UUID ownerId, String ownerName)
    {
        this.entityData.set(OWNER_PROFILE, Optional.ofNullable(ownerId));
        this.entityData.set(OWNER_NAME, ownerName == null ? "" : ownerName);
    }

    @Override
    public String getRaceName()
    {
        return this.entityData.get(RACE_NAME);
    }

    @Override
    public int getBodyType()
    {
        return this.entityData.get(BODY_TYPE);
    }

    @Override
    public int getBodyColor1()
    {
        return this.entityData.get(BODY_COLOR1) & 0xFFFFFF;
    }

    @Override
    public int getBodyColor2()
    {
        return this.entityData.get(BODY_COLOR2) & 0xFFFFFF;
    }

    @Override
    public int getBodyColor3()
    {
        return this.entityData.get(BODY_COLOR3) & 0xFFFFFF;
    }

    @Override
    public int getHairColor()
    {
        return this.entityData.get(HAIR_COLOR) & 0xFFFFFF;
    }

    @Override
    public String getOwnerName()
    {
        return this.entityData.get(OWNER_NAME);
    }

    @Override
    public UUID getOwnerProfileId()
    {
        return this.entityData.get(OWNER_PROFILE).orElse(null);
    }

    /** Full size: the avatar is a full-scale copy of the owner, not a 60% mini clone. */
    @Override
    public float getCloneScale()
    {
        return 1.0F;
    }

    @Override
    public boolean isPlayerCopyLook()
    {
        return true;
    }

    @Override
    public String getHomePlanetId()
    {
        return this.homePlanetId;
    }

    public void setHomePlanet(String planetId)
    {
        this.homePlanetId = planetId == null ? "" : planetId;
    }

    // never turn on the owner: even if the owner stands beside a challenger, the avatar refuses them as a target.
    @Override
    public boolean canAttack(LivingEntity target)
    {
        UUID owner = getOwnerProfileId();
        if (owner != null && target != null && owner.equals(target.getUUID()))
        {
            return false;
        }
        return super.canAttack(target);
    }

    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    @Override
    public String getGeckolibModelName()
    {
        // never consulted (our own player-copy renderer draws it), but a real saga id keeps any other path safe.
        return "saga_raditz";
    }

    @Override
    protected void registerGoals()
    {
        super.registerGoals();
        this.goalSelector.addGoal(0, new PlanetGarrisonDefenderConfineGoal(this));
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag)
    {
        super.addAdditionalSaveData(tag);
        tag.putString(KEY_RACE, getRaceName());
        tag.putInt(KEY_BODY_TYPE, getBodyType());
        tag.putInt(KEY_BODY1, getBodyColor1());
        tag.putInt(KEY_BODY2, getBodyColor2());
        tag.putInt(KEY_BODY3, getBodyColor3());
        tag.putInt(KEY_HAIR, getHairColor());
        tag.putString(KEY_OWNER_NAME, getOwnerName());
        UUID owner = getOwnerProfileId();
        if (owner != null)
        {
            tag.putUUID(KEY_OWNER_PROFILE, owner);
        }
        if (!this.homePlanetId.isEmpty())
        {
            tag.putString(KEY_HOME, this.homePlanetId);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag)
    {
        super.readAdditionalSaveData(tag);
        setRaceAppearance(
                tag.getString(KEY_RACE), tag.getInt(KEY_BODY_TYPE),
                tag.contains(KEY_BODY1) ? tag.getInt(KEY_BODY1) : NO_TINT,
                tag.contains(KEY_BODY2) ? tag.getInt(KEY_BODY2) : NO_TINT,
                tag.contains(KEY_BODY3) ? tag.getInt(KEY_BODY3) : NO_TINT,
                tag.contains(KEY_HAIR) ? tag.getInt(KEY_HAIR) : NO_TINT);
        setOwner(tag.hasUUID(KEY_OWNER_PROFILE) ? tag.getUUID(KEY_OWNER_PROFILE) : null,
                tag.getString(KEY_OWNER_NAME));
        this.homePlanetId = tag.getString(KEY_HOME);
    }
}
