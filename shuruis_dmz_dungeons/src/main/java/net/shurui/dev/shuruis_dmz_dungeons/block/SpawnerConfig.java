package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_dmz_dungeons.util.ColorCodes;

import java.util.ArrayList;
import java.util.List;

// Full editable state of one advanced spawn block. No sdu types (primitives + vanilla entity ids), so the
// block/BE work with sdu absent.
//
//   universal    - name + max health/damage/speed via vanilla attributes on any LivingEntity. defense is the
//                  odd one out: it drives the DMZ NPC-defense curve via persistent-data key dmz_npc_defense
//                  (DMZ getDefense() scale), NOT Attributes.ARMOR, so sdu fighters still get mitigation and
//                  nothing double-mitigates. unread if sdu absent.
//   NPC options  - ki/behavior/scale/model written as the NBT keys sdu's SduDmzFighter reads on load; other
//                  entities ignore the extra tags.
public class SpawnerConfig {

    // any registered entity id. ki/behaviour options only mean anything to sdu:dmz_fighter.
    public String entityTypeId = "minecraft:zombie";
    public String displayName = "";

    // Universal (vanilla attributes)
    public float maxHealth = 20.0f;
    public float attackDamage = 3.0f;
    public float moveSpeed = 0.25f;
    // DMZ getDefense() scale, NOT armor points. written to persistent-data dmz_npc_defense; the sdu
    // NPC-defense system applies the resistance curve. 0/absent = no mitigation. old armor-style values
    // barely mitigate on this curve, so admins re-tune.
    public float defense = 0.0f;

    // NPC options (written as sdu DmzNpcEntity NBT keys; ignored by other entities). Moveset/aiTier ordinals
    // MUST match sdu's DmzMoveset / NpcAiTier enum order.
    public int moveset = 0;      // DmzMoveset ordinal (0 = None/melee, then DMZ fighters)
    public int aiTier = 0;       // NpcAiTier ordinal: 0 Simple, 1 Tactical, 2 Advanced
    public boolean kiEnabled = false; // allow ki attacks from the moveset
    public float kiPower = 0.0f; // legacy single ki-blast damage (back-compat seed for kiDmgMin/Max)
    // per-spawn ki-blast damage range: random value in [kiDmgMin, kiDmgMax] each spawn
    public int kiDmgMin = 0;
    public int kiDmgMax = 0;
    // explicit ki loadout (KiMoveEntry tokens). non-empty -> per-move cooldowns rolled + SduKiMovesCsv written;
    // empty -> fall back to the Moveset/KiEnabled path.
    public final List<String> kiMoves = new ArrayList<>();
    public int behavior = 0;     // NpcBehavior ordinal: 0 Passive … 6 DMZ Fighter
    public float scale = 1.0f;
    public String modelId = "";  // sdu custom model id; blank -> sdu:default

    /**
     * Which ragnarok NPC character a spawned {@code dmz_ragnarok:rgnpc} wears. Blank leaves it alone.
     *
     * <p>NOT {@link #modelId}, which is a CustomNPCs model key. All 386 ragnarok NPCs share ONE entity type and
     * carry their character on the instance, so the entity dropdown only reaches the default one; this picks which.
     */
    public String rgModelId = "";
    // saved Custom NPC clone ref "tab$name". when set, spawn sdu:dmz_fighter configured from it.
    public String savedNpcRef = "";

    // Spawner control
    public int maxSpawns = 6;       // max alive at once within range ("spawn amount"); a wave fills up to this
    public int cooldownTicks = 200; // ticks between spawn waves (20 ticks = 1 second)
    public int wanderDistance = 16; // blocks a spawned mob may roam from the spawner (0 = unlimited)

    // On/off + spawn conditions. The spawner only runs while enabled AND every configured condition holds.
    public boolean enabled = true;
    public int timeCondition = 0;    // 0 Any, 1 Day, 2 Night, 3 Morning (sunrise), 4 Dusk (sunset)
    public int weatherCondition = 0; // 0 Any, 1 Clear, 2 Rain, 3 Thunder
    public int redstoneMode = 0;     // 0 Ignore, 1 Requires signal, 2 Requires NO signal

    // Boss: optional extra spawn rolled once per wave (one alive at a time). PUBLIC: the boss roll is gated on the
    // named public feature PublicContent.FEATURE_SPAWNER_BOSS at the roll site in AdvancedSpawnerBlockEntity, not
    // on a key here, so a keyless server fires bosses too. inherits Moveset / AI Tier / Behavior / Speed / Defense
    // from the main config and overrides the rest below; a saved Custom NPC ref configures it fully like savedNpcRef.
    public boolean bossEnabled = false;
    public String bossEntityTypeId = "dmz_ragnarok:dmz_fighter";

