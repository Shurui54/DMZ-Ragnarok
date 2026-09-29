package net.shurui.shuruisutilities.npcregion;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The full editable spawn definition for an {@link NpcRegion}: which entity to spawn, its universal
 * attributes and the NPC-only options (ki attack/power, behaviour, scale, model), plus spawn control and
 * kill rewards. Holds only primitives + vanilla ids, so it serializes cleanly to the region JSON (Gson) and
 * over the network, and works whether or not {@code sdu} is installed.
 *
 * <p>Ported from the advanced-spawner {@code SpawnerConfig} in Shurui's DMZ Dungeons (minus the block
 * disguise). Two kinds of options:
 * <ul>
 *   <li><b>Universal</b> - name + Max Health / Damage / Speed applied via vanilla attributes to any spawned
 *       {@link LivingEntity}, plus Defense written as the raw {@code dmz_npc_defense} curve key (never vanilla
 *       ARMOR, which would double-mitigate on sdu's resistance curve).</li>
 *   <li><b>NPC options</b> - Ki Attack, Ki Power, Behaviour, Scale, Model - written as the NBT keys that
 *       {@code sdu}'s {@code sdu:dmz_fighter} reads on load ({@code KiAttack}, {@code KiPower}, {@code Behavior},
 *       {@code ModelScale}, {@code ModelId}, {@code Defense}). sdu NPCs pick them up; other entities ignore
 *       the extra tags. Scale is the exception: it is ALSO written as DMZ's own {@code EntityScale} key, so it
 *       moves real {@code dragonminez:} NPCs too (see {@link #writeDmzScale}).</li>
 * </ul>
 */
public class NpcSpawnConfig
{
    /** Any registered entity id. Ki/behaviour/model options apply to {@code sdu:dmz_fighter}. */
    public String entityTypeId = "dmz_ragnarok:dmz_fighter";
    public String displayName = "";

    // Universal (vanilla attributes)
    public float maxHealth = 20.0f;
    public float attackDamage = 3.0f;
    public float moveSpeed = 0.25f;
    // DMZ getDefense() scale, NOT armor points. written to persistent-data dmz_npc_defense; the sdu NPC-defense
    // system applies the resistance curve, NOT vanilla Attributes.ARMOR (that would double-mitigate on the curve
    // and sdu fighters carry no ARMOR attribute anyway). 0/absent = no mitigation. old armor-style values barely
    // mitigate on this curve, so admins re-tune. Matches the advanced-spawner SpawnerConfig defense scale.
    public float defense = 0.0f;

    // NPC options (written as sdu NBT keys; ignored by other entities)
    public int kiAttack = 0;     // legacy single ki attack; kept only for migration of old saved regions
    public int moveset = 0;      // DmzMoveset ordinal (order must match sdu's enum)
    public int aiTier = 0;       // NpcAiTier ordinal: 0 Simple, 1 Tactical, 2 Advanced
    public boolean kiEnabled = false; // allow ki attacks from the moveset
    public float kiPower = 0.0f; // drives ki attack damage on sdu NPCs
    public int behavior = 0;     // NpcBehavior ordinal: 0 Passive ... 6 DMZ Fighter
    public float scale = 1.0f;
    public String modelId = "";  // sdu custom model id; blank -> sdu:default

    /**
     * Which ragnarok NPC character a spawned {@code dmz_ragnarok:rgnpc} wears. Blank leaves it alone.
     *
     * <p>A different system from {@link #modelId} above, which is a CustomNPCs model key written into the spawn
     * NBT for sdu's fighter to read. The ragnarok NPCs all share ONE entity type and carry their character on
     * the entity instance, so the entity dropdown can only ever reach whichever one is the default; this picks.
     */
    public String rgModelId = "";
    /**
     * Aggro/detection range in blocks: the distance at which this NPC can acquire a target (players).
     * Applied as the vanilla {@link Attributes#FOLLOW_RANGE} attribute on spawn and written into sdu NPC
     * NBT. Default 4 (short-range) rather than the vanilla hostile default of 16. Zero saved values from
     * pre-field regions migrate to this default in {@link #sanitize()}.
     */
    public double aggroRange = DEFAULT_AGGRO_RANGE;

    /** Default aggro/detection range in blocks for a region-spawned NPC. */
    public static final double DEFAULT_AGGRO_RANGE = 4.0;

    // Spawner control
    public int maxSpawns = 6;       // max alive at once inside the region PER nearby player (mobs only spawn near players)
    public int cooldownTicks = 200; // ticks between spawn waves (20 ticks = 1 second)

    // Kill rewards. TP and balance are ranges: a random amount in [min, max] is granted per kill. TP goes
    // through DragonMineZ (DmzBridge); balance through Shurui's Utilities economy. TP degrades to a no-op
    // when DMZ is absent.
    public int tpMin = 0;
    public int tpMax = 0;
    public int balMin = 0;
    public int balMax = 0;
    /**
     * Commands run by the server console when a player kills this NPC (semicolon-separated;
     * {@code %player%} = the killer's name). Blank = none.
     */
    public String killCommands = "";

    /** Percentage-based custom drops, each rolled independently on the death of a mob from this region. */
    public List<CustomDrop> drops = new ArrayList<>();
    /**
     * Whether the entity's own (vanilla/mod loot table) drops still drop on death. Default OFF: only the
     * configured custom drops appear.
     */
    public boolean vanillaDrops = false;

    public NpcSpawnConfig() {}

    /** Full deep copy, used by the editor to copy a region's NPC list and paste it into another region. */
    public NpcSpawnConfig copy()
    {
        NpcSpawnConfig c = new NpcSpawnConfig();
        c.entityTypeId = entityTypeId;
        c.displayName = displayName;
        c.maxHealth = maxHealth;
        c.attackDamage = attackDamage;
        c.moveSpeed = moveSpeed;
        c.defense = defense;
        c.kiAttack = kiAttack;
        c.moveset = moveset;
        c.aiTier = aiTier;
        c.kiEnabled = kiEnabled;
        c.kiPower = kiPower;
        c.behavior = behavior;
        c.scale = scale;
        c.modelId = modelId;
        c.rgModelId = rgModelId;
        c.aggroRange = aggroRange;
        c.maxSpawns = maxSpawns;
        c.cooldownTicks = cooldownTicks;
        c.tpMin = tpMin;
        c.tpMax = tpMax;
        c.balMin = balMin;
        c.balMax = balMax;
        c.killCommands = killCommands;
        c.vanillaDrops = vanillaDrops;
        c.drops = new ArrayList<>();
        if (drops != null)
            for (CustomDrop d : drops)
                if (d != null)
                    c.drops.add(d.copy());
        return c;
    }

    public EntityType<?> entityType()
    {
        ResourceLocation id = ResourceLocation.tryParse(entityTypeId);
        return id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
    }

    /** Universal attribute application (Max Health / Damage / Speed + name) plus the DMZ NPC-defense curve key. */
    public void applyTo(LivingEntity entity)
    {
        setAttr(entity, Attributes.MAX_HEALTH, maxHealth);
        entity.setHealth(entity.getMaxHealth());
        setAttr(entity, Attributes.MOVEMENT_SPEED, moveSpeed);
        setAttr(entity, Attributes.ATTACK_DAMAGE, attackDamage);
        applyDefenseCurve(entity);
        // FOLLOW_RANGE is the distance at which target-acquisition goals scan for players, i.e. the aggro
        // range. Applied to every spawned entity class; default 4 keeps region NPCs short-sighted.
        setAttr(entity, Attributes.FOLLOW_RANGE, Math.max(0.0, aggroRange));
        if (displayName != null && !displayName.isBlank())
        {
            entity.setCustomName(Component.literal(displayName));
            entity.setCustomNameVisible(true);
        }
        applyFighterMobility(entity);
    }

    /**
     * Stop an ordinary region mob behaving like a saga FIGHTER: no flight, no teleporting, no dashing.
     *
     * <h3>Why it was happening</h3>
     * DragonMineZ's own NPCs are saga entities, and a saga entity comes with the whole fighter kit: it flies, it
     * zanzokens behind you, it wild-senses out of danger and it dashes. That is right for a saga boss and absurd for
     * the population of a region, which is why dinosaurs were teleporting and Red Ribbon soldiers were taking off.
     * Nothing here was asking for those behaviours; they are simply what the base class does unless told otherwise.
     *
     * <h3>Why the AI tier decides it</h3>
     * The editor already has the knob: Simple / Tactical / Advanced. DMZ's own brain reads the same field (a dash
     * needs better than Simple, the teleport tricks need Advanced), so the tier is the honest place for this rather
     * than a new toggle beside it. At SIMPLE, which is the default and what every ambient mob is, the fighter kit is
     * switched off explicitly. Raise a spawn to Tactical or Advanced and it fights like a saga NPC again, which is
     * what choosing those means.
     *
     * <p>The tier is also pushed onto the LIVE entity, not just written into NBT. The NBT key is read by our own
     * fighters; a {@code dragonminez:} entity never sees it, and those are exactly the ones doing the teleporting.
     */
    private void applyFighterMobility(LivingEntity entity)
    {
        if (!(entity instanceof DBSagasEntity saga))
            return;
        // Our ordinal is 0-based (0 Simple, 1 Tactical, 2 Advanced); DMZ's id is 1-based.
        saga.setAiTierById(Math.max(1, Math.min(3, aiTier + 1)));
        if (aiTier > 0)
            return;
        saga.setCanFly(false);
        saga.setFlying(false);
        // Each of these is "may it, and on what cooldown". False is what disables the ability; the cooldown is
        // then irrelevant, and is left at DMZ's own default rather than invented here.
        saga.setZanzoken(false, 0);
        saga.setWildSense(false, 0);
        saga.setEvade(false, 0);
    }

    /**
     * Write the sdu-NPC NBT keys into {@code tag} so a spawned {@code sdu:dmz_fighter} picks up its ki attack, ki
     * power, behaviour, scale, model and custom defense on load. Harmless for non-sdu entities (unknown keys).
     */
    public void writeNpcNbt(CompoundTag tag)
    {
        int mv = moveset;
        boolean ki = kiEnabled;
        if (mv == 0 && !ki && kiAttack > 0)
        {
            mv = 2;        // FRIEZA_SOLDIER: a basic ranged moveset for pre-rework saved regions
            ki = true;
        }
        tag.putInt("Moveset", mv);
        tag.putInt("AiTier", aiTier);
        tag.putBoolean("KiEnabled", ki);
        tag.putFloat("KiPower", kiPower);
        tag.putInt("Behavior", behavior);
        tag.putFloat("ModelScale", scale);
        writeDmzScale(tag, scale);
        tag.putFloat("Defense", defense);
        tag.putDouble("AggroRange", Math.max(0.0, aggroRange));
        tag.putString("ModelId", modelId == null || modelId.isBlank() ? "dmz_ragnarok:default" : modelId);
        tag.putInt("Abilities", 0);
        tag.putString("IdleAnim", "");
    }

    /**
     * Scale a DragonMineZ NPC, not just one of ours.
     *
     * <p>{@code ModelScale} above is sdu's own key, read by {@code SduDmzFighter.applySuSpawnNbt}, so the editor's
     * scale field moved our fighters and did nothing at all to a {@code dragonminez:} entity. DMZ has its own key
     * for the same thing: {@code DBSagasEntity.readAdditionalSaveData} reads a float {@code EntityScale} and feeds
     * it to {@code setScaleVal}, and {@code MastersEntity} reads the identical key, so between them every DMZ NPC
     * is covered. Both classes then override {@code LivingEntity.getScale()} to return it, which is what makes the
     * value drive the HITBOX and not just the model.
     *
     * <p>Written alongside {@code ModelScale} rather than instead of it. Our fighters extend {@code DBSagasEntity},
     * so they see both, and both end at the same {@code setScaleVal} call with the same number. Anything that is
     * neither ignores an NBT key it does not read, exactly as it already ignores ours.
     *
     * <p>1.0 MEANS "LEAVE IT ALONE", and it has to. DMZ entities set their own signature size in their constructor
     * (Hirudegarn is 3.0, Frieza's forms each differ), and the constructor runs before
     * {@code readAdditionalSaveData}, so writing this key unconditionally would shrink every one of them to 1.0 the
     * moment a region was saved with the field untouched. Every existing region would have quietly lost its giants.
     * The cost is that a natively oversized NPC cannot be pinned to exactly 1.0; anything either side of it works.
     *
     * <p>Kept in step with the same method on the dungeon side, {@code SpawnerConfig.writeDmzScale}.
     */
    private static void writeDmzScale(CompoundTag tag, float scale)
    {
        if (scale > 0.0f && scale != 1.0f)
            tag.putFloat("EntityScale", scale);
    }

    private static void setAttr(LivingEntity entity, Attribute attribute, double value)
    {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null)
            inst.setBaseValue(value);
    }

    /**
     * Write the DMZ NPC-defense key onto the live entity (raw NBT double, no sdu classes, so it works with sdu
     * absent, just sits unread). Single mitigation stage: vanilla {@code Attributes.ARMOR} is deliberately NOT set,
     * as it would double-mitigate on top of sdu's resistance curve (and sdu fighters have no ARMOR attribute).
     * Mirrors the advanced-spawner {@code SpawnerConfig.applyDefenseCurve}.
     */
    private void applyDefenseCurve(LivingEntity entity)
    {
        if (defense > 0f)
            entity.getPersistentData().putDouble("dmz_npc_defense", defense);
        else
            entity.getPersistentData().remove("dmz_npc_defense");
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(entityTypeId == null ? "" : entityTypeId);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeFloat(maxHealth);
        buf.writeFloat(attackDamage);
        buf.writeFloat(moveSpeed);
        buf.writeFloat(defense);
        buf.writeInt(kiAttack);
        buf.writeInt(moveset);
        buf.writeInt(aiTier);
        buf.writeBoolean(kiEnabled);
        buf.writeFloat(kiPower);
        buf.writeInt(behavior);
        buf.writeFloat(scale);
        buf.writeUtf(modelId == null ? "" : modelId);
        buf.writeInt(maxSpawns);
        buf.writeInt(cooldownTicks);
        buf.writeInt(tpMin);
        buf.writeInt(tpMax);
        buf.writeInt(balMin);
        buf.writeInt(balMax);
        buf.writeVarInt(drops.size());
        for (CustomDrop d : drops)
            d.encode(buf);
        buf.writeUtf(killCommands == null ? "" : killCommands);
        buf.writeBoolean(vanillaDrops);
        buf.writeDouble(aggroRange);
        // APPENDED: this format is positional, so a field slotted in beside its relatives would shift every
        // field after it and the far side would read garbage.
        buf.writeUtf(rgModelId == null ? "" : rgModelId);
    }

    public static NpcSpawnConfig decode(FriendlyByteBuf buf)
    {
        NpcSpawnConfig c = new NpcSpawnConfig();
        c.entityTypeId = buf.readUtf();
        c.displayName = buf.readUtf();
        c.maxHealth = buf.readFloat();
        c.attackDamage = buf.readFloat();
        c.moveSpeed = buf.readFloat();
        c.defense = buf.readFloat();
        c.kiAttack = buf.readInt();
        c.moveset = buf.readInt();
        c.aiTier = buf.readInt();
        c.kiEnabled = buf.readBoolean();
        c.kiPower = buf.readFloat();
        c.behavior = buf.readInt();
        c.scale = buf.readFloat();
        c.modelId = buf.readUtf();
        c.maxSpawns = buf.readInt();
        c.cooldownTicks = buf.readInt();
        c.tpMin = buf.readInt();
        c.tpMax = buf.readInt();
        c.balMin = buf.readInt();
        c.balMax = buf.readInt();
        int dropCount = buf.readVarInt();
        c.drops = new ArrayList<>();
        for (int i = 0; i < dropCount; i++)
            c.drops.add(CustomDrop.decode(buf));
        c.killCommands = buf.readUtf();
        c.vanillaDrops = buf.readBoolean();
        c.aggroRange = buf.readDouble();
        c.rgModelId = buf.readUtf();
        return c;
    }

    /** Defensive normalisation after a Gson load (null lists/fields become sane defaults). */
    public void sanitize()
    {
        if (entityTypeId == null || entityTypeId.isBlank())
            entityTypeId = "dmz_ragnarok:dmz_fighter";
        // Normalize a pre-merge id (sdu:...) to dmz_ragnarok so regions saved before the merge keep resolving and
        // re-save in the new form; other mods' ids (dragonminez:, minecraft:) pass through untouched.
        entityTypeId = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(entityTypeId);
        // Regions saved before the sdu entity rename still hold the retired npc id (dmz_ragnarok:npc once normalized).
        if ((net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":npc").equals(entityTypeId))
            entityTypeId = net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":dmz_fighter";
        if (displayName == null)
            displayName = "";
        if (modelId == null)
            modelId = "";
        if (rgModelId == null)
            rgModelId = "";
        if (killCommands == null)
            killCommands = "";
        if (drops == null)
            drops = new ArrayList<>();
        if (maxSpawns < 1)
            maxSpawns = 1;
        if (cooldownTicks < 1)
            cooldownTicks = 1;
        // Back-compat: regions saved before the aggroRange field have no JSON entry, so Gson leaves it at the
        // Java double default of 0. A non-positive value is meaningless for a detection range, so treat it as
        // "unset" and fall back to the 4-block default. Existing saves therefore load with a 4-block aggro range.
        if (aggroRange <= 0.0)
            aggroRange = DEFAULT_AGGRO_RANGE;
    }
}
