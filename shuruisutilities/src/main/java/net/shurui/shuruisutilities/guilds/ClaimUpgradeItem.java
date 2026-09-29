package net.shurui.shuruisutilities.guilds;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.shurui.shuruisutilities.core.config.PublicContent;
import net.shurui.shuruisutilities.guilds.model.Guild;
import net.shurui.shuruisutilities.guilds.model.GuildPermission;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;

/**
 * A one-use item that permanently raises the consumer's guild claim-chunk allowance by a fixed amount. Four
 * tiers are registered ({@code +1}, {@code +5}, {@code +10}, {@code +25}); the amount is carried per instance.
 *
 * <p>The bonus is applied AFTER the MIN of the guild's size and power limit terms (see
 * {@link GuildManager#maxClaims}), so it delivers exactly its stated number of extra chunks no matter which term
 * is currently binding, and it attaches to the GUILD rather than the player. Consumed on right click in survival;
 * in creative the stack is left intact, matching every other consumable.
 *
 * <p>This is guild content, deliberately NOT in the public key set, so the effect is gated behind the same key
 * allow-list ({@link PublicContent#moduleEntitled}) the {@code /guild} commands register under. The item still
 * EXISTS on every server (it is a creative-tab item); only its effect is gated, which is the correct split.
 */
public class ClaimUpgradeItem extends Item
{
    private final int chunks;

    public ClaimUpgradeItem(int chunks, Item.Properties props)
    {
        super(props);
        this.chunks = chunks;
    }

    public int getChunks()
    {
        return this.chunks;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand)
    {
        ItemStack stack = player.getItemInHand(hand);

        // All checks, messaging and persistence are server authoritative. The client does nothing so it never
        // predicts a swing or consume the server then refuses.
        if (level.isClientSide)
            return InteractionResultHolder.pass(stack);
        if (!(player instanceof ServerPlayer serverPlayer))
            return InteractionResultHolder.pass(stack);

        // Guild content is not public: guilds must be live here (GuildHooks, installed only by the Ragnarok Key),
        // and the key allow-list must entitle the module, exactly as ModuleGuilds does when registering the /guild
        // commands, so the item cannot hand a server a guild feature it was never entitled to run. Asking the hook
        // first means a jar that merely carries the key's mod id never reaches the guild path. Fails without
        // consuming the item.
        if (!net.shurui.shuruisutilities.api.key.GuildHooks.available() || !PublicContent.moduleEntitled("Guilds"))
        {
            ChatOutputHandler.chatError(serverPlayer, "Guilds are not enabled on this server.");
            return InteractionResultHolder.fail(stack);
        }

        Guild guild = GuildManager.guildOf(serverPlayer.getUUID());
        if (guild == null)
        {
            ChatOutputHandler.chatError(serverPlayer,
                    "You are not in a guild, so there is no guild to add claim chunks to. Join or create one first.");
            return InteractionResultHolder.fail(stack);
        }

        // Same permission the /guild claim path checks, so who may spend an upgrade tracks who may claim land.
        if (!guild.hasPermission(serverPlayer.getUUID(), GuildPermission.CLAIM))
        {
            ChatOutputHandler.chatError(serverPlayer,
                    "Your guild rank lacks permission to add claim chunks: " + GuildPermission.CLAIM.id() + ".");
            return InteractionResultHolder.fail(stack);
        }

        // Record and persist the bonus FIRST. Only shrink the stack once it is durably saved: a failed save
        // returns false here, the item is not consumed, and nothing is lost. This is the direction that cannot
        // duplicate across a shard hop, since the chunks are never delivered without the item also being spent.
        if (!GuildManager.grantClaimBonus(guild, this.chunks))
        {
            ChatOutputHandler.chatError(serverPlayer,
                    "Could not save the claim upgrade. Nothing was consumed, please try again.");
            return InteractionResultHolder.fail(stack);
        }

        if (!serverPlayer.getAbilities().instabuild)
            stack.shrink(1);

        long max = GuildManager.maxClaims(guild);
        ChatOutputHandler.chatConfirmation(serverPlayer, "Your guild can now claim " + this.chunks
                + " more chunk(s). Claim limit is now " + guild.claims.size() + " / " + max + ".");
        serverPlayer.awardStat(Stats.ITEM_USED.get(this));
        return InteractionResultHolder.sidedSuccess(stack, false);
    }
}
