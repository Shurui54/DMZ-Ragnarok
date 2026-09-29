package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The ONE place a cosmetic token is ever created. Minting is the primary defence of the transfer gate: a copy that
 * may not move never becomes an item at all, so no later route has to catch it.
 *
 * <h2>What mint requires, and why each check is here</h2>
 * A copy is minted into a token only when its {@link CosmeticOwnership} row is live, owned by the minter, not
 * already a token, and not per-copy bound, AND its {@link CosmeticDef} is tradeable. Bound is checked as well as
 * tradeable because a shop purchase binds one copy whatever the type allows, so a bound copy of a tradeable
 * cosmetic must still refuse to become an item. All of it is the {@link TransferRoute#MINT} gate, expressed once.
 *
 * <p>The mint is a single server-thread mutation: flip the row escrowed ({@link CosmeticLedgerData#escrow}), then
 * build the item. The row stays THE one row for this copy; the token only points back at it. If the copy was being
 * worn it is taken off first, or the wearer would keep rendering a cosmetic that is now an item in their bag.
 */
public final class CosmeticTokens
{
    private CosmeticTokens()
    {
    }

    /**
     * Turn one owned copy into a token stack, or return an empty stack (having changed nothing) when the copy may
     * not be minted.
     *
     * <p>The instance is named explicitly because a player may own more than one copy of a cosmetic and only the
     * chosen one becomes the item. The caller is responsible for having picked an instance the player actually
     * owns; every reason it might not be mintable is re-checked here regardless.
     */
    public static ItemStack mint(MinecraftServer server, ServerPlayer owner, UUID instanceId)
    {
        if (server == null || owner == null || instanceId == null)
            return ItemStack.EMPTY;
        CosmeticLedgerData ledger = CosmeticLedgerData.get(server);
        CosmeticOwnership row = ledger.row(instanceId);
        if (row == null || !row.live() || row.escrowed || row.bound)
            return ItemStack.EMPTY;
        if (!owner.getUUID().equals(row.player))
            return ItemStack.EMPTY;
        CosmeticDef def = CosmeticCatalog.get(row.catalogId);
        if (def == null || !def.tradeable)
            return ItemStack.EMPTY;
        // Take it off first if it is being worn: the equipped slot names this exact instance, so leaving it on would
        // keep drawing a cosmetic that is no longer in the wardrobe. Only the slot wearing THIS instance is touched,
        // so a second copy the player wears is left alone.
        PlayerWardrobe worn = WardrobeManager.current(owner);
        for (CosmeticSlot slot : CosmeticSlot.values())
        {
            EquippedCosmetic e = worn.worn(slot);
            if (e != null && instanceId.equals(e.instanceId))
                WardrobeManager.unequip(owner, slot);
        }
        if (!ledger.escrow(instanceId, owner.getUUID()))
            return ItemStack.EMPTY;
        WardrobeManager.syncOwner(server, owner.getUUID());
        return CosmeticTokenItem.build(row.catalogId, instanceId, row.quality);
    }
}
