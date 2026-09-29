package net.shurui.shuruisutilities.client.cosmetics.magic;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.client.cosmetics.CosmeticRenderOptions;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEffect;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;
import net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountEntity;

/**
 * Draws every nearby player's equipped MAGIC effects, once per client tick, with nothing asked of the server per
 * frame. The whole feature is data already known here: {@code PacketWardrobeSync} tells this client, for every
 * player it can see, which copy each one is wearing and which effect id a Magic copy rolled, and
 * {@code PacketCosmeticCatalogSync} carries the effect records those ids resolve to. This class is only the loop
 * that turns that into particles.
 *
 * <h2>Why a client tick and not a render layer</h2>
 * Particles are world objects with their own lifetime and motion, spawned into the level, not redrawn each frame.
 * Emitting them from a {@link net.minecraftforge.event.TickEvent.ClientTickEvent} (once every 1/20 s, the rate the
 * effects' {@code halo.rate} is authored against) is both correct and cheap, and it keeps this out of
 * {@code WardrobeCosmeticLayer} entirely: the worn item and its Magic aura are independent, so the two can be worked
 * on without touching each other.
 *
 * <h2>Budgets, so a crowd cannot melt a client</h2>
 * <ul>
 *   <li>Only players within {@link #DRAW_RADIUS} blocks are considered, nearest first.</li>
 *   <li>At most {@link #MAX_PLAYERS} are drawn in a tick; the rest are skipped this tick.</li>
 *   <li>Everything past the nearest {@link #CROWD_AFTER}, or past {@link #CROWD_DISTANCE} blocks, uses the effect's
 *       reduced-density crowd variant.</li>
 *   <li>A shared per-tick particle allowance ({@link #PARTICLES_PER_TICK}) caps the total across every wearer.</li>
 *   <li>An invisible player draws nothing, the local player's own effect is hidden in true first person, and other
 *       players' effects honour the {@link CosmeticRenderOptions} show-others switch.</li>
 * </ul>
 *
 * <p>The annotation is BARE of a modid on purpose: the suite is one jar now, so naming one is a chance to name the
 * wrong one, which fails silently. Client only, Forge bus.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT)
public final class CosmeticMagicClientEvents
{
    /** Only wearers this close are drawn at all. */
    private static final double DRAW_RADIUS = 32.0;

    private static final double DRAW_RADIUS_SQ = DRAW_RADIUS * DRAW_RADIUS;

    /** Most wearers drawn in one tick. */
    private static final int MAX_PLAYERS = 12;

    /** Wearers past this many (nearest first) drop to the crowd variant. */
    private static final int CROWD_AFTER = 3;

    /** Wearers past this distance drop to the crowd variant regardless of how many there are. */
    private static final double CROWD_DISTANCE_SQ = 16.0 * 16.0;

    /** Total particles every wearer together may spawn in one tick. */
    private static final int PARTICLES_PER_TICK = 220;

    /** The worn slots that present a Magic aura. MOUNT is handled through the ridden entity; PET is guarded below. */
    private static final CosmeticSlot[] AURA_SLOTS =
            { CosmeticSlot.HEAD, CosmeticSlot.BACK, CosmeticSlot.ACCESSORY };

    /** Per (player, slot) emission state, kept between ticks so rates and trails are continuous. */
    private static final Map<UUID, EnumMap<CosmeticSlot, MagicEffectPresenter.State>> STATES =
            new ConcurrentHashMap<>();

    private static final RandomSource RNG = RandomSource.create();

    /**
     * A pet entity class, if the tree has one yet. Probed by name rather than imported so this compiles and runs
     * whether or not the pet work has landed: when it has, wire {@link #petAnchor} to it. Guarding on the class,
     * not assuming it, is the rule for anything not built yet.
     */
    private static final Class<?> PET_ENTITY_CLASS = probePetClass();

    private CosmeticMagicClientEvents()
    {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.isPaused() || mc.level == null || mc.player == null)
            return;

        ClientLevel level = mc.level;
        AbstractClientPlayer self = mc.player;
        boolean firstPerson = mc.screen == null && mc.options.getCameraType().isFirstPerson();
        boolean showOthers = CosmeticRenderOptions.showOthers();

        // Gather candidate wearers within range, nearest first, so the caps spend the budget on who is closest.
        List<AbstractClientPlayer> candidates = new ArrayList<>();
        Vec3 eye = self.getEyePosition();
        for (AbstractClientPlayer player : level.players())
        {
            if (player == null || player.isInvisible())
                continue;
            boolean local = player == self;
            if (!local && !showOthers)
                continue;
            if (local && firstPerson)
                continue; // your own effect is hidden in true first person; F5 and screens still show it
            if (player.distanceToSqr(eye.x, eye.y, eye.z) > DRAW_RADIUS_SQ)
                continue;
            candidates.add(player);
        }
        candidates.sort((a, b) -> Double.compare(a.distanceToSqr(eye.x, eye.y, eye.z),
                b.distanceToSqr(eye.x, eye.y, eye.z)));

        MagicEffectPresenter.Budget budget = new MagicEffectPresenter.Budget(PARTICLES_PER_TICK);
        long gameTime = level.getGameTime();
        Set<UUID> seen = new HashSet<>();

        int drawn = 0;
        for (AbstractClientPlayer player : candidates)
        {
            if (drawn >= MAX_PLAYERS)
                break;
            boolean crowded = drawn >= CROWD_AFTER
                    || player.distanceToSqr(eye.x, eye.y, eye.z) > CROWD_DISTANCE_SQ;
            boolean any = present(level, player, crowded, gameTime, budget);
            if (any)
            {
                seen.add(player.getUUID());
                drawn++;
            }
        }

        // Drop state for wearers no longer being drawn, so the map cannot grow without bound on a busy server.
        STATES.keySet().retainAll(seen);
    }

    /** Present every Magic slot on one player. Returns whether the player had anything Magic equipped at all. */
    private static boolean present(ClientLevel level, AbstractClientPlayer player, boolean crowded, long gameTime,
            MagicEffectPresenter.Budget budget)
    {
        UUID id = player.getUUID();
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        double h = player.getBbHeight();
        double yawR = Math.toRadians(player.yBodyRot);
        double fwdX = -Math.sin(yawR);
        double fwdZ = Math.cos(yawR);
        double rightX = -fwdZ;
        double rightZ = fwdX;
        Vec3 feet = new Vec3(px, py, pz);

        boolean any = false;
        for (CosmeticSlot slot : AURA_SLOTS)
        {
            CosmeticEffect fx = magicEffect(id, slot);
            if (fx == null)
                continue;
            any = true;
            Vec3 anchor = switch (slot)
            {
                case BACK -> new Vec3(px - fwdX * 0.28, py + h * 0.62, pz - fwdZ * 0.28);
                case ACCESSORY -> new Vec3(px + rightX * 0.42, py + h * 0.55, pz + rightZ * 0.42);
                default -> new Vec3(px, py + h + 0.10, pz); // HEAD: the classic halo above the head
            };
            MagicEffectPresenter.emit(level, fx, slot.key, crowded, anchor, feet, gameTime, state(id, slot), budget,
                    RNG);
        }

        // MOUNT: the aura rides the ridden cosmetic mount, not the player.
        CosmeticEffect mountFx = magicEffect(id, CosmeticSlot.MOUNT);
        if (mountFx != null)
        {
            any = true;
            Entity vehicle = player.getVehicle();
            if (vehicle instanceof CosmeticMountEntity mount)
            {
                Vec3 mAnchor = new Vec3(mount.getX(), mount.getY() + mount.getBbHeight() * 0.5, mount.getZ());
                Vec3 mFeet = new Vec3(mount.getX(), mount.getY(), mount.getZ());
                MagicEffectPresenter.emit(level, mountFx, CosmeticSlot.MOUNT.key, crowded, mAnchor, mFeet, gameTime,
                        state(id, CosmeticSlot.MOUNT), budget, RNG);
            }
        }

        // PET: only when a pet entity actually exists in the tree and belongs to this player.
        CosmeticEffect petFx = magicEffect(id, CosmeticSlot.PET);
        if (petFx != null)
        {
            any = true;
            Entity pet = petAnchor(level, player);
            if (pet != null)
            {
                Vec3 anchor = new Vec3(pet.getX(), pet.getY() + pet.getBbHeight() * 0.5, pet.getZ());
                Vec3 pFeet = new Vec3(pet.getX(), pet.getY(), pet.getZ());
                MagicEffectPresenter.emit(level, petFx, CosmeticSlot.PET.key, crowded, anchor, pFeet, gameTime,
                        state(id, CosmeticSlot.PET), budget, RNG);
            }
        }
        return any;
    }

    /** The effect record equipped in this slot on this player, but only when the copy is Magic. Null otherwise. */
    private static CosmeticEffect magicEffect(UUID player, CosmeticSlot slot)
    {
        EquippedCosmetic eq = CosmeticClientStore.equipped(player, slot);
        if (eq == null || eq.quality != CosmeticQuality.MAGIC || eq.effectId == null || eq.effectId.isBlank())
            return null;
        CosmeticEffect fx = CosmeticClientStore.effect(eq.effectId);
        return fx != null && fx.valid() ? fx : null;
    }

    private static MagicEffectPresenter.State state(UUID player, CosmeticSlot slot)
    {
        return STATES.computeIfAbsent(player, k -> new EnumMap<>(CosmeticSlot.class))
                .computeIfAbsent(slot, k -> new MagicEffectPresenter.State());
    }

    /**
     * The pet entity this player owns, or null. A no-op today because the tree has no pet entity yet: the slot is a
     * selector, not a summon. When the pet work lands, resolve {@link #PET_ENTITY_CLASS} to it and match its owner
     * here; nothing else in the Magic feature changes.
     */
    private static Entity petAnchor(ClientLevel level, AbstractClientPlayer player)
    {
        if (PET_ENTITY_CLASS == null)
            return null;
        // The pet syncs its owner, so pick the pet in this level whose owner is this player, within follow range.
        for (net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntity pet : level.getEntitiesOfClass(
                net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntity.class,
                player.getBoundingBox().inflate(24.0D)))
        {
            if (player.getUUID().equals(pet.getOwnerUUID()))
                return pet;
        }
        return null;
    }

    private static Class<?> probePetClass()
    {
        for (String name : new String[] {
                "net.shurui.shuruisutilities.cosmetics.wardrobe.pet.CosmeticPetEntity",
                "net.shurui.shuruisutilities.cosmetics.wardrobe.pet.PetEntity" })
        {
            try
            {
                return Class.forName(name);
            }
            catch (Throwable ignored)
            {
                // Not present yet. Keep looking, then leave it null.
            }
        }
        return null;
    }
}