    /** The boss's ragnarok NPC character, when its entity type is {@code dmz_ragnarok:rgnpc}. See rgModelId. */
    public String bossRgModelId = "";
    public String bossName = "";
    public float bossChance = 10.0f;   // % chance per spawn wave
    public float bossHealth = 100.0f;
    public float bossDamage = 10.0f;
    public float bossKiPower = 0.0f; // legacy single boss ki-blast damage (back-compat seed for bossKiDmgMin/Max)
    public int bossKiDmgMin = 0;
    public int bossKiDmgMax = 0;
    public final List<String> bossKiMoves = new ArrayList<>(); // boss version of kiMoves
    public float bossScale = 1.5f;
    public String bossSavedNpcRef = "";

    // Transformation chains (sdu). raw NBT (a TransformChain's {forms:[...], index:0}, or empty) so this class
    // carries no sdu types. stamped onto the spawned entity's persistentData under "sdu_tf" where sdu's transform
    // engine reads it. "use default" flags map to sdu's "dmz_quest_no_transform" boolean: false = suppress DMZ's
    // built-in transformations (a custom chain suppresses them too).
    public CompoundTag mainTransform = new CompoundTag();
    public boolean mainUseDefaultTransform = true;
    public CompoundTag bossTransform = new CompoundTag();
    public boolean bossUseDefaultTransform = true;

    // Kill rewards, both [min, max] ranges per kill. TP goes through DMZ (DmzTpCompat), balance through Shurui's
    // Utilities (UtilitiesEconomyCompat); both no-op when their mod is absent.
    public int tpMin = 0;
    public int tpMax = 0;
    public int balMin = 0;
    public int balMax = 0;

    // Percentage-based custom drops: each is rolled independently on the death of a mob from this spawner.
    public final List<CustomDrop> drops = new ArrayList<>();
    // console commands on kill (semicolon-separated; %player% = killer's name)
    public String killCommands = "";
    // keep the entity's own loot-table drops? default OFF: only the configured custom drops appear
    public boolean vanillaDrops = false;

    public String disguise = ""; // block id; blank = vanilla mob spawner render

    public SpawnerConfig() {
    }

    public EntityType<?> entityType() {
        ResourceLocation id = ResourceLocation.tryParse(entityTypeId);
        return id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
    }

    public EntityType<?> bossEntityType() {
        ResourceLocation id = ResourceLocation.tryParse(bossEntityTypeId);
        return id == null ? null : ForgeRegistries.ENTITY_TYPES.getValue(id);
    }

    // universal attributes (health/damage/speed + name) plus the DMZ NPC-defense curve key
    public void applyTo(LivingEntity entity) {
        setAttr(entity, Attributes.MAX_HEALTH, maxHealth);
        entity.setHealth(entity.getMaxHealth());
        // sdu:dmz_fighter recomputes its health from its DMZ stat block after spawn (clobbers the vanilla attribute
        // above with a ~120 default), so also stamp the override key it respects. Plain NBT; non-fighter entities
        // never read it and keep the vanilla attribute.
        writeHealthOverride(entity, maxHealth);
        setAttr(entity, Attributes.MOVEMENT_SPEED, moveSpeed);
        setAttr(entity, Attributes.ATTACK_DAMAGE, attackDamage);
        applyDefenseCurve(entity);
        if (displayName != null && !displayName.isBlank()) {
            entity.setCustomName(Component.literal(ColorCodes.translate(displayName)));
            entity.setCustomNameVisible(true);
        }
        applyFighterMobility(entity);
        applyKiSkills(entity, kiMoves);
    }

    /**
     * Hand a DMZ saga NPC the configured ki loadout through DMZ's own API. {@link #writeNpcNbt} publishes it as
     * {@code SduKiMovesCsv}, SDU's key, which a {@code dragonminez:} entity never reads, so a DMZ NPC kept its
     * native pool and never threw its configured blasts. Same trap as ModelScale, same fix: reach the live entity
     * (see {@link #writeDmzScale}). Applied unconditionally to REPLACE the native pool; both this and the NBT path
     * clear before filling, so nothing doubles. Cooldowns rolled per spawn from {@code cdMin..cdMax}.
     */
    /** Chance a spawned NPC past melee range fires ki instead of closing. Kept in step with RaidInstance.RANGED_BIAS. */
    private static final double RANGED_BIAS = 0.45;

