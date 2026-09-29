package net.shurui.shuruisutilities.cosmetics.wardrobe.mount;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.PlayerWardrobe;
import net.shurui.shuruisutilities.cosmetics.wardrobe.WardrobeManager;

/**
 * Server-side authority for summoning and recalling cosmetic mounts: the one place a mount entity is spawned, and
 * the one place the "never a second vehicle per player" rule is enforced.
 *
 * <h2>One mount per player, ever</h2>
 * A live reference to each player's mount is held in {@link #ACTIVE}. Summoning while one is out RECALLS it (the
 * toggle the owner asked for), and a fresh summon only ever happens when the map holds nothing live. Because the
 * mount entity is {@code .noSave()} (see {@code CosmeticMountEntities}), a chunk unload, a restart or a crash
 * discards it rather than persisting it, so unlike the hoverbike there is no on-disk recall store to keep in step:
 * this in-memory map plus non-persistence is the whole guarantee.
 *
 * <h2>Two gates</h2>
 * {@link #active()} composes {@link WardrobeManager#active()} (the key and operator switch on the Cosmetics module)
 * with the operator's {@code Content.Cosmetics.Mounts} sub-switch. Both must be on, and both are re-checked at the
 * summon call, never trusted from the client. Equipping and owning are separate per-player questions answered by
 * {@link WardrobeManager} and the ledger.
 */
public final class CosmeticMountManager
{
    private CosmeticMountManager()
    {
    }


    /** player UUID -> their currently summoned mount. Server thread only. */
    private static final Map<UUID, CosmeticMountEntity> ACTIVE = new ConcurrentHashMap<>();

    /** Whether mounts may be summoned on this server right now: the Cosmetics module (the sub-switch is gone). */
    public static boolean active()
    {
        return WardrobeManager.active();
    }

    /**
     * Toggle: summon the player's equipped mount under them, or recall the one already out.
     *
     * @return a short status word for the caller to phrase a message around: "recalled", "summoned",
     *         "no_mount" (nothing equipped or not owned), "unknown" (equipped mount has no rig this build can
     *         draw), or "off" (the feature is gated off here).
     */
    public static String toggle(ServerPlayer player)
    {
        if (player == null)
            return "off";
        if (!active())
            return "off";

        if (isActive(player))
        {
            recall(player);
            return "recalled";
        }
        return summon(player);
    }

    /** Whether the player currently has a live summoned mount out. Cleans a dead reference on the way past. */
    public static boolean isActive(ServerPlayer player)
    {
        if (player == null)
            return false;
        CosmeticMountEntity existing = ACTIVE.get(player.getUUID());
        if (existing != null && existing.isAlive())
            return true;
        ACTIVE.remove(player.getUUID());
        return false;
    }

    /**
     * Summon the player's equipped mount under them (summon only, no recall). Returns the same status words as
     * {@link #toggle}. Extracted so the vehicle toggle path can summon a mount in a vehicle's place without also
     * inheriting the recall half of the toggle.
     */
    public static String summon(ServerPlayer player)
    {
        return summon(player, 0.0F, 0.0F);
    }

    /**
     * Summon the player's equipped mount under them, moving at the speed of the vehicle it stands in for. A
     * {@code vehicleSpeed} of 0 means "no vehicle to match" (a plain {@code /cosmetic} summon), and the mount keeps
     * its own configured speed. Returns the same status words as {@link #toggle}.
     */
    public static String summon(ServerPlayer player, float vehicleSpeed, float sprintMultiplier)
    {
        if (player == null || !active())
            return "off";
        ACTIVE.remove(player.getUUID());

        PlayerWardrobe wardrobe = WardrobeManager.current(player);
        EquippedCosmetic worn = wardrobe.worn(CosmeticSlot.MOUNT);
        if (worn == null || !worn.valid())
            return "no_mount";
        String id = worn.catalogId;
        CosmeticDef def = CosmeticCatalog.get(id);
        // Re-checked server side: the client only ever asks for a toggle, never names the mount, but ownership and
        // the definition are still verified so a stale equipped record cannot summon something unowned.
        if (def == null || def.slot != CosmeticSlot.MOUNT || !WardrobeManager.owns(player, id))
            return "no_mount";
        if (!CosmeticMountType.known(id))
            return "unknown";

        if (!(player.level() instanceof ServerLevel level))
            return "off";

        CosmeticMountEntity mount = new CosmeticMountEntity(CosmeticMountEntities.COSMETIC_MOUNT.get(), level);
        mount.setMountId(id);
        // Inherit the replaced vehicle's speed BEFORE the entity is added, so the value rides the first tracking
        // packet to every client. 0 (a plain /cosmetic summon) leaves the mount on its own configured speed.
        mount.setVehicleMovement(vehicleSpeed, sprintMultiplier);
        mount.setOwnerUUID(player.getUUID());
        mount.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        level.addFreshEntity(mount);
        player.startRiding(mount);
        ACTIVE.put(player.getUUID(), mount);
        // The shared summon whistle, so a summon reads the same whether it came from /cosmetic or from replacing a
        // vehicle. Played on the server so every tracking client hears it once.
        level.playSound(null, mount.getX(), mount.getY(), mount.getZ(), CosmeticMountSounds.summon(),
                net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.0F);
        return "summoned";
    }

