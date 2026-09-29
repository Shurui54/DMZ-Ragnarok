package net.shurui.dev.shuruis_dmz_dungeons.dungeon;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;

// Delivery for dungeon rewards (crate loot, boss drops) that must NEVER void. Three tiers, most immediate first:
// the player's inventory, a drop at their feet in a SAFE dimension, and a durable pending store.
//
// The pending store rides the player's Forge persistent-data compound, which the cross-shard vault captures
// wholesale (see ShardPayload's FORGE branch), so a reward banked here travels with a hop and is delivered on the
// next login or the moment the player leaves a floor dimension. This closes the two ways a dungeon reward went
// missing that a plain "inventory else drop at feet" grant could not:
//   * a full inventory whose drop fell inside a themed floor dimension that was later torn down with its chunks, so
//     the dropped item entity was unloaded and lost; and
//   * a reward handed into the inventory just before a shard hop, whose payload was then overwritten by an older
//     vault copy (a stale write or an unclean disconnect), losing the reward with the rest of that stale restore.
// A banked reward survives both because it is durable and delivered again after every restore.
public final class PendingRewards {

    private PendingRewards() {
    }

    // The list of serialized ItemStacks still owed to this player, kept on their Forge persistent data so it travels
    // in the vault payload. One compound per owed stack, in the vanilla ItemStack.save() shape.
    private static final String KEY = "sdd_pending_rewards";

    /**
     * Give a dungeon reward, guaranteeing the player ends up with it. Tiers: inventory, then a drop at their feet in
     * a dimension where a dropped item survives, then the durable pending store. Logged at debug on every path.
     */
    public static void give(ServerPlayer player, ItemStack stack) {
        if (player == null || stack == null || stack.isEmpty()) {
            return;
        }
        int total = stack.getCount();
        ItemStack remainder = stack.copy();
        player.getInventory().add(remainder); // mutates remainder down to whatever did not fit
        if (remainder.isEmpty()) {
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Delivered {}x {} to {} (inventory).",
                    Shuruis_dmz_dungeons.MODID, total, stack.getItem(), player.getGameProfile().getName());
            return;
        }
        // A drop inside a floor / dungeon dimension can be lost: those dimensions are torn down and their chunks
        // unloaded when the party leaves or the server restarts, taking any loose item entity with them. Bank the
        // remainder instead. Every other dimension keeps a dropped item, so a drop there is safe and immediate.
        if (DungeonDimensions.isAnyDungeon(player.level())) {
            bank(player, remainder);
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Inventory full for {} in a floor dimension; banked {}x {} to the"
                    + " pending store.", Shuruis_dmz_dungeons.MODID, player.getGameProfile().getName(),
                    remainder.getCount(), stack.getItem());
        } else {
            player.drop(remainder, false);
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Inventory full for {}; dropped {}x {} at their feet.",
                    Shuruis_dmz_dungeons.MODID, player.getGameProfile().getName(), remainder.getCount(),
                    stack.getItem());
        }
    }

    private static void bank(ServerPlayer player, ItemStack stack) {
        CompoundTag pd = player.getPersistentData();
        ListTag list = pd.getList(KEY, Tag.TAG_COMPOUND);
        list.add(stack.save(new CompoundTag()));
        pd.put(KEY, list);
    }

    /**
     * Deliver everything owed into the inventory; anything that still does not fit stays owed for the next time.
     * Called after the vault restore on login, and when a player leaves a floor dimension. Safe to call with nothing
     * owed. Only ever adds to the inventory, so it is safe in any dimension.
     */
    public static void deliver(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CompoundTag pd = player.getPersistentData();
        if (!pd.contains(KEY, Tag.TAG_LIST)) {
            return;
        }
        ListTag owed = pd.getList(KEY, Tag.TAG_COMPOUND);
        if (owed.isEmpty()) {
            pd.remove(KEY);
            return;
        }
        ListTag stillOwed = new ListTag();
        int delivered = 0;
        for (int i = 0; i < owed.size(); i++) {
            ItemStack stack = ItemStack.of(owed.getCompound(i));
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack remainder = stack.copy();
            player.getInventory().add(remainder);
            if (remainder.isEmpty()) {
                delivered++;
            } else {
                stillOwed.add(remainder.save(new CompoundTag()));
            }
        }
        if (stillOwed.isEmpty()) {
            pd.remove(KEY);
        } else {
            pd.put(KEY, stillOwed);
        }
        if (delivered > 0) {
            Shuruis_dmz_dungeons.LOGGER.debug("[{}] Delivered {} owed dungeon reward stack(s) to {}{}.",
                    Shuruis_dmz_dungeons.MODID, delivered, player.getGameProfile().getName(),
                    stillOwed.isEmpty() ? "" : " (" + stillOwed.size() + " still owed, inventory full)");
        }
    }
}
