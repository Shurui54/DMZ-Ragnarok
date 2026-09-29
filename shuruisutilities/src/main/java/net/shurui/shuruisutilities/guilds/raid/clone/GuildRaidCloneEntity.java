package net.shurui.shuruisutilities.guilds.raid.clone;

import java.util.UUID;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.level.Level;

/**
 * The server-side DRIVER for one defending guild clone. This is the real, live entity: it fights, takes damage,
 * and dies. It extends DragonMineZ's {@link DBSagasEntity} so it inherits the full combat NPC chassis (battle
 * power, ki blasts, ki-skill pool, AI tiers, fly speed, scale) that {@code CloneStats} configures on it, exactly
 * like the shuruis_raid_bosses saga bosses. It is deliberately made INVISIBLE (not removed) once its client-side
 * appearance puppet exists, so hitboxes, damage and AI stay real while DragonMineZ's player renderer draws a
 * true-appearance {@link net.shurui.shuruisutilities.guilds.raid.clone.client puppet} in its place.
 *
 * <h2>Why a subclass and not a raw saga entity</h2>
 * The raid bosses addon reuses DragonMineZ's own concrete saga entity types. Guild raids are a core SU feature
 * that must run with any subset of siblings absent, and the clone needs a few clone-specific behaviours: it must
 * NEVER run DragonMineZ's native transform chain ({@link #hasTransformation()} is forced false, because the
 * clone's single scripted transform is driven by {@code GuildRaid}), and
 * it carries the driving-raid id so its death can be routed back to the right raid. A tiny SU-owned subclass,
 * registered through SU's own {@link GuildRaidCloneEntities DeferredRegister}, gives us that without touching
 * DragonMineZ's registry.
 *
 * <p>DragonMineZ is a mandatory dependency of every addon in this suite, so extending {@link DBSagasEntity}
 * directly (no compat guard) is correct here: the class simply cannot load without DragonMineZ, which is always
 * present.
 */
public class GuildRaidCloneEntity extends DBSagasEntity
{
    /**
     * persistentData key holding the id of the OWNING (defender) guild this clone defends. That guild id also
     * identifies the raid, because a guild can be in at most one raid at a time, so it is how a clone re-finds its
     * live raid at AI-tick time to decide who it may attack. Stored in ForgeData, so it survives a chunk reload
     * mid-raid; across a full restart the live raid is gone and the clone is swept, so no target ever resolves.
     */
    public static final String RAID_ID_KEY = "su_guild_raid_id";

    /** persistentData key holding the UUID (as a string) of the defending member this clone was copied from. */
    public static final String SOURCE_MEMBER_KEY = "su_guild_raid_source";

    /**
     * persistentData key holding the source member's raw {@code StatsData.save()} NBT: the appearance capsule the
     * client puppet is built from (race, colours, hair, selected form). Stamped once at spawn and kept for the
     * clone's whole life so the capsule can be RE-SENT to any player who starts tracking the clone later (a
     * relogger, a late raid joiner, or anyone who walked out of range and back). Without this the capsule would
     * only exist as transient spawn-time state and those players would keep seeing the plain saga fallback body.
     * Stored in ForgeData, so it survives a chunk reload mid-raid.
     */
    public static final String APPEARANCE_KEY = "su_guild_raid_appearance";

    public GuildRaidCloneEntity(EntityType<? extends DBSagasEntity> type, Level level)
    {
        super(type, level);
    }

    /** Standard DBSagasEntity attribute base, reused verbatim; {@code CloneStats} overrides the values per clone. */
    public static AttributeSupplier.Builder createAttributes()
    {
        return DBSagasEntity.createAttributes();
    }

    /**
     * The clone's single scripted transform (base -&gt; strongest form) is driven entirely by
     * {@code GuildRaid}, which re-stats this same entity in place. We never
     * want DragonMineZ's native saga transform chain (which spawns a NEW form entity and discards this one) to fire,
     * because that would replace the driver mid-fight and orphan its puppet. Forcing this false keeps the entity
     * identity stable for its whole life.
     */
    @Override
    protected boolean hasTransformation()
    {
        return false;
    }

    /** Never advertises a next form, for the same reason {@link #hasTransformation()} is false. */
    @Override
    public EntityType<? extends DBSagasEntity> getNextTransform()
    {
        return null;
    }

