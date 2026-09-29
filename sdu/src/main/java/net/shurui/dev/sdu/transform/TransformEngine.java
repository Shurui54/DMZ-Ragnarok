package net.shurui.dev.sdu.transform;

import com.dragonminez.common.init.EntityAttributes;
import com.dragonminez.common.init.MainSounds;
import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.cnpc.CnpcCloneSpawner;
import net.shurui.dev.sdu.compat.cnpc.DmzCnpcCompat;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// server-side swap engine for NPC transforms. on LivingDamageEvent (AFTER armour/effects, so getAmount() is
// final) if a chained NPC's post-hit health drops to/below the next form's trigger (or the blow is lethal),
// swap to the next form: scale attributes by the form multipliers, preserve HP fraction, carry
// position/target/name/quest NBT, advance the chain. active form NBT stored under sdu_tf_active for the
// dodge/buff runtime handler.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TransformEngine {

    private static final String BUSY_KEY = "sdu_tf_busy";   // re-entrancy guard on the swap's own damage
    private static final String ACTIVE_KEY = "sdu_tf_active"; // active form NBT for the dodge/buff runtime
    private static final String DMZ_NO_TRANSFORM = "dmz_quest_no_transform"; // disable DMZ's own low-HP transform
    private static final String WINDOW_KEY = "sdu_tf_window"; // a deferred window is running (guards re-trigger)

    // deferred charge/iframe window length, matching DMZ's ~80-tick native transform cutscene. during it the OLD
    // form plays KI_CHARGE_LOOP + aura (via DMZ's TRANSFORMING flag) and is invulnerable (m_6469_/m_7327_ return
    // false while transforming), THEN swaps.
    private static final int WINDOW_TICKS = 80;

    // in-flight windows keyed by the old entity's UUID. holds a hard ref to the old form for the whole window so
    // it stays alive+resolvable for the raid engine's enemy(id) (no null-grace, no false wipe) until we swap.
    private static final Map<UUID, Pending> WINDOWS = new ConcurrentHashMap<>();

    // one in-flight deferred transform. fields captured at trigger, validity re-checked each tick.
    private static final class Pending {
        final LivingEntity old;
        final TransformChain chain;
        final TransformForm form;
        final double oldMax;
        final double postHealth;
        final boolean lethal;
        int ticks;

        Pending(LivingEntity old, TransformChain chain, TransformForm form,
                double oldMax, double postHealth, boolean lethal) {
            this.old = old;
            this.chain = chain;
            this.form = form;
            this.oldMax = oldMax;
            this.postHealth = postHealth;
            this.lethal = lethal;
        }
    }

    // buff modifier source name. distinct from the player-form source (sdu_form_rage) so they never clobber.
    private static final String BUFF_SOURCE = "sdu_tf_buff";
    // stable per-attribute UUIDs so the buff is idempotently removed/replaced, never stacked.
    private static final UUID BUFF_UUID_HEALTH = UUID.fromString("9492c29b-d6fc-4fab-b210-ddde11674630");
    private static final UUID BUFF_UUID_ATTACK = UUID.fromString("1108caea-1ae7-4c81-aad0-02d3f809b4bb");
    private static final UUID BUFF_UUID_KI = UUID.fromString("32e562ff-1861-4b72-a9bb-331ee746423d");
    private static final UUID BUFF_UUID_ARMOR = UUID.fromString("5b6aa950-222c-47e2-b8f9-2c1b3488b61e");

    private TransformEngine() {
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide) {
            return;
        }
        TransformChain c = TransformChain.readFromEntity(e);
        if (c == null || !c.hasRemaining()) {
            return;
        }
        if (e.getPersistentData().getBoolean(BUSY_KEY)) {
            return; // re-entrancy guard
        }
        if (e.getPersistentData().getBoolean(WINDOW_KEY)) {
            return; // a deferred charge/iframe window is already running on this entity
        }
        double oldMax = e.getMaxHealth();
        double postHealth = e.getHealth() - event.getAmount();
        TransformForm form = c.next();
        boolean lethal = postHealth <= 0;
        if (postHealth <= oldMax * form.trigger || lethal) {
            if (lethal) {
                event.setAmount(0); // cancel the killing blow; we transform instead.
            }
            // saga bosses get DMZ's native transform look (charge window then swap). gate strictly on
            // DBSagasEntity so PLAYER transforms (stack-form/quest-purchase/prestige-race) are never touched.
            if (e instanceof DBSagasEntity saga && startWindow(saga, c, form, oldMax, postHealth, lethal)) {
                return; // window started; swap runs in ~80 ticks from onServerTick.
            }
            // non-saga, or window couldn't start: original atomic swap, no charge window.
            performSwap(e, c, form, oldMax, postHealth, lethal);
        }
    }

    // start a charge/iframe window: flag TRANSFORMING (yields DMZ iframes via m_6469_/m_7327_ and drives the
    // charge anim + aura), play the same KI_CHARGE_LOOP sound as DMZ. we can't call DMZ's startTransformation()
    // (protected, hardwired to DMZ's next-form logic) so we replicate its two observable effects while keeping
    // OUR next sdu_tf form as the target. old form kept undiscarded the whole window so raid enemy(id) still
    // resolves it. false if a window is already running.
    private static boolean startWindow(DBSagasEntity saga, TransformChain chain, TransformForm form,
                                       double oldMax, double postHealth, boolean lethal) {
        UUID id = saga.getUUID();
        if (saga.getPersistentData().getBoolean(WINDOW_KEY) || WINDOWS.containsKey(id)) {
            return false;
        }
        saga.getPersistentData().putBoolean(WINDOW_KEY, true);
        saga.setTransforming(true);
        SoundEvent charge = MainSounds.KI_CHARGE_LOOP.get();
        if (charge != null) {
            saga.playSound(charge, 1.0f, 1.2f); // same sound + pitch as DMZ startTransformation()
        }
        WINDOWS.put(id, new Pending(saga, chain, form, oldMax, postHealth, lethal));
        return true;
    }

    // drive in-flight windows on the server tick END phase (same as HakaiManager). age one tick each; cancel if
    // the charging entity dies/removes mid-window. after WINDOW_TICKS run the atomic swap (old discarded, next
    // spawned + re-adopted by the raid on EntityJoinLevelEvent). the 1-tick null is well inside the raid's
    // 100-tick null-grace.
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || WINDOWS.isEmpty()) {
            return;
        }
        for (Iterator<Map.Entry<UUID, Pending>> it = WINDOWS.entrySet().iterator(); it.hasNext(); ) {
            Pending p = it.next().getValue();
            LivingEntity old = p.old;
            try {
                // Entity gone mid-window (killed via /kill, unloaded, dimension change): cancel cleanly.
                if (old == null || !old.isAlive() || old.isRemoved()) {
                    if (old != null) {
                        old.getPersistentData().putBoolean(WINDOW_KEY, false);
                        if (old instanceof DBSagasEntity s) {
                            s.setTransforming(false);
                        }
                    }
                    it.remove();
                    continue;
                }
                // re-assert TRANSFORMING each tick so a stray clear can't drop the iframes early.
                if (old instanceof DBSagasEntity s && !s.isTransforming()) {
                    s.setTransforming(true);
                }
                if (++p.ticks < WINDOW_TICKS) {
                    continue;
                }
                // window elapsed: clear TRANSFORMING + the guard, then swap. clearing WINDOW_KEY before
                // performSwap keeps the persistentData carry-over clean on the new form.
                if (old instanceof DBSagasEntity s) {
                    s.setTransforming(false);
                }
                old.getPersistentData().putBoolean(WINDOW_KEY, false);
                it.remove();
                performSwap(old, p.chain, p.form, p.oldMax, p.postHealth, p.lethal);
            } catch (Throwable t) {
                if (old != null) {
                    old.getPersistentData().putBoolean(WINDOW_KEY, false);
                    if (old instanceof DBSagasEntity s) {
                        s.setTransforming(false);
                    }
                }
                it.remove();
                DmzNpc.LOGGER.warn("[{}] transform window errored, cancelling: {}", DmzNpc.MODID, t.toString());
            }
        }
    }

    private static void performSwap(LivingEntity old, TransformChain chain, TransformForm form,
                                    double oldMax, double postHealth, boolean lethal) {
        old.getPersistentData().putBoolean(BUSY_KEY, true);
        Level level = old.level();
        try {
            // 1. Resolve the new entity.
            Entity created;
            String target = form.target == null ? "" : form.target.trim();
            boolean cloneRef = target.startsWith("cnpc$");
            if (cloneRef) {
                if (!DmzCnpcCompat.cnpcAvailable()) {
                    return; // cnpc absent: abort, do NOT consume the form.
                }
                created = CnpcCloneSpawner.fighterFromRef(target, level);
                if (created == null) {
                    return; // clone missing/unbuildable: abort.
                }
            } else {
                EntityType<?> t = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(target));
                if (t == null) {
                    return; // unknown entity id: abort.
                }
                created = t.create(level);
            }
            if (!(created instanceof LivingEntity newEntity)) {
                return; // not a LivingEntity: abort.
            }

            // 2. Scale attributes off the OLD entity's CURRENT values.
            double newMax = oldMax * form.hpMult;
            setBase(newEntity, Attributes.MAX_HEALTH, newMax);

            double newMelee = old.getAttributeValue(Attributes.ATTACK_DAMAGE) * form.meleeMult;
            setBase(newEntity, Attributes.ATTACK_DAMAGE, newMelee);

            AttributeInstance oldSpeed = old.getAttribute(Attributes.MOVEMENT_SPEED);
            if (oldSpeed != null) {
                // DMZ rewrites MOVEMENT_SPEED from its defaultMovementSpeed FIELD every tick, so the attribute
                // set alone is ignored on saga bosses; the field is set below for DBSagasEntity to actually apply.
                setBase(newEntity, Attributes.MOVEMENT_SPEED, oldSpeed.getValue() * form.speedMult);
            }

            double newKi = 0;
            Attribute kiAttr = EntityAttributes.KI_BLAST_DAMAGE.get();
            if (newEntity instanceof DBSagasEntity sagaNew) {
                AttributeInstance oldKiInst = old.getAttribute(kiAttr);
                if (oldKiInst != null) {
                    newKi = oldKiInst.getValue() * form.kiMult;
                    setBase(newEntity, kiAttr, newKi);
                }
                // mirror applyDmzStats: battle power tracks melee + ki.
                sagaNew.setBattlePower((int) Math.round(newMelee + newKi));

                if (old instanceof DBSagasEntity sagaOld) {
                    // carry the AI tier (mirrors DMZ's finishTransformationSpawn). without it every new form drops
                    // to AiTier.SIMPLE, whose heavy cast loop freezes handleCommonCombatMovement navigation nearly
                    // every casting tick, so the boss shoots but never walks. keep the full skill pool (no
                    // trimming); a SIMPLE heavy-caster may still root, but carrying aiTier fixes it for the
                    // higher-tier bosses this is actually used with.
                    sagaNew.setAiTierById(sagaOld.getAiTierId());

                    // set DMZ's ground-speed FIELD, not just the attribute (DMZ rewrites it from this field each
                    // tick). this is what makes form.speedMult actually change walk speed.
                    sagaNew.setDefaultMovementSpeed(sagaOld.getDefaultMovementSpeed() * form.speedMult);
                }
            }

            // form defense scaling runs through the NPC-defense curve, not vanilla ARMOR (fighters keep ARMOR=0
            // so LivingDamageEvent#getAmount() stays the DMZ raw, one stage). carried+scaled below, after the
            // persistentData merge that would otherwise copy the OLD defense verbatim. never set vanilla ARMOR
            // here or we'd double-mitigate.

            // 2b. form stat buff (% of base, MULTIPLY_BASE) on top of the scaled attributes. recompute newMax
            // AFTER so the VIT contribution shows in the HP-fraction below.
            applyFormBuff(newEntity, form);
            newMax = newEntity.getMaxHealth();

            // 3. Preserve HP fraction (a lethal blow revives at the trigger%).
            double frac = lethal ? form.trigger : Math.max(0.0, Math.min(1.0, postHealth / oldMax));
            newEntity.setHealth((float) (newMax * frac));

            // 4. Copy position / orientation / target / name / persistentData.
            newEntity.moveTo(old.getX(), old.getY(), old.getZ(), old.getYRot(), old.getXRot());
            newEntity.setYHeadRot(old.getYHeadRot());
            if (old instanceof Mob oldMob && newEntity instanceof Mob newMob) {
                carryTarget(oldMob, newMob);
            }
            if (old.getCustomName() != null) {
                newEntity.setCustomName(old.getCustomName());
                newEntity.setCustomNameVisible(old.isCustomNameVisible());
            }
            // carry the old persistentData (keeps dmz_quest_* difficulty/team), minus our own control keys.
            CompoundTag carried = old.getPersistentData().copy();
            carried.remove(TransformChain.KEY);
            carried.remove(BUSY_KEY);
            carried.remove(WINDOW_KEY); // never carry a stale window flag onto the fresh form
            newEntity.getPersistentData().merge(carried);

            // defense scaling via the NPC-defense curve: OLD form's dmz_npc_defense (carried by the merge above)
            // * form.defMult, fold in the RES stat-gain buff, re-stamp on the new form. ARMOR stays 0.
            double oldDefense = net.shurui.dev.sdu.combat.NpcDefense.get(old);
            if (oldDefense > 0.0) {
                double resGainPct = Math.max(0.0, gain(form, "RES")); // +X%, see applyFormBuff
                double scaledDefense = oldDefense * form.defMult * (1.0 + resGainPct / 100.0);
                net.shurui.dev.sdu.combat.NpcDefense.set(newEntity, scaledDefense);
            }

            // The form's ragnarok NPC character, before the entity enters the world so the first packet every
            // viewer gets already carries it. Blank (and any target that is not a ragnarok NPC) is a no-op, and
            // it is a no-op in an sdu-only install too, where nothing has installed an applier.
            net.shurui.dev.sdu.compat.ragnarok.RagnarokLook.apply(newEntity, form.rgModelId);

            chain.index++;
            TransformChain.writeToEntity(newEntity, chain);
            newEntity.getPersistentData().putBoolean(DMZ_NO_TRANSFORM, true);
            newEntity.getPersistentData().put(ACTIVE_KEY, form.toNbt());

            level.addFreshEntity(newEntity);
            old.discard();
        } catch (Throwable t) {
            // fail mid-swap: leave `old` alone, don't half-transform.
            old.getPersistentData().putBoolean(BUSY_KEY, false);
            DmzNpc.LOGGER.warn("[{}] transform swap failed for '{}': {}",
                    DmzNpc.MODID, form.target, t.toString());
        }
    }

    // carry the AI target across a swap without ever handing the new form a null/dead target (a stale null,
    // e.g. after a raid confine-drop or the target logging off, leaves the next form permanently inert).
    // copy the old target only if alive+present, else nearest living player, else leave it to the AI.
    private static void carryTarget(Mob oldMob, Mob newMob) {
        LivingEntity t = oldMob.getTarget();
        if (t != null && t.isAlive() && !t.isRemoved()) {
            newMob.setTarget(t);
            return;
        }
        Player nearest = newMob.level().getNearestPlayer(newMob, 64.0);
        if (nearest != null && nearest.isAlive() && !nearest.isSpectator()) {
            newMob.setTarget(nearest);
        }
        // else no valid target: leave it to the AI's own goals (never set null).
    }

    private static void setBase(LivingEntity e, Attribute attr, double value) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst != null) {
            inst.setBaseValue(value);
        }
    }

    // apply the form's statGainPercent as MULTIPLY_BASE modifiers (+X% of base), summed per target, capped at
    // form.maxBonusPercent. mapping: VIT->MAX_HEALTH, STR+SKP->ATTACK_DAMAGE, PWR->KI_BLAST_DAMAGE (saga only),
    // RES->folded into dmz_npc_defense at swap time (not ARMOR), ENE->no NPC analog. prior sdu_tf_buff removed
    // first so a later form REPLACES, not stacks.
    static void applyFormBuff(LivingEntity e, TransformForm form) {
        double cap = form.maxBonusPercent;

        applyBuffModifier(e, Attributes.MAX_HEALTH, BUFF_UUID_HEALTH,
                capPct(gain(form, "VIT"), cap));

        applyBuffModifier(e, Attributes.ATTACK_DAMAGE, BUFF_UUID_ATTACK,
                capPct(gain(form, "STR") + gain(form, "SKP"), cap));

        Attribute kiAttr = EntityAttributes.KI_BLAST_DAMAGE.get();
        if (e instanceof DBSagasEntity && e.getAttribute(kiAttr) != null) {
            applyBuffModifier(e, kiAttr, BUFF_UUID_KI, capPct(gain(form, "PWR"), cap));
        }

        // RES not applied to ARMOR here: ARMOR stays 0 and RES is folded into dmz_npc_defense at swap time.
        // applying it here too would double-count.
    }

    // per-stat gain percent, 0 for absent keys.
    private static double gain(TransformForm form, String stat) {
        Double v = form.statGainPercent == null ? null : form.statGainPercent.get(stat);
        return v == null ? 0.0 : v;
    }

    // floored at 0, capped at the form's maxBonusPercent.
    private static double capPct(double pct, double cap) {
        if (pct <= 0) {
            return 0.0;
        }
        return Math.min(pct, cap);
    }

    // remove any existing modifier by UUID, then add a fresh MULTIPLY_BASE of pct/100 if pct > 0.
    private static void applyBuffModifier(LivingEntity e, Attribute attr, UUID id, double pct) {
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) {
            return;
        }
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null) {
            inst.removeModifier(existing);
        }
        if (pct > 0) {
            inst.addPermanentModifier(new AttributeModifier(
                    id, BUFF_SOURCE, pct / 100.0, AttributeModifier.Operation.MULTIPLY_BASE));
        }
    }
}
