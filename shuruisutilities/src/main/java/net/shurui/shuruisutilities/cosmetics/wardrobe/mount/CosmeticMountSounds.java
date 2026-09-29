package net.shurui.shuruisutilities.cosmetics.wardrobe.mount;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The per-mount SFX set: a summon and a dismiss shared by every mount, plus an idle (ambient) and a move (step or
 * flap) sound each. A summoned cosmetic mount REPLACES a vehicle, so the vehicle's own sounds never play (the
 * vehicle is never spawned); these are what a rider hears instead.
 *
 * <h2>Where the sounds come from</h2>
 * The five premium rigs (dead horse, broomstick, ghost ship, skeleptor, watcher) carry authored OGGs bundled under
 * {@code assets/dmz_ragnarok/sounds/cosmetic/mount/}, mapped from the source pack's MythicMobs skill definitions
 * (the pack plays a generic whistle on summon and revoke, a per-mount petting/idle vocal, and a per-mount walk
 * loop). The two free rigs (pumpkin hound, pumpkin spider) shipped no OGGs, only references to VANILLA sounds, so
 * they fall back to sensible vanilla events resolved from the built-in registry: their pack "sounds" were vanilla
 * all along.
 *
 * <h2>Resolved on both sides, played on the server</h2>
 * The mount entity plays ambient and movement sounds server side ({@code level.playSound(null, ...)}), which
 * broadcasts to every tracking client once, so this table is only ever read where a {@link SoundEvent} is needed.
 * The registered events are bound to the mod bus from {@code ShuruisUtilities}'s constructor, unconditionally, the
 * same rule the entity type follows.
 */
public final class CosmeticMountSounds
{
    private CosmeticMountSounds()
    {
    }

    public static final DeferredRegister<SoundEvent> REGISTER =
            DeferredRegister.create(ForgeRegistries.SOUND_EVENTS, ShuruisUtilities.MODID);

    private static RegistryObject<SoundEvent> reg(String id)
    {
        return REGISTER.register(id,
                () -> SoundEvent.createVariableRangeEvent(new ResourceLocation(ShuruisUtilities.MODID, id)));
    }

    /** The shared summon whistle, played when a mount is summoned. */
    public static final RegistryObject<SoundEvent> SUMMON = reg("cosmetic_mount_summon");

    /** The shared dismiss note, played when a mount is recalled. */
    public static final RegistryObject<SoundEvent> DISMISS = reg("cosmetic_mount_dismiss");

    // Per premium mount idle (ambient) and move (step/flap).
    private static final RegistryObject<SoundEvent> DEADHORSE_IDLE = reg("cosmetic_mount_deadhorse_idle");
    private static final RegistryObject<SoundEvent> DEADHORSE_MOVE = reg("cosmetic_mount_deadhorse_move");
    private static final RegistryObject<SoundEvent> BROOMSTICK_IDLE = reg("cosmetic_mount_broomstick_idle");
    private static final RegistryObject<SoundEvent> BROOMSTICK_MOVE = reg("cosmetic_mount_broomstick_move");
    private static final RegistryObject<SoundEvent> GHOSTSHIP_IDLE = reg("cosmetic_mount_ghostship_idle");
    private static final RegistryObject<SoundEvent> GHOSTSHIP_MOVE = reg("cosmetic_mount_ghostship_move");
    private static final RegistryObject<SoundEvent> SKELEPTOR_IDLE = reg("cosmetic_mount_skeleptor_idle");
    private static final RegistryObject<SoundEvent> SKELEPTOR_MOVE = reg("cosmetic_mount_skeleptor_move");
    private static final RegistryObject<SoundEvent> WATCHER_IDLE = reg("cosmetic_mount_watcher_idle");
    private static final RegistryObject<SoundEvent> WATCHER_MOVE = reg("cosmetic_mount_watcher_move");

    private static final Map<String, RegistryObject<SoundEvent>> IDLE = new HashMap<>();
    private static final Map<String, RegistryObject<SoundEvent>> MOVE = new HashMap<>();

    static
    {
        IDLE.put("hw_mount_deadhorse", DEADHORSE_IDLE);
        MOVE.put("hw_mount_deadhorse", DEADHORSE_MOVE);
        IDLE.put("hw_mount_broomstick", BROOMSTICK_IDLE);
        MOVE.put("hw_mount_broomstick", BROOMSTICK_MOVE);
        IDLE.put("hw_mount_ghostship", GHOSTSHIP_IDLE);
        MOVE.put("hw_mount_ghostship", GHOSTSHIP_MOVE);
        IDLE.put("hw_mount_skeleptor", SKELEPTOR_IDLE);
        MOVE.put("hw_mount_skeleptor", SKELEPTOR_MOVE);
        IDLE.put("hw_mount_watcher", WATCHER_IDLE);
        MOVE.put("hw_mount_watcher", WATCHER_MOVE);
    }

    public static SoundEvent summon()
    {
        return SUMMON.get();
    }

    public static SoundEvent dismiss()
    {
        return DISMISS.get();
    }

    /** The idle (ambient) sound for a mount id, or null when the mount is silent at idle. */
    @Nullable
    public static SoundEvent idle(String mountId)
    {
        RegistryObject<SoundEvent> r = IDLE.get(mountId);
        if (r != null)
            return r.get();
        // The free pumpkin rigs used vanilla ambients in their pack; mirror that here, looked up from the registry
        // rather than a SoundEvents constant so a renamed vanilla event degrades to silence, never a crash.
        if ("hw_mount_pumpkin_hound".equals(mountId))
            return vanilla("entity.frog.ambient");
        if ("hw_mount_pumpkin_spider".equals(mountId))
            return vanilla("entity.spider.ambient");
        return null;
    }

    /** The movement (step/flap) sound for a mount id, or null when the mount is silent while moving. */
    @Nullable
    public static SoundEvent move(String mountId)
    {
        RegistryObject<SoundEvent> r = MOVE.get(mountId);
        if (r != null)
            return r.get();
        if ("hw_mount_pumpkin_hound".equals(mountId))
            return vanilla("entity.frog.step");
        if ("hw_mount_pumpkin_spider".equals(mountId))
            return vanilla("entity.frog.step");
        return null;
    }

    /** A vanilla sound event by id, or null if the id is unknown. Guards against a renamed vanilla event. */
    @Nullable
    private static SoundEvent vanilla(String path)
    {
        return BuiltInRegistries.SOUND_EVENT.get(new ResourceLocation("minecraft", path));
    }
}