    /**
     * Model name handed to GeckoLib for the (normally hidden) driver body. The driver is invisible whenever its
     * appearance puppet renders, so this only matters as a graceful-degradation fallback: if the puppet fails, the
     * driver becomes visible and renders as this plain humanoid saga model rather than crashing. A real DragonMineZ
     * saga model id is used so the resource always resolves.
     */
    @Override
    public String getGeckolibModelName()
    {
        return "saga_zarbon";
    }

    /** Convenience: the id of the raid driving this clone, or null if it was spawned outside a raid. */
    public UUID sourceMember()
    {
        CompoundTag pd = getPersistentData();
        if (!pd.hasUUID(SOURCE_MEMBER_KEY))
        {
            return null;
        }
        return pd.getUUID(SOURCE_MEMBER_KEY);
    }

    /** The owning (defender) guild id this clone defends, or null if it was spawned outside a raid. */
    public String owningGuildId()
    {
        String id = getPersistentData().getString(RAID_ID_KEY);
        return id.isEmpty() ? null : id;
    }

    /** Stamp the source member's appearance NBT onto this clone at spawn, so the capsule can be rebuilt later. */
    public void storeAppearance(CompoundTag statsNbt)
    {
        getPersistentData().put(APPEARANCE_KEY, statsNbt == null ? new CompoundTag() : statsNbt.copy());
    }

    /**
     * Build the SPAWN appearance capsule for this clone from the data stamped on it at spawn. This is the single
     * capsule-build path, reused by the spawn-time broadcast in {@code GuildRaidClones} and by the per-player
     * re-send when a client starts tracking the clone, so both send an identical packet without re-serialising the
     * source member's stats. The name comes from the (already-set) custom name and the member id from
     * {@link #sourceMember()}; an offline member with no stored stats yields an empty-NBT capsule, exactly as at
     * spawn (a default-appearance puppet, still better than the plain saga fallback).
     */
    public PacketCloneAppearance buildAppearanceSpawnPacket()
    {
        CompoundTag stats = getPersistentData().getCompound(APPEARANCE_KEY);
        UUID member = sourceMember();
        if (member == null)
        {
            member = new UUID(0L, 0L);
        }
        String name = getCustomName() != null ? getCustomName().getString() : "";
        return PacketCloneAppearance.spawn(getId(), member, name, stats);
    }

    /**
     * Hard, guild-aware target filter. Every target goal consults this through the vanilla combat
     * {@code TargetingConditions.test}, so gating here keeps DragonMineZ's inherited nearest-player and hurt-by
     * target goals from ever picking an owning-guild ally or an uninvolved bystander: a defending clone may only
     * target players on the ATTACKING side of its own raid. The decision lives in the Ragnarok Key's clone targeting
     * (through {@link net.shurui.shuruisutilities.api.key.GuildRaidHooks}; keyless it answers "nobody"),
     * which fails SAFE to "target nobody" whenever the raid cannot be resolved (ended, orphaned clone, guild lookup
     * null), because an orphaned clone mauling bystanders is far worse than an inert one.
     */
    @Override
    public boolean canAttack(LivingEntity target)
    {
        return net.shurui.shuruisutilities.api.key.GuildRaidHooks.get().cloneCanTarget(this, target)
                && super.canAttack(target);
    }

    /**
     * Final, authoritative target gate. DragonMineZ's own {@code hurt} sets the target DIRECTLY to whoever struck
     * the clone, bypassing the target goals (and thus {@link #canAttack}); this override closes that path so a
     * stray hit from an owning-guild ally can never turn the clone against its own side. A disallowed assignment is
     * simply ignored (the current legitimate target, if any, is left untouched) rather than cleared, so friendly
     * fire cannot even briefly interrupt the clone's real fight. Orphaned clones still go inert, because their
     * running target goal drops the now-illegal target on its own next tick.
     */
    @Override
    public void setTarget(LivingEntity target)
    {
        if (target != null && !net.shurui.shuruisutilities.api.key.GuildRaidHooks.get().cloneCanTarget(this, target))
        {
            return;
        }
        super.setTarget(target);
    }

    /**
     * Add SU's guild-aware target selection at top priority, ABOVE DragonMineZ's inherited retaliation and
     * nearest-player target goals, so it is the single authority for which attacker the clone engages. It expresses
     * the "fight alongside the owners" preference; the inherited goals remain only as a canAttack-gated fallback.
     */
    @Override
    protected void registerGoals()
    {
        super.registerGoals();
        this.targetSelector.addGoal(0, new GuildRaidCloneTargetGoal(this));
    }
}
