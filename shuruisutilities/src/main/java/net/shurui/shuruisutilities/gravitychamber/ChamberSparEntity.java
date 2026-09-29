package net.shurui.shuruisutilities.gravitychamber;

import java.util.UUID;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance;

/**
 * The sparring-dummy driver for the guild gravity chamber: a live, fighting NPC copied from a chosen guild or party
 * member (or the player themselves) and scaled to that member's DragonMineZ stats. It extends {@link DBSagasEntity}
 * so it inherits the full saga-fighter chassis (melee, ki skills, AI tiers, targeting), exactly like the guild-raid
 * clone and the planet garrison defender, and it wears its source member's TRUE appearance through the same client
 * puppet pipeline the guild-raid clone uses ({@link PacketCloneAppearance}). The bout itself, its non-lethal rules
 * and its win/lose handling live in {@code ChamberSparManager}.
 *
 * <p>Unlike the raid clone it is bound to exactly ONE opponent, the player who started the spar, and it will only
 * ever target that player (see {@link #canAttack} / {@link #setTarget}). DragonMineZ is a mandatory dependency of
 * every addon in this suite, so extending {@link DBSagasEntity} directly (no compat guard) is correct.
 */
public class ChamberSparEntity extends DBSagasEntity
{
    // persistentData key holding the source member's raw StatsData.save() NBT, so the appearance capsule can be
    // rebuilt and re-sent to any player who starts tracking the dummy later (a relogger, someone who left and
    // returned). Mirrors GuildRaidCloneEntity.APPEARANCE_KEY.
    public static final String APPEARANCE_KEY = "su_chamber_spar_appearance";
    public static final String SOURCE_MEMBER_KEY = "su_chamber_spar_source";

    // the opponent this dummy spars, held only in memory: the dummy is never persisted (spars end on unload), so an
    // opponent UUID that failed to resolve simply means "target nobody" until the manager cleans the dummy up.
    private UUID opponent;

    public ChamberSparEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
        // grounded: a training dummy fights on the chamber floor, it must not fly off. Same knob the garrison uses.
        this.setCanFly(false);
    }

    /** Standard DBSagasEntity attribute base; {@code CloneStats} (Ragnarok Key) overrides per spawn. */
    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
    }

    public void setOpponent(UUID opponent)
    {
        this.opponent = opponent;
    }

    public UUID getOpponent()
    {
        return opponent;
    }

    /** Never run DragonMineZ's native transform chain (it swaps the entity and would orphan the puppet/spar). */
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

    /** Graceful-degradation model only: the dummy's puppet draws the true appearance, this is the plain fallback. */
    @Override
    public String getGeckolibModelName()
    {
        return "saga_zarbon";
    }

    public void storeAppearance(CompoundTag statsNbt)
    {
        getPersistentData().put(APPEARANCE_KEY, statsNbt == null ? new CompoundTag() : statsNbt.copy());
    }

    /** Build the SPAWN appearance capsule from the data stamped on the dummy, reusing the raid-clone puppet packet. */
    public PacketCloneAppearance buildAppearanceSpawnPacket()
    {
        CompoundTag stats = getPersistentData().getCompound(APPEARANCE_KEY);
        UUID member = getPersistentData().hasUUID(SOURCE_MEMBER_KEY)
                ? getPersistentData().getUUID(SOURCE_MEMBER_KEY)
                : new UUID(0L, 0L);
        String name = getCustomName() != null ? getCustomName().getString() : "";
        return PacketCloneAppearance.spawn(getId(), member, name, stats);
    }

    private boolean isOpponent(LivingEntity target)
    {
        return opponent != null && target != null && opponent.equals(target.getUUID());
    }

    @Override
    public boolean canAttack(LivingEntity target)
    {
        return isOpponent(target) && super.canAttack(target);
    }

    @Override
    public void setTarget(LivingEntity target)
    {
        if (target != null && !isOpponent(target))
        {
            return; // never let a stray hit turn the dummy on a bystander
        }
        super.setTarget(target);
    }

    @Override
    protected void registerGoals()
    {
        super.registerGoals();
        this.targetSelector.addGoal(0, new ChamberSparTargetGoal(this));
    }
}
