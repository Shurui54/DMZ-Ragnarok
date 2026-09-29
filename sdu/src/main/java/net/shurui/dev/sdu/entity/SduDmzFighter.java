package net.shurui.dev.sdu.entity;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;

// extends DBSagasEntity so it inherits DMZ's real saga AI/ki/combat/animation untouched;
// only the look is ours (custom geckolib model + texture synced via MODEL/TEXTURE).
// stats configured through the inherited DMZ setters, same as the raid boss mod's saga bosses.
public class SduDmzFighter extends DBSagasEntity {

    private static final EntityDataAccessor<String> MODEL =
            SynchedEntityData.defineId(SduDmzFighter.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> TEXTURE =
            SynchedEntityData.defineId(SduDmzFighter.class, EntityDataSerializers.STRING);
    // 0 = texture resloc, 1 = player name, 2 = URL (mirrors Custom NPCs' skinType).
    private static final EntityDataAccessor<Integer> SKIN_TYPE =
            SynchedEntityData.defineId(SduDmzFighter.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> HAIR =
            SynchedEntityData.defineId(SduDmzFighter.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> HAIR_COLOR =
            SynchedEntityData.defineId(SduDmzFighter.class, EntityDataSerializers.STRING);

    // explicit max-health override. when >0 it wins over DMZ's stat-derived health, which otherwise
    // recomputes to a ~120 default and clobbers whatever a spawner set. any mod can spawn a fighter with
    // fixed HP by writing this key (the dungeon Advanced Spawner does). applied AFTER all stat config.
    public static final String HEALTH_OVERRIDE_KEY = "sdu_health_override";

    public static final String DEFAULT_MODEL = "sdu_wide";
    // full geo resloc so the fighter can render ANY model (sdu, dragonminez, ...).
    public static final String DEFAULT_GEO = "dmz_ragnarok:geo/entity/sdu_wide.geo.json";
    public static final String DEFAULT_TEXTURE = "minecraft:textures/entity/player/wide/steve.png";

    private boolean sduQuestConfigured = false;

    // SU NPC-region behaviour ordinal from the "Behavior" spawn NBT key (0 Passive, 1 Wander, 2 Dialogue,
    // 3 Follower, 4 Guard, 5 Hostile, 6 DMZ Fighter). -1 = unset -> stock DMZ fighter. enforced in setTarget
    // so it works even without SU installed (SU adds a second class-agnostic layer via LivingChangeTargetEvent).
    private int suBehavior = -1;

    public SduDmzFighter(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    // self-config on the SERVER before the entity is tracked, so the right MODEL/TEXTURE/SKIN/HAIR rides the
    // initial tracking packet (fixes the one-tick default-Steve/default-geo flash). safe here: only sets
    // synched data + stats and reads NBT, no world membership needed. resloc skins (type 0) are correct from
    // frame one; player-name/URL skins (types 1/2) still resolve async on the client, unavoidable.
    @Override
    public net.minecraft.world.entity.SpawnGroupData finalizeSpawn(
            net.minecraft.world.level.ServerLevelAccessor level,
            net.minecraft.world.DifficultyInstance difficulty,
            net.minecraft.world.entity.MobSpawnType reason,
            net.minecraft.world.entity.SpawnGroupData spawnData,
            CompoundTag dataTag) {
        net.minecraft.world.entity.SpawnGroupData result =
                super.finalizeSpawn(level, difficulty, reason, spawnData, dataTag);
        // NBT already loaded by loadEntityRecursive before finalizeSpawn, so sdu_clone_ref / the quest binding
        // are readable here. run the config now, guard stops tick() redoing it.
        selfConfigureFromRef();
        return result;
    }

    @Override
    public void tick() {
        super.tick();
        // fallback: some paths set the ref AFTER finalizeSpawn (quest binding stamped post-spawn). same guard,
        // so it's a no-op when finalizeSpawn already ran.
        if (!level().isClientSide() && !sduQuestConfigured) {
            selfConfigureFromRef();
        }
        // Lift out of solid blocks if a ki crater regen, knockback or a dash has buried this fighter. Throttled and
        // only acts when actually walled in, so it is a no-op on a fighter standing free. Terrain regen sweeps once
        // when a crater fills; this covers a fighter shoved into terrain with no crater, or into it after that sweep.
        NpcUnstuck.tickSelfHeal(this);
    }

    // one-time clone-ref + quest-transform + health-override config, guarded by sduQuestConfigured.
    private void selfConfigureFromRef() {
        if (level().isClientSide() || sduQuestConfigured) {
            return;
        }
        sduQuestConfigured = true;
        try {
            // ref comes from a quest binding OR a direct NBT key, so any mod (e.g. the dungeon spawner) can
            // spawn a configured fighter just by writing "sdu_clone_ref".
            String ref = net.shurui.dev.sdu.saga.SagaSpawnBindings.matchRaw(getPersistentData());
            if (ref == null && getPersistentData().contains("sdu_clone_ref")) {
                ref = getPersistentData().getString("sdu_clone_ref");
            }
            if (ref != null && ref.startsWith("cnpc$")
                    && net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat.cnpcAvailable()) {
                net.shurui.dev.sdu.compat.cnpc.CnpcCloneSpawner.configureFighterFromRef(this, ref);
            }
            applyQuestTransformBinding();
            // last so it wins: force any explicit health override AFTER clone-ref/quest config set stat health.
            applyHealthOverride();
        } catch (Throwable t) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.debug("[sdu] Fighter self-config failed: {}", t.toString());
        }
    }

    // if HEALTH_OVERRIDE_KEY is present and positive, pin MAX_HEALTH to it and heal up. called from self-config
    // and again at the end of applyDmzStats so DMZ's stat-derived health can never clobber a spawner's HP.
    private void applyHealthOverride() {
        CompoundTag pd = getPersistentData();
        if (!pd.contains(HEALTH_OVERRIDE_KEY)) {
            return;
        }
        double override = pd.getDouble(HEALTH_OVERRIDE_KEY);
        if (override <= 0) {
            return;
        }
        var inst = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        if (inst != null) {
            inst.setBaseValue(override);
            setHealth((float) override);
        }
    }

    // apply the quest KILL objective's transform config before DMZ's own low-HP transform check runs:
    //   custom chain -> write a COPY onto ourselves (engine's index++ must not mutate the shared binding) and
    //     set dmz_quest_no_transform so the custom chain overrides DMZ's default.
    //   no chain but useDefaultTransform == false -> just disable DMZ's native transform.
    //   otherwise -> leave DMZ native transform on.
    private void applyQuestTransformBinding() {
        net.shurui.dev.sdu.saga.SagaSpawnBindings.TransformBinding tb =
                net.shurui.dev.sdu.saga.SagaSpawnBindings.matchTransform(getPersistentData());
        if (tb == null) {
            return;
        }
        net.shurui.dev.sdu.transform.TransformChain chain = tb.chain();
        if (chain != null && !chain.forms.isEmpty()) {
            // Copy-on-write (NBT round-trip): the engine's index++ must not mutate the shared binding's chain.
            net.shurui.dev.sdu.transform.TransformChain copy =
                    net.shurui.dev.sdu.transform.TransformChain.fromNbt(chain.toNbt());
            net.shurui.dev.sdu.transform.TransformChain.writeToEntity(this, copy);
            getPersistentData().putBoolean("dmz_quest_no_transform", true); // custom chain overrides DMZ default.
        } else if (!tb.useDefaultTransform()) {
            getPersistentData().putBoolean("dmz_quest_no_transform", true); // disable DMZ native transforms.
        }
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(MODEL, DEFAULT_GEO);
        this.entityData.define(TEXTURE, DEFAULT_TEXTURE);
        this.entityData.define(SKIN_TYPE, 0);
        this.entityData.define(HAIR, "");
        this.entityData.define(HAIR_COLOR, "");
    }

    public int getSkinType() {
        return this.entityData.get(SKIN_TYPE);
    }

    // type 0 = value is a texture resloc, 1 = player name, 2 = URL.
    public void setSkin(int type, String value) {
        this.entityData.set(SKIN_TYPE, type);
        setTextureLocation(value);
    }

    public String getHairCode() {
        return this.entityData.get(HAIR);
    }

    public void setHairCode(String code) {
        this.entityData.set(HAIR, code == null ? "" : code);
    }

    public String getHairColor() {
        return this.entityData.get(HAIR_COLOR);
    }

    public void setHairColor(String hex) {
        this.entityData.set(HAIR_COLOR, hex == null ? "" : hex);
    }

    public String getModelGeo() {
        return this.entityData.get(MODEL);
    }

    public void setModelGeo(String geo) {
        this.entityData.set(MODEL, geo == null || geo.isBlank() ? DEFAULT_GEO : geo);
    }

    // accepts a bare sdu model name (e.g. sdu_wide) or a full geo resloc.
    public void setModelName(String name) {
        if (name == null || name.isBlank()) {
            setModelGeo(DEFAULT_GEO);
        } else if (name.contains(":") || name.contains("/")) {
            setModelGeo(name);
        } else {
            setModelGeo("dmz_ragnarok:geo/entity/" + name + ".geo.json");
        }
    }

    // bare model name from the geo path (DMZ saga logic keys on the geckolib model name).
    public String getModelName() {
        String geo = getModelGeo();
        int slash = geo.lastIndexOf('/');
        String file = slash >= 0 ? geo.substring(slash + 1) : geo;
        int dot = file.indexOf('.');
        return dot >= 0 ? file.substring(0, dot) : file;
    }

    public String getTextureLocation() {
        return this.entityData.get(TEXTURE);
    }

    public void setTextureLocation(String loc) {
        this.entityData.set(TEXTURE, loc == null || loc.isBlank() ? DEFAULT_TEXTURE : loc);
    }

    // report our custom model as the geckolib model name so DMZ saga logic keyed on it matches what we render.
    @Override
    public String getGeckolibModelName() {
        return getModelName();
    }

    // enforce suBehavior on target acquisition (mirrors SU's LivingChangeTargetEvent so it works without SU).
    // DMZ's registerGoals adds NearestAttackableTargetGoal + HurtByTargetGoal, both route through here.
    //   2 Dialogue / 3 Follower -> swallow the target (stay null).
    //   0 Passive / 1 Wander / 4 Guard -> accept only our last attacker (retaliation) OR a pack-aggro provoke, swallow the rest.
    //   5 Hostile / 6 DMZ Fighter / -1 -> DMZ default.
    // clearing (null) always passes so other systems can still calm the fighter.
    @Override
    public void setTarget(LivingEntity target) {
        if (target != null && suBehavior >= 0 && suBehavior <= 4) {
            if (suBehavior == 2 || suBehavior == 3) {
                return; // Dialogue/Follower: never target (fully non-combat).
            }
            // Passive/Wander/Guard: accept our last attacker (retaliation), or a player this fighter was provoked to
            // pack-aggro on (a regionmate near it was attacked). SU's NpcRegionBehavior stamps the provoke into our
            // persistent data before calling setTarget; read those SAME string keys directly here, since sdu cannot
            // import SU. Everything else is swallowed.
            if (target != getLastHurtByMob() && !isProvokedBy(target)) {
                return;
            }
        }
        super.setTarget(target);
    }

    // Live pack-aggro provoke naming this exact target. The keys mirror SU's NpcRegionManager.TAG_PROVOKE_BY /
    // TAG_PROVOKE_UNTIL; a game-time gate expires a stale stamp, so a fighter with SU absent simply never gets one.
    private boolean isProvokedBy(LivingEntity target) {
        CompoundTag pd = getPersistentData();
        if (level().getGameTime() >= pd.getLong("su_npcr_provoke_until")) {
            return false;
        }
        return pd.hasUUID("su_npcr_provoke_by") && pd.getUUID("su_npcr_provoke_by").equals(target.getUUID());
    }

    // DMZ battle-power stat set (mirrors the raid boss schema). 0/-1 sentinels keep DMZ defaults.
    // kiMovesCsv = comma-separated TYPE:cooldown:size[:colorMain] tokens (4th field 0xRRGGBB int -> colour overload).
    // defense is DMZ-scale for sdu's NPC-defense curve (0 = none), stored as dmz_npc_defense NBT, NOT vanilla ARMOR.
    // server-side.
    public void applyDmzStats(int battlePower, double health, double kiBlastDamage, double moveSpeed,
                              double meleeDamage, int aiTier, String kiMovesCsv, boolean noRanged,
                              double defense) {
        try {
            if (battlePower > 0) {
                setBattlePower(battlePower);
            }
            // kept off vanilla ARMOR so LivingDamageEvent#getAmount() at LOWEST equals DMZ raw damage (one stage).
            net.shurui.dev.sdu.combat.NpcDefense.set(this, defense);
            if (kiBlastDamage > 0) {
                setKiBlastDamage((float) kiBlastDamage);
            }
            if (aiTier >= 0) {
                setAiTierById(aiTier);
            }
            setAttr(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH, health, true);
            // stamp the override so it survives DMZ's later quest-HP clobber: DMZ's EntitiesEvents reads
            // dmz_quest_hp (the ~120 scaled value) on EntityJoinLevelEvent and rewrites MAX_HEALTH. only stamp
            // a positive value so nothing is forced when no health was configured.
            if (health > 0) {
                getPersistentData().putDouble(HEALTH_OVERRIDE_KEY, health);
            }
            setAttr(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE, meleeDamage, false);
            // DBSagasEntity's AI rewrites MOVEMENT_SPEED every tick from defaultMovementSpeed, so set that field.
            if (moveSpeed > 0) {
                this.defaultMovementSpeed = moveSpeed;
                var spd = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MOVEMENT_SPEED);
                if (spd != null) {
                    spd.setBaseValue(moveSpeed);
                }
            }
            getSkillPool().clear();
            if (!noRanged && kiMovesCsv != null) {
                for (String token : kiMovesCsv.split(",")) {
                    String tk = token.trim();
                    if (tk.isEmpty()) {
                        continue;
                    }
                    String[] p = tk.split(":");
                    int cd = 60;
                    float size = 1.0f;
                    int colorMain = -1; // -1 = no colour field -> colourless (default white) overload
                    if (p.length >= 2) {
                        try { cd = Integer.parseInt(p[1].trim()); } catch (NumberFormatException ignored) { }
                    }
                    if (p.length >= 3) {
                        try { size = Float.parseFloat(p[2].trim()); } catch (NumberFormatException ignored) { }
                    }
                    if (p.length >= 4) {
                        colorMain = parseColor(p[3]);
                    }
                    try {
                        KiSkillType type = KiSkillType.valueOf(p[0].trim().toUpperCase(java.util.Locale.ROOT));
                        if (colorMain >= 0) {
                            addColoredKiSkill(type, Math.max(1, cd), size, colorMain);
                        } else {
                            addKiSkill(type, Math.max(1, cd), size);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // unknown ki type - skip
                    }
                }
            }
            // explicit health override wins over the stat health above.
            applyHealthOverride();
        } catch (Throwable t) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.debug("[sdu] Fighter applyDmzStats failed: {}", t.toString());
        }
    }

    // border/outline derived from colorMain (progressively darker), then DMZ's 6-arg colour overload.
    // darkening computed here, not via DMZ's client-only ColorUtils, so it stays server-safe.
    private void addColoredKiSkill(KiSkillType type, int cooldown, float size, int colorMain) {
        int border = darken(colorMain, 0.85f);
        int outline = darken(border, 0.6f);
        addKiSkill(type, cooldown, size, colorMain & 0xFFFFFF, border & 0xFFFFFF, outline & 0xFFFFFF);
    }

    // multiply each RGB channel by factor (clamped 0..1).
    private static int darken(int rgb, float factor) {
        float f = Math.max(0.0f, Math.min(1.0f, factor));
        int r = Math.round(((rgb >> 16) & 0xFF) * f);
        int g = Math.round(((rgb >> 8) & 0xFF) * f);
        int b = Math.round((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    // parse a colour token to 0xRRGGBB: plain decimal int, or "#RRGGBB"/"RRGGBB" hex. -1 if invalid.
    private static int parseColor(String s) {
        if (s == null) {
            return -1;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return -1;
        }
        try {
            if (t.startsWith("#")) {
                return Integer.parseInt(t.substring(1), 16) & 0xFFFFFF;
            }
            // bare 6-hex-digit string (no #) is a colour too; plain decimal int also works.
            if (t.length() == 6 && t.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
                return Integer.parseInt(t, 16) & 0xFFFFFF;
            }
            return Integer.parseInt(t) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void setAttr(net.minecraft.world.entity.ai.attributes.Attribute attr, double value, boolean alsoSetCurrent) {
        if (value <= 0) {
            return;
        }
        var inst = getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
            if (alsoSetCurrent) {
                setHealth((float) value);
            }
        }
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("sdu_model", getModelName());
        tag.putString("sdu_texture", getTextureLocation());
        tag.putInt("sdu_skin_type", getSkinType());
        tag.putString("sdu_hair", getHairCode());
        tag.putString("sdu_hair_color", getHairColor());
        if (suBehavior >= 0) {
            tag.putInt("Behavior", suBehavior); // SU NPC-region behaviour; survives reloads.
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("sdu_model")) {
            setModelName(tag.getString("sdu_model"));
        }
        if (tag.contains("sdu_texture")) {
            setTextureLocation(tag.getString("sdu_texture"));
        }
        if (tag.contains("sdu_skin_type")) {
            this.entityData.set(SKIN_TYPE, tag.getInt("sdu_skin_type"));
        }
        if (tag.contains("sdu_hair")) {
            setHairCode(tag.getString("sdu_hair"));
        }
        if (tag.contains("sdu_hair_color")) {
            setHairColor(tag.getString("sdu_hair_color"));
        }
        applySuSpawnNbt(tag);
    }

    // consume the NPC-region spawn NBT SU writes so a fighter from an SU region respects its editor options.
    // pure string-keyed NBT: no compile dep on SU, harmless when the keys are absent. wired keys:
    //   Behavior (int) -> suBehavior, enforced in setTarget.
    //   AiTier (int, 0-based) -> setAiTierById(id+1): DMZ tier is 1-based, so SU ordinal 0 maps to DMZ tier 1.
    //     NOTE the DMZ superclass ALSO reads its own 1-based "AITier" key; SU's "AiTier" is the distinct 0-based one.
    //   KiPower (float) -> setKiBlastDamage.
    //   KiEnabled (bool) + Moveset (int) -> enabled with no skills: grant one generic GENERIC_KI_WAVE;
    //     disabled: clear the pool (melee only) and stop flying.
    //   ModelScale (float) -> setScaleVal (visual + hitbox).
    // NOT wired: the Moveset ordinal isn't mapped to saga movesets (no table in sdu or DMZ to map it to), it's
    // only a "has a ranged kit" hint here. Defense/ModelId handled by SU's attribute pass and sdu_model;
    // Abilities/IdleAnim have no sdu mechanic.
    private void applySuSpawnNbt(CompoundTag tag) {
        if (tag.contains("Behavior")) {
            suBehavior = tag.getInt("Behavior");
            if (suBehavior >= 0 && suBehavior <= 4 && getTarget() != null
                    && (suBehavior == 2 || suBehavior == 3 || getTarget() != getLastHurtByMob())) {
                super.setTarget(null); // drop any target inherited from a stock spawn (retaliation targets kept).
            }
        }
        if (tag.contains("AiTier")) {
            setAiTierById(tag.getInt("AiTier") + 1); // SU 0-based ordinal -> DMZ 1-based tier id.
        }
        if (tag.contains("KiPower")) {
            float kiPower = tag.getFloat("KiPower");
            if (kiPower > 0.0f) {
                setKiBlastDamage(kiPower);
            }
        }
        // explicit ki-blast loadout (dungeon spawner). tokens TYPE:cooldown:size:colorMain (cooldown already
        // rolled, colorMain 0xRRGGBB). when present it REPLACES the generic fallback below.
        boolean explicitMoves = false;
        if (tag.contains("SduKiMovesCsv")) {
            String csv = tag.getString("SduKiMovesCsv");
            if (csv != null && !csv.isBlank()) {
                explicitMoves = true;
                getSkillPool().clear();
                for (String token : csv.split(",")) {
                    String tk = token.trim();
                    if (tk.isEmpty()) {
                        continue;
                    }
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
                        colorMain = parseColor(p[3]);
                    }
                    try {
                        KiSkillType type = KiSkillType.valueOf(p[0].trim().toUpperCase(java.util.Locale.ROOT));
                        if (colorMain >= 0) {
                            addColoredKiSkill(type, Math.max(1, cd), size, colorMain);
                        } else {
                            addKiSkill(type, Math.max(1, cd), size);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // unknown ki type in this token - skip it, keep the rest of the loadout
                    }
                }
            }
        }
        if (!explicitMoves && tag.contains("KiEnabled")) {
            boolean kiEnabled = tag.getBoolean("KiEnabled");
            if (!kiEnabled) {
                getSkillPool().clear();  // melee only
                setFlySpeed(0.0);
            } else if (getSkillPool().isEmpty()) {
                // no moveset table to map SU's ordinal to, so just grant one basic ki blast.
                addKiSkill(KiSkillType.GENERIC_KI_WAVE, 60, 1.0f);
            }
        }
        if (tag.contains("ModelScale")) {
            float scale = tag.getFloat("ModelScale");
            if (scale > 0.0f) {
                setScaleVal(scale);
            }
        }
    }
}
