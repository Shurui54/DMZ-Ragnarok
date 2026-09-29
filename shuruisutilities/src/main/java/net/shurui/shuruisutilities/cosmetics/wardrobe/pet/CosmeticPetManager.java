package net.shurui.shuruisutilities.cosmetics.wardrobe.pet;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticCatalog;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.EquippedCosmetic;
import net.shurui.shuruisutilities.cosmetics.wardrobe.PlayerWardrobe;
import net.shurui.shuruisutilities.cosmetics.wardrobe.WardrobeManager;

/**
 * Server-side authority for spawning and despawning cosmetic pets: the one place a pet entity is spawned, and the
 * one place the "one pet per player" rule is enforced.
 *
 * <h2>Reconcile, do not toggle</h2>
 * Unlike the mount (a manual {@code /cosmetic summon} toggle), a pet is a straight mirror of the equipped PET slot:
 * equip one and it appears, unequip it or equip another and it is replaced, and on login, respawn, a dimension
 * change or a shard arrival it is re-spawned if still equipped. Every one of those paths calls {@link #refresh}
 * which spawns, replaces or removes the pet to match the wardrobe. A live reference to each player's pet is held in
 * {@link #ACTIVE}; because the pet entity is {@code .noSave()}, a chunk unload, a restart or a crash discards it
 * rather than persisting it, so this in-memory map plus non-persistence is the whole guarantee.
 *
 * <h2>Two gates</h2>
 * {@link #active()} composes {@link WardrobeManager#active()} (the key and operator switch on the Cosmetics module)
 * with the operator's {@code Content.Cosmetics.Pets} sub-switch. Both must be on, re-checked at the spawn call and
 * never trusted from the client. Owning the pet is a separate per-player question answered by the ledger.
 */
public final class CosmeticPetManager
{
    private CosmeticPetManager()
    {
    }


    /** player UUID -> their currently spawned pet. Server thread only. */
    private static final Map<UUID, CosmeticPetEntity> ACTIVE = new ConcurrentHashMap<>();

    /** Whether pets may be spawned on this server right now: the Cosmetics module (the sub-switch is gone). */
    public static boolean active()
    {
        return WardrobeManager.active();
    }

    /**
     * Bring the player's pet into line with their equipped PET slot: spawn the right one, replace a wrong one, or
     * remove it entirely. Safe to call on any lifecycle path (equip change, login, respawn, dimension change,
     * arrival) and idempotent when nothing has changed.
     */
    public static void refresh(ServerPlayer player)
    {
        if (player == null)
            return;

        String desired = active() ? desiredPetId(player) : null;

        CosmeticPetEntity existing = ACTIVE.get(player.getUUID());
        if (existing != null && (!existing.isAlive() || existing.level() != player.level()))
        {
            // A dead reference, or one left behind in the level the owner just left: drop it and re-spawn below.
            if (existing.isAlive())
                existing.discard();
            ACTIVE.remove(player.getUUID());
            existing = null;
        }

        if (desired == null)
        {
            despawn(player);
            return;
        }

        if (existing != null && desired.equals(existing.getPetId()))
            return;

        // Wrong pet or none: clear whatever is out and spawn the desired one at the owner's feet.
        despawn(player);
        if (!(player.level() instanceof ServerLevel level))
            return;

        CosmeticPetEntity pet = new CosmeticPetEntity(CosmeticPetEntities.COSMETIC_PET.get(), level);
        pet.setPetId(desired);
        pet.setOwnerUUID(player.getUUID());
        // Name tag over the pet's head: "<owner>'s pet". Set as the synced custom name, so it is correct on every
        // client and renders with the usual mob name-tag distance and occlusion. The client renderer suppresses it
        // when the viewer hid pets or the owner is invisible.
        pet.setCustomName(net.minecraft.network.chat.Component.literal(player.getGameProfile().getName() + "'s pet"));
        pet.setCustomNameVisible(true);
        pet.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        level.addFreshEntity(pet);
        ACTIVE.put(player.getUUID(), pet);
    }

    /**
     * The pet id this player should have out, or null when nothing should be. Re-checked server side: the equipped
     * record is verified against the catalogue and the ledger so a stale record cannot spawn something unowned, and
     * the id has to name a pet this build can actually draw ({@link CosmeticPetType#known}).
     */
    private static String desiredPetId(ServerPlayer player)
    {
        PlayerWardrobe wardrobe = WardrobeManager.current(player);
        EquippedCosmetic worn = wardrobe.worn(CosmeticSlot.PET);
        if (worn == null || !worn.valid())
            return null;
        String id = worn.catalogId;
        CosmeticDef def = CosmeticCatalog.get(id);
        if (def == null || def.slot != CosmeticSlot.PET || !WardrobeManager.owns(player, id))
            return null;
        if (!CosmeticPetType.known(id))
            return null;
        return id;
    }

    /** Remove the player's pet if one is out. Safe to call when there is none. */
    public static void despawn(ServerPlayer player)
    {
        if (player == null)
            return;
        CosmeticPetEntity pet = ACTIVE.remove(player.getUUID());
        if (pet != null && pet.isAlive())
            pet.discard();
    }

    /** Forget a player's pet without touching the entity, for a logout where the entity is going away anyway. */
    public static void forget(UUID player)
    {
        if (player != null)
            ACTIVE.remove(player);
    }

    /** The equipped PET slot changed: reconcile. Called from {@link WardrobeManager#equip}. */
    public static void onEquipChanged(ServerPlayer player)
    {
        refresh(player);
    }

    /**
     * Slow-tick liveness check for one player's pet, driven from the server tick.
     *
     * <p>A cosmetic pet is {@code .noSave()}, so anything that removes the entity without going through
     * {@link #despawn} (a chunk unload while the owner is briefly not keeping it loaded, the entity's own
     * non-finite-position guard, a momentary cross-level flicker) leaves its {@link #ACTIVE} reference pointing at a
     * dead entity. Nothing else re-spawns it until the next lifecycle event (login, respawn, dimension change or an
     * equip change), so without this a pet that vanished mid-session would stay gone until a relog, which is exactly
     * a pet "going invisible sometimes". Only a player who ALREADY has a tracked pet is considered here, so this adds
     * no wardrobe or ledger lookups for players with none: the equip and login paths own that case.
     */
    public static void tickReconcile(ServerPlayer player)
    {
        if (player == null)
            return;
        CosmeticPetEntity existing = ACTIVE.get(player.getUUID());
        if (existing == null)
            return;
        if (!existing.isAlive() || existing.level() != player.level())
            refresh(player);
    }
}