    private void applyKiSkills(LivingEntity entity, List<String> moves) {
        if (!(entity instanceof com.dragonminez.common.init.entities.sagas.DBSagasEntity saga)) {
            return;
        }
        if (moves == null || moves.isEmpty()) {
            return;
        }
        String csv = rollKiMoves(entity.getRandom(), moves);
        if (csv.isEmpty()) {
            return;
        }
        // Filling the pool is not enough: DMZ's brain past melee range mostly chooses to close, so a full-loadout
        // NPC still only punched. MixinDmzSagaRangedBias reads this key and weights that decision. Raw string, no
        // mixin class loaded here.
        entity.getPersistentData().putDouble("dmz_ragnarok_ranged_bias", RANGED_BIAS);
        saga.getSkillPool().clear();
        for (String token : csv.split(",")) {
            String tk = token.trim();
            if (tk.isEmpty()) {
                continue;
            }
            // rolled token: TYPE:cooldown:size:colorMain
            String[] p = tk.split(":");
            if (p.length < 1 || p[0].isBlank()) {
                continue;
            }
            int cd = 60;
            float size = 1.0f;
            int colorMain = -1;
            if (p.length >= 2) {
                try { cd = Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { }
            }
            if (p.length >= 3) {
                try { size = Float.parseFloat(p[2].trim()); } catch (NumberFormatException ignored) { }
            }
            if (p.length >= 4) {
                try { colorMain = (int) Long.parseLong(p[3].trim().replace("#", ""), 16); }
                catch (NumberFormatException ignored) { }
            }
            try {
                var type = com.dragonminez.common.init.entities.sagas.DBSagasEntity.KiSkillType
                        .valueOf(p[0].trim().toUpperCase(java.util.Locale.ROOT));
                if (colorMain >= 0) {
                    // border/outline derived as SduDmzFighter does, so the same colour renders the same blast.
                    int border = darkenRgb(colorMain, 0.85f);
                    int outline = darkenRgb(border, 0.6f);
                    saga.addKiSkill(type, Math.max(1, cd), size,
                            colorMain & 0xFFFFFF, border & 0xFFFFFF, outline & 0xFFFFFF);
                } else {
                    saga.addKiSkill(type, Math.max(1, cd), size);
                }
            } catch (IllegalArgumentException ignored) {
                // unknown ki type: skip, keep the rest
            }
        }
    }

    /** Multiply each RGB channel by factor, clamped. Mirrors SduDmzFighter.darken. */
    private static int darkenRgb(int rgb, float factor) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) * factor));
        int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 0xFF) * factor));
        int b = (int) Math.max(0, Math.min(255, (rgb & 0xFF) * factor));
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Stop an ordinary spawned mob behaving like a saga FIGHTER (flight, zanzoken, wild-sense, dash). DMZ's own
     * NPCs are saga entities and do all of that by default. The AI TIER gates it, which DMZ's brain already reads:
     * Simple (default) off, Tactical/Advanced on. Pushed onto the LIVE entity too, since a {@code dragonminez:}
     * entity never reads the NBT key. Kept in step with NpcSpawnConfig.applyFighterMobility on the region side.
     */
    private void applyFighterMobility(LivingEntity entity) {
        if (!(entity instanceof com.dragonminez.common.init.entities.sagas.DBSagasEntity saga)) {
            return;
        }
        // Our ordinal is 0-based (0 Simple, 1 Tactical, 2 Advanced); DMZ's id is 1-based.
        saga.setAiTierById(Math.max(1, Math.min(3, aiTier + 1)));
        if (aiTier > 0) {
            return;
        }
        saga.setCanFly(false);
        saga.setFlying(false);
        saga.setZanzoken(false, 0);
        saga.setWildSense(false, 0);
        saga.setEvade(false, 0);
    }

    // Scale a DragonMineZ NPC, not just one of ours. ModelScale is sdu's key, so the slider did nothing to a
    // dragonminez: entity. DMZ reads a float "EntityScale" (DBSagasEntity + MastersEntity) into setScaleVal and
    // overrides getScale(), so it drives the HITBOX, not just the model. Written alongside ModelScale, not instead;
    // our fighters extend DBSagasEntity and see both, ending at the same setScaleVal.
    //
    // 1.0 MEANS "LEAVE IT ALONE". DMZ entities set their signature size in their constructor (Hirudegarn 3.0), which
    // runs before readAdditionalSaveData, so writing this unconditionally would shrink every one to 1.0 the moment a
    // spawner was placed. The trade: a natively oversized NPC cannot be pinned to exactly 1.0.
    private static void writeDmzScale(CompoundTag tag, float scale) {
        if (scale > 0.0f && scale != 1.0f) {
            tag.putFloat("EntityScale", scale);
        }
    }

    // write the sdu-NPC NBT keys so a spawned sdu:dmz_fighter picks up ki/behaviour/scale/model/defense on
    // load (harmless for non-sdu entities). kiMoves non-empty -> per-move cooldowns rolled into SduKiMovesCsv;
    // ki damage rolled in [kiDmgMin, kiDmgMax] into KiPower. random drives both so each mob varies.
    public void writeNpcNbt(CompoundTag tag, RandomSource random) {
        tag.putInt("Moveset", moveset);
        tag.putInt("AiTier", aiTier);
        tag.putBoolean("KiEnabled", kiEnabled || !kiMoves.isEmpty());
        tag.putFloat("KiPower", rollKiDamage(random, kiDmgMin, kiDmgMax, kiPower));
        String csv = rollKiMoves(random, kiMoves);
        if (!csv.isEmpty()) {
            tag.putString("SduKiMovesCsv", csv);
            writeCnpcKiMoves(tag, csv);
        }
        tag.putInt("Behavior", behavior);
        tag.putFloat("ModelScale", scale);
        writeDmzScale(tag, scale);
        tag.putFloat("Defense", defense);
        tag.putString("ModelId", modelId == null || modelId.isBlank() ? "dmz_ragnarok:default" : modelId);
        tag.putInt("Abilities", 0);
        tag.putString("IdleAnim", "");
    }

    // A Custom NPC does not read SduKiMovesCsv. SDU keeps a CNPC's ki loadout as "sdu_dmz_ki_moves" on the DISPLAY
    // data (DataDisplayHairMixin), inside the NpcModelData compound, so writing only the root key left CNPCs with a
    // list nothing read and they never threw a blast. Writing both covers either NPC kind. NpcModelData is merged,
    // not replaced, so existing display data survives.
    private static void writeCnpcKiMoves(CompoundTag tag, String csv) {
        CompoundTag display = tag.contains("NpcModelData") ? tag.getCompound("NpcModelData") : new CompoundTag();
        display.putString("sdu_dmz_ki_moves", csv);
        tag.put("NpcModelData", display);
    }

    // random ki damage in [min, max]; empty range -> legacy single value
    private static float rollKiDamage(RandomSource random, int min, int max, float legacy) {
        int lo = Math.max(0, Math.min(min, max));
        int hi = Math.max(0, Math.max(min, max));
        if (hi <= 0) {
            return legacy; // no range -> legacy single value (0 leaves DMZ default)
        }
        return lo >= hi ? lo : lo + random.nextInt(hi - lo + 1);
    }

    // roll every configured move into the comma-joined CSV for SduKiMovesCsv
    private static String rollKiMoves(RandomSource random, List<String> moves) {
        if (moves == null || moves.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : moves) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(KiMoveEntry.fromToken(token).roll(random));
        }
        return sb.toString();
    }

    // boss attributes: own health/damage/name, inherits speed/defense from the config
    public void applyBossTo(LivingEntity entity) {
        setAttr(entity, Attributes.MAX_HEALTH, bossHealth);
        entity.setHealth(entity.getMaxHealth());
        // same override stamp as applyTo, using the boss health (see writeHealthOverride note there).
        writeHealthOverride(entity, bossHealth);
        setAttr(entity, Attributes.MOVEMENT_SPEED, moveSpeed);
        setAttr(entity, Attributes.ATTACK_DAMAGE, bossDamage);
        applyDefenseCurve(entity);
        if (bossName != null && !bossName.isBlank()) {
            entity.setCustomName(Component.literal(ColorCodes.translate(bossName)));
            entity.setCustomNameVisible(true);
        }
        // Boss was getting neither, so its AI tier and ki pool stayed DMZ's own and the configured loadout never
        // landed: why a dungeon boss fought like an ordinary mob. Boss ki comes from bossKiMoves.
        applyFighterMobility(entity);
        applyKiSkills(entity, bossKiMoves);
    }

    // boss variant of writeNpcNbt: boss ki (rolled damage + loadout)/scale, rest inherited
    public void writeBossNpcNbt(CompoundTag tag, RandomSource random) {
        tag.putInt("Moveset", moveset);
        tag.putInt("AiTier", aiTier);
        float bossDmg = rollKiDamage(random, bossKiDmgMin, bossKiDmgMax, bossKiPower);
        String csv = rollKiMoves(random, bossKiMoves);
        tag.putBoolean("KiEnabled", bossDmg > 0 || !csv.isEmpty());
        tag.putFloat("KiPower", bossDmg);
        if (!csv.isEmpty()) {
            tag.putString("SduKiMovesCsv", csv);
            writeCnpcKiMoves(tag, csv);
        }
        tag.putInt("Behavior", behavior);
        tag.putFloat("ModelScale", bossScale);
        writeDmzScale(tag, bossScale);
        tag.putFloat("Defense", defense);
        tag.putString("ModelId", modelId == null || modelId.isBlank() ? "dmz_ragnarok:default" : modelId);
        tag.putInt("Abilities", 0);
        tag.putString("IdleAnim", "");
    }

    // write the DMZ NPC-defense key onto the live entity (raw NBT double, no sdu classes, so it works with sdu
    // absent, just sits unread). single mitigation stage: vanilla Attributes.ARMOR is deliberately NOT set, it
    // would double-mitigate on the curve.
    private void applyDefenseCurve(LivingEntity living) {
        if (defense > 0f) {
            living.getPersistentData().putDouble("dmz_npc_defense", defense);
        } else {
            living.getPersistentData().remove("dmz_npc_defense");
        }
    }

    // max-health override that sdu:dmz_fighter reads (SduDmzFighter.HEALTH_OVERRIDE_KEY). plain persistent-data
    // double so no compile dependency on sdu; entities that don't read it (vanilla mobs, other mods) ignore it
    // and keep their vanilla MAX_HEALTH attribute. value <= 0 clears the key.
    private static void writeHealthOverride(LivingEntity living, double value) {
        if (value > 0) {
            living.getPersistentData().putDouble("sdu_health_override", value);
        } else {
            living.getPersistentData().remove("sdu_health_override");
        }
    }

    private static void setAttr(LivingEntity entity, Attribute attribute, double value) {
        AttributeInstance inst = entity.getAttribute(attribute);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(entityTypeId == null ? "" : entityTypeId);
        buf.writeUtf(displayName == null ? "" : displayName);
        buf.writeFloat(maxHealth);
        buf.writeFloat(attackDamage);
        buf.writeFloat(moveSpeed);
        buf.writeFloat(defense);
        buf.writeInt(moveset);
        buf.writeInt(aiTier);
        buf.writeBoolean(kiEnabled);
        buf.writeFloat(kiPower);
        buf.writeInt(kiDmgMin);
        buf.writeInt(kiDmgMax);
        encodeStrings(buf, kiMoves);
        buf.writeInt(behavior);
        buf.writeFloat(scale);
        buf.writeUtf(modelId == null ? "" : modelId);
        buf.writeInt(maxSpawns);
        buf.writeInt(cooldownTicks);
        buf.writeInt(wanderDistance);
        buf.writeInt(tpMin);
        buf.writeInt(tpMax);
        buf.writeInt(balMin);
        buf.writeInt(balMax);
        buf.writeInt(drops.size());
        for (CustomDrop d : drops) {
            d.encode(buf);
        }
        buf.writeUtf(disguise == null ? "" : disguise);
        buf.writeUtf(savedNpcRef == null ? "" : savedNpcRef);
        buf.writeBoolean(enabled);
        buf.writeInt(timeCondition);
        buf.writeInt(weatherCondition);
        buf.writeInt(redstoneMode);
        buf.writeBoolean(bossEnabled);
        buf.writeUtf(bossEntityTypeId == null ? "" : bossEntityTypeId);
        buf.writeUtf(bossName == null ? "" : bossName);
        buf.writeFloat(bossChance);
        buf.writeFloat(bossHealth);
        buf.writeFloat(bossDamage);
        buf.writeFloat(bossKiPower);
        buf.writeInt(bossKiDmgMin);
        buf.writeInt(bossKiDmgMax);
        encodeStrings(buf, bossKiMoves);
        buf.writeFloat(bossScale);
        buf.writeUtf(bossSavedNpcRef == null ? "" : bossSavedNpcRef);
        buf.writeUtf(killCommands == null ? "" : killCommands);
        buf.writeBoolean(vanillaDrops);
        buf.writeNbt(mainTransform == null ? new CompoundTag() : mainTransform);
        buf.writeBoolean(mainUseDefaultTransform);
        buf.writeNbt(bossTransform == null ? new CompoundTag() : bossTransform);
        buf.writeBoolean(bossUseDefaultTransform);
        // APPENDED: this format is positional, so inserting a field mid-stream shifts everything after it.
        buf.writeUtf(rgModelId == null ? "" : rgModelId);
        buf.writeUtf(bossRgModelId == null ? "" : bossRgModelId);
    }

    public static SpawnerConfig decode(FriendlyByteBuf buf) {
        SpawnerConfig c = new SpawnerConfig();
        c.entityTypeId = buf.readUtf();
        c.displayName = buf.readUtf();
        c.maxHealth = buf.readFloat();
        c.attackDamage = buf.readFloat();
        c.moveSpeed = buf.readFloat();
        c.defense = buf.readFloat();
        c.moveset = buf.readInt();
        c.aiTier = buf.readInt();
        c.kiEnabled = buf.readBoolean();
        c.kiPower = buf.readFloat();
        c.kiDmgMin = buf.readInt();
        c.kiDmgMax = buf.readInt();
        decodeStrings(buf, c.kiMoves);
        c.behavior = buf.readInt();
        c.scale = buf.readFloat();
        c.modelId = buf.readUtf();
        c.maxSpawns = buf.readInt();
        c.cooldownTicks = buf.readInt();
        c.wanderDistance = buf.readInt();
        c.tpMin = buf.readInt();
        c.tpMax = buf.readInt();
        c.balMin = buf.readInt();
        c.balMax = buf.readInt();
        int dropCount = buf.readInt();
        for (int i = 0; i < dropCount; i++) {
            c.drops.add(CustomDrop.decode(buf));
        }
        c.disguise = buf.readUtf();
        c.savedNpcRef = buf.readUtf();
        c.enabled = buf.readBoolean();
        c.timeCondition = buf.readInt();
        c.weatherCondition = buf.readInt();
        c.redstoneMode = buf.readInt();
        c.bossEnabled = buf.readBoolean();
        c.bossEntityTypeId = buf.readUtf();
        c.bossName = buf.readUtf();
        c.bossChance = buf.readFloat();
        c.bossHealth = buf.readFloat();
        c.bossDamage = buf.readFloat();
        c.bossKiPower = buf.readFloat();
        c.bossKiDmgMin = buf.readInt();
        c.bossKiDmgMax = buf.readInt();
        decodeStrings(buf, c.bossKiMoves);
        c.bossScale = buf.readFloat();
        c.bossSavedNpcRef = buf.readUtf();
        c.killCommands = buf.readUtf();
        c.vanillaDrops = buf.readBoolean();
        CompoundTag mainTf = buf.readNbt();
        c.mainTransform = mainTf == null ? new CompoundTag() : mainTf;
        c.mainUseDefaultTransform = buf.readBoolean();
        CompoundTag bossTf = buf.readNbt();
        c.bossTransform = bossTf == null ? new CompoundTag() : bossTf;
        c.bossUseDefaultTransform = buf.readBoolean();
        c.rgModelId = buf.readUtf();
        c.bossRgModelId = buf.readUtf();
        return c;
    }

    private static void encodeStrings(FriendlyByteBuf buf, List<String> list) {
        buf.writeInt(list.size());
        for (String s : list) {
            buf.writeUtf(s == null ? "" : s);
        }
    }

    private static void decodeStrings(FriendlyByteBuf buf, List<String> out) {
        out.clear();
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            out.add(buf.readUtf());
        }
    }

    public CompoundTag save(CompoundTag tag) {
        tag.putString("EntityType", entityTypeId == null ? "" : entityTypeId);
        tag.putString("Name", displayName == null ? "" : displayName);
        tag.putFloat("MaxHealth", maxHealth);
        tag.putFloat("Damage", attackDamage);
        tag.putFloat("Speed", moveSpeed);
        tag.putFloat("Defense", defense);
        tag.putInt("Moveset", moveset);
        tag.putInt("AiTier", aiTier);
        tag.putBoolean("KiEnabled", kiEnabled);
        tag.putFloat("KiPower", kiPower);
        tag.putInt("KiDmgMin", kiDmgMin);
        tag.putInt("KiDmgMax", kiDmgMax);
        tag.put("KiMoves", saveStrings(kiMoves));
        tag.putInt("Behavior", behavior);
        tag.putFloat("Scale", scale);
        tag.putString("ModelId", modelId == null ? "" : modelId);
        tag.putString("RgModelId", rgModelId == null ? "" : rgModelId);
        tag.putString("BossRgModelId", bossRgModelId == null ? "" : bossRgModelId);
        tag.putString("SavedNpcRef", savedNpcRef == null ? "" : savedNpcRef);
        tag.putInt("MaxSpawns", maxSpawns);
        tag.putInt("Cooldown", cooldownTicks);
        tag.putInt("WanderDistance", wanderDistance);
        tag.putInt("TpMin", tpMin);
        tag.putInt("TpMax", tpMax);
        tag.putInt("BalMin", balMin);
        tag.putInt("BalMax", balMax);
        ListTag dropList = new ListTag();
        for (CustomDrop d : drops) {
            dropList.add(d.save());
        }
        tag.put("Drops", dropList);
        tag.putString("Disguise", disguise == null ? "" : disguise);
        tag.putBoolean("Enabled", enabled);
        tag.putInt("TimeCondition", timeCondition);
        tag.putInt("WeatherCondition", weatherCondition);
        tag.putInt("RedstoneMode", redstoneMode);
        tag.putBoolean("BossEnabled", bossEnabled);
        tag.putString("BossEntityType", bossEntityTypeId == null ? "" : bossEntityTypeId);
        tag.putString("BossName", bossName == null ? "" : bossName);
        tag.putFloat("BossChance", bossChance);
        tag.putFloat("BossHealth", bossHealth);
        tag.putFloat("BossDamage", bossDamage);
        tag.putFloat("BossKiPower", bossKiPower);
        tag.putInt("BossKiDmgMin", bossKiDmgMin);
        tag.putInt("BossKiDmgMax", bossKiDmgMax);
        tag.put("BossKiMoves", saveStrings(bossKiMoves));
        tag.putFloat("BossScale", bossScale);
        tag.putString("BossSavedNpcRef", bossSavedNpcRef == null ? "" : bossSavedNpcRef);
        tag.putString("KillCommands", killCommands == null ? "" : killCommands);
        tag.putBoolean("VanillaDrops", vanillaDrops);
        tag.put("MainTransform", mainTransform == null ? new CompoundTag() : mainTransform);
        tag.putBoolean("MainUseDefaultTransform", mainUseDefaultTransform);
        tag.put("BossTransform", bossTransform == null ? new CompoundTag() : bossTransform);
        tag.putBoolean("BossUseDefaultTransform", bossUseDefaultTransform);
        return tag;
    }

    // Full deep copy via the NBT round trip, so the mutable kiMoves/bossKiMoves/drops lists and the
    // mainTransform/bossTransform tags are duplicated rather than shared. Used by the floor editor's preset
    // duplicate and clipboard, where a shared reference would let editing the copy mutate the original.
    public SpawnerConfig copy() {
        return load(save(new CompoundTag()));
    }

    public static SpawnerConfig load(CompoundTag tag) {
        SpawnerConfig c = new SpawnerConfig();
        c.entityTypeId = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(
                tag.contains("EntityType") ? tag.getString("EntityType") : "minecraft:zombie");
        // spawners placed before the sdu entity rename still hold the retired npc id (dmz_ragnarok:npc once normalized)
        if ((net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":npc").equals(c.entityTypeId))
            c.entityTypeId = net.shurui.shuruisutilities.ragnarok.LegacyIds.NEW_NAMESPACE + ":dmz_fighter";
        c.displayName = tag.getString("Name");
        c.maxHealth = tag.contains("MaxHealth") ? tag.getFloat("MaxHealth") : 20.0f;
        c.attackDamage = tag.contains("Damage") ? tag.getFloat("Damage") : 3.0f;
        c.moveSpeed = tag.contains("Speed") ? tag.getFloat("Speed") : 0.25f;
        c.defense = tag.getFloat("Defense");
        c.savedNpcRef = tag.getString("SavedNpcRef");
        if (tag.contains("Moveset")) {
            c.moveset = tag.getInt("Moveset");
            c.aiTier = tag.getInt("AiTier");
            c.kiEnabled = tag.getBoolean("KiEnabled");
        } else if (tag.getInt("KiAttack") > 0) {
            c.moveset = 2;      // FRIEZA_SOLDIER: a basic ranged moveset for pre-rework spawners
            c.kiEnabled = true;
        }
        c.kiPower = tag.getFloat("KiPower");
        // ki damage is a range now; pre-range spawners seed both bounds from the legacy single KiPower
        int legacyKiDmg = Math.round(c.kiPower);
        c.kiDmgMin = tag.contains("KiDmgMin") ? tag.getInt("KiDmgMin") : legacyKiDmg;
        c.kiDmgMax = tag.contains("KiDmgMax") ? tag.getInt("KiDmgMax") : legacyKiDmg;
        loadStrings(tag, "KiMoves", c.kiMoves);
        c.behavior = tag.getInt("Behavior");
        c.scale = tag.contains("Scale") ? tag.getFloat("Scale") : 1.0f;
        c.modelId = tag.getString("ModelId");
        c.rgModelId = tag.getString("RgModelId");
        c.bossRgModelId = tag.getString("BossRgModelId");
        c.maxSpawns = tag.contains("MaxSpawns") ? tag.getInt("MaxSpawns") : 6;
        c.cooldownTicks = tag.contains("Cooldown") ? tag.getInt("Cooldown") : 200;
        c.wanderDistance = tag.contains("WanderDistance") ? tag.getInt("WanderDistance") : 16;
        // TP is a range now; fall back to the legacy single "TpPerKill" for older spawners
        int legacyTp = tag.getInt("TpPerKill");
        c.tpMin = tag.contains("TpMin") ? tag.getInt("TpMin") : legacyTp;
        c.tpMax = tag.contains("TpMax") ? tag.getInt("TpMax") : legacyTp;
        c.balMin = tag.getInt("BalMin");
        c.balMax = tag.getInt("BalMax");
        if (tag.contains("Drops")) {
            ListTag dropList = tag.getList("Drops", Tag.TAG_COMPOUND);
            for (int i = 0; i < dropList.size(); i++) {
                c.drops.add(CustomDrop.load(dropList.getCompound(i)));
            }
        }
        c.disguise = tag.getString("Disguise");
        c.enabled = !tag.contains("Enabled") || tag.getBoolean("Enabled"); // pre-update spawners stay on
        c.timeCondition = tag.getInt("TimeCondition");
        c.weatherCondition = tag.getInt("WeatherCondition");
        c.redstoneMode = tag.getInt("RedstoneMode");
        c.bossEnabled = tag.getBoolean("BossEnabled");
        c.bossEntityTypeId = net.shurui.shuruisutilities.ragnarok.LegacyIds.normalize(
                tag.contains("BossEntityType") && !tag.getString("BossEntityType").isBlank()
                        ? tag.getString("BossEntityType") : "dmz_ragnarok:dmz_fighter");
        c.bossName = tag.getString("BossName");
        c.bossChance = tag.contains("BossChance") ? tag.getFloat("BossChance") : 10.0f;
        c.bossHealth = tag.contains("BossHealth") ? tag.getFloat("BossHealth") : 100.0f;
        c.bossDamage = tag.contains("BossDamage") ? tag.getFloat("BossDamage") : 10.0f;
        c.bossKiPower = tag.getFloat("BossKiPower");
        int legacyBossKiDmg = Math.round(c.bossKiPower);
        c.bossKiDmgMin = tag.contains("BossKiDmgMin") ? tag.getInt("BossKiDmgMin") : legacyBossKiDmg;
        c.bossKiDmgMax = tag.contains("BossKiDmgMax") ? tag.getInt("BossKiDmgMax") : legacyBossKiDmg;
        loadStrings(tag, "BossKiMoves", c.bossKiMoves);
        c.bossScale = tag.contains("BossScale") ? tag.getFloat("BossScale") : 1.5f;
        c.bossSavedNpcRef = tag.getString("BossSavedNpcRef");
        c.killCommands = tag.getString("KillCommands");
        c.vanillaDrops = tag.getBoolean("VanillaDrops");
        c.mainTransform = tag.contains("MainTransform") ? tag.getCompound("MainTransform") : new CompoundTag();
        c.mainUseDefaultTransform = !tag.contains("MainUseDefaultTransform") || tag.getBoolean("MainUseDefaultTransform");
        c.bossTransform = tag.contains("BossTransform") ? tag.getCompound("BossTransform") : new CompoundTag();
        c.bossUseDefaultTransform = !tag.contains("BossUseDefaultTransform") || tag.getBoolean("BossUseDefaultTransform");
        return c;
    }

    private static ListTag saveStrings(List<String> list) {
        ListTag out = new ListTag();
        for (String s : list) {
            out.add(StringTag.valueOf(s == null ? "" : s));
        }
        return out;
    }

    private static void loadStrings(CompoundTag tag, String key, List<String> out) {
        out.clear();
        if (!tag.contains(key)) {
            return;
        }
        ListTag list = tag.getList(key, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            String s = list.getString(i);
            if (s != null && !s.isBlank()) {
                out.add(s);
            }
        }
    }
}