    /** Whether the player currently has a live mount out. Drives the mounts screen's Summon/Recall label. */
    public static boolean isSummoned(ServerPlayer player)
    {
        if (player == null)
            return false;
        CosmeticMountEntity mount = ACTIVE.get(player.getUUID());
        return mount != null && mount.isAlive();
    }

    /**
     * Summon the player's equipped mount in the place of a vehicle they were about to deploy, but only when the
     * mount's KIND matches the vehicle's: a FLYING mount replaces a flying vehicle only, a GROUND mount a ground
     * vehicle only. The summoned mount moves at {@code vehicleSpeed} (the replaced vehicle's speed) with
     * {@code sprintMultiplier}; pass 0 for a vehicle that has no scalar speed to match (e.g. the DMZ nimbus), and the
     * mount keeps its own configured speed.
     *
     * <p>Returns true when a mount was actually summoned (or one was already out), meaning the caller must NOT spawn
     * the vehicle. Returns false for every other outcome (no usable mount, a KIND mismatch, or the feature gated
     * off), meaning the caller should deploy the vehicle unchanged so a player never loses their vehicle.
     *
     * @param requireFlying true when replacing a flying vehicle, false when replacing a ground vehicle
     */
    public static boolean summonAsReplacement(ServerPlayer player, boolean requireFlying, float vehicleSpeed,
            float sprintMultiplier)
    {
        if (player == null)
            return false;
        // Already out (via /cosmetic or a prior replacement): the one-per-player rule holds, so do not spawn a
        // vehicle on top of it. Reported as handled.
        if (isActive(player))
            return true;
        // Kind match: a mount only replaces a vehicle of its own kind. A mismatch (or no usable mount) returns false
        // so the caller deploys the real vehicle, so summoning a ground vehicle with a flying mount equipped (or the
        // reverse) gives the real vehicle rather than the wrong mount.
        Boolean flying = equippedMountFlying(player);
        if (flying == null || flying.booleanValue() != requireFlying)
            return false;
        return "summoned".equals(summon(player, vehicleSpeed, sprintMultiplier));
    }

    /**
     * Whether the player's equipped, owned, known mount flies, or null when they have no usable mount equipped. Reads
     * the same operator-edited flying flag the mount entity resolves at ride time (falling back to the seeded code
     * table), so the vehicle-kind match agrees with how the summoned mount actually moves.
     */
    @Nullable
    public static Boolean equippedMountFlying(ServerPlayer player)
    {
        if (player == null)
            return null;
        PlayerWardrobe wardrobe = WardrobeManager.current(player);
        EquippedCosmetic worn = wardrobe.worn(CosmeticSlot.MOUNT);
        if (worn == null || !worn.valid())
            return null;
        String id = worn.catalogId;
        CosmeticDef def = CosmeticCatalog.get(id);
        if (def == null || def.slot != CosmeticSlot.MOUNT || !WardrobeManager.owns(player, id)
                || !CosmeticMountType.known(id))
            return null;
        if (def.mount != null)
            return def.mount.flying;
        CosmeticMountType t = CosmeticMountType.of(id);
        return t != null ? t.flying() : null;
    }

    /** Remove the player's mount if one is out. Safe to call when there is none. */
    public static void recall(ServerPlayer player)
    {
        if (player == null)
            return;
        CosmeticMountEntity mount = ACTIVE.remove(player.getUUID());
        if (mount != null && mount.isAlive())
        {
            mount.level().playSound(null, mount.getX(), mount.getY(), mount.getZ(), CosmeticMountSounds.dismiss(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, 0.8F, 1.0F);
            mount.ejectPassengers();
            mount.discard();
        }
    }

    /** Forget a player's mount without touching the entity, for a logout where the entity is going away anyway. */
    public static void forget(UUID player)
    {
        if (player != null)
            ACTIVE.remove(player);
    }
}
