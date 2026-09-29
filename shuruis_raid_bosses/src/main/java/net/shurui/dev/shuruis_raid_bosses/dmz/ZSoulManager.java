package net.shurui.dev.shuruis_raid_bosses.dmz;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.shurui.dev.shuruis_raid_bosses.api.key.RaidKeyHooks;
import net.shurui.dev.shuruis_raid_bosses.data.ZSoulData;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulItem;
import net.shurui.dev.shuruis_raid_bosses.item.ZSoulTier;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;
import net.shurui.dev.shuruis_raid_bosses.registry.ModItems;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.EnumMap;
import java.util.Optional;
import java.util.UUID;

/**
 * The Z-Soul facade. The PRIVATE beyond-cap system (the overlay projection, the Curios slot resize, the TP
 * investment and the stat gem overflow grant) lives in the Ragnarok Key and is reached through
 * {@link RaidKeyHooks}; keyless, Z-Souls are off exactly as before (the key gate always answered no). What stays
 * here is public: the worn-tier read (the client stats screen reads it too) and the banked-points export/import that
 * character slots reflect on by name, so this class keeps its FQN and its public statics.
 */
public final class ZSoulManager {
    private ZSoulManager() {}

    /** Whether Z-Souls run here: the config toggle and the key. Keyless: false. */
    public static boolean enabled() {
        return RaidKeyHooks.get().zsoulEnabled();
    }

    /** One server tick (the key re-projects overlays once a second). Keyless: nothing. */
    public static void tick(MinecraftServer server) {
        RaidKeyHooks.get().tickZSouls(server);
    }

    /** Re-projects the overlay immediately (called when a soul is equipped or removed). Keyless: nothing. */
    public static void refresh(ServerPlayer player) {
        RaidKeyHooks.get().refreshZSoul(player);
    }

    public static void onLogout(UUID player) {
        RaidKeyHooks.get().forgetZSoulOverlay(player);
    }

    /** Resizes the player's z_souls Curios slot to the configured count (called on login/respawn). Keyless: nothing. */
    public static void applySlotCount(ServerPlayer player) {
        RaidKeyHooks.get().applyZSoulSlots(player);
    }

    /**
     * Grant beyond-cap points directly (no TP cost) for the Z-Soul matching a DMZ stat key, used by Stat
     * Gems that overflow past the cap. Returns how many landed; 0 keyless, when disabled or no soul is worn.
     */
    public static int grantBeyondCap(ServerPlayer player, String dmzStatKey, int amount) {
        return RaidKeyHooks.get().grantBeyondCap(player, dmzStatKey, amount);
    }

    /**
     * Export a player's banked beyond-cap Z-Soul progress as NBT (for storing per character slot). Safe to
     * call cross-mod (e.g. reflectively); returns an empty tag when unavailable.
     */
    public static net.minecraft.nbt.CompoundTag exportBanked(ServerPlayer player) {
        if (player == null || player.getServer() == null) {
            return new net.minecraft.nbt.CompoundTag();
        }
        return ZSoulData.get(player.getServer()).saveFor(player.getUUID());
    }

    /**
     * Replace a player's banked beyond-cap Z-Soul progress from NBT and re-project the overlay (used when a
     * character slot is loaded). An empty/null tag clears their progress. Safe to call cross-mod.
     */
    public static void importBanked(ServerPlayer player, net.minecraft.nbt.CompoundTag tag) {
        if (player == null || player.getServer() == null) {
            return;
        }
        ZSoulData.get(player.getServer()).loadFor(player.getUUID(), tag);
        RaidKeyHooks.get().forgetZSoulOverlay(player.getUUID()); // force the overlay to recompute from the new values
        refresh(player);
    }

    /**
     * Highest worn tier per stat, across dedicated souls and the rainbow soul. Takes a plain
     * {@link net.minecraft.world.entity.player.Player} so the client can query its own souls too (Curios is
     * two-sided).
     */
    public static EnumMap<ZStat, ZSoulTier> wornTiers(net.minecraft.world.entity.player.Player player) {
        EnumMap<ZStat, ZSoulTier> result = new EnumMap<>(ZStat.class);
        Optional<ICuriosItemHandler> handler = CuriosApi.getCuriosInventory(player).resolve();
        if (handler.isEmpty()) return result;
        Optional<ICurioStacksHandler> slot = handler.get().getStacksHandler(ModItems.ZSOUL_SLOT);
        if (slot.isEmpty()) return result;
        IDynamicStackHandler stacks = slot.get().getStacks();
        for (int i = 0; i < stacks.getSlots(); i++) {
            ItemStack s = stacks.getStackInSlot(i);
            if (s.isEmpty() || !(s.getItem() instanceof ZSoulItem soul)) continue;
            if (soul.isRainbow()) {
                for (ZStat stat : ZStat.values()) raise(result, stat, soul.tier);
            } else {
                raise(result, soul.stat, soul.tier);
            }
        }
        return result;
    }

    private static void raise(EnumMap<ZStat, ZSoulTier> map, ZStat stat, ZSoulTier tier) {
        ZSoulTier prev = map.get(stat);
        if (prev == null || tier.ordinal() > prev.ordinal()) map.put(stat, tier);
    }
}
