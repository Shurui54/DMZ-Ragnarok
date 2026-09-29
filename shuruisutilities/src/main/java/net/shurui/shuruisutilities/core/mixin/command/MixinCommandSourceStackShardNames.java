package net.shurui.shuruisutilities.core.mixin.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.commands.CommandSourceStack;

import net.shurui.shuruisutilities.api.key.ShardHooks;
import net.shurui.shuruisutilities.shard.ShardSync;

/**
 * Network-wide player-name tab completion.
 *
 * <h2>What this does and why it is the right seam</h2>
 * Every command that suggests a player name, vanilla ({@code /tp}, {@code /msg}, ...) and SU's own alike, does it
 * through {@code EntityArgument.listSuggestions}, which fills its suggestion list from
 * {@link CommandSourceStack#getOnlinePlayerNames()} (see the argument type: it takes exactly that collection and,
 * for the entity-not-player forms, concatenates the selected-entity names onto it). Appending the network's remote
 * players to that ONE method therefore reaches every player-name argument at once, with a single hook, rather than
 * bolting a custom {@link com.mojang.brigadier.suggestion.SuggestionProvider} onto each SU command one by one and
 * still leaving vanilla's uncovered.
 *
 * <h2>Why it does not break selectors or local precedence</h2>
 * This only ADDS strings to the suggestion pool. The selector grammar ({@code @a}, {@code @p}, {@code @e},
 * {@code @s}) is parsed separately by {@code EntitySelectorParser} and is untouched: a longer name list changes
 * nothing about how a leading {@code @} is completed. Local players stay in the list and stay FIRST (they are the
 * whole of the original return value, which is copied ahead of any remote names), and a remote name equal to a local
 * one is dropped, so a duplicate never appears and the local entry always wins.
 *
 * <h2>Suggestion only, never resolution</h2>
 * {@code getOnlinePlayerNames()} feeds autocomplete alone; player RESOLUTION goes through the player list, which this
 * does not touch. So a remote name offered here still resolves to "no player found" when a command actually runs it,
 * which is exactly the seam {@code ShardTpa} / {@code ShardTp} intercept. This closes the completion half of the
 * complaint; those two close the execution half.
 *
 * <h2>No database on the command thread</h2>
 * The remote roster is read through {@code ShardHooks.networkPlayerNames()}, which answers from
 * {@code ShardTabList.presenceSnapshot()}, the snapshot that reconciler already pulls off thread on its beat, so
 * nothing here touches the vault. If no snapshot exists yet (sharding just came up, or the
 * tab-list reconciler has not run), the list is simply empty and only local names are offered that tick.
 *
 * <p>require = 0: this targets a vanilla Minecraft class, so an upstream rename must DEGRADE (local-only completion)
 * rather than crash, per the suite's Minecraft-mixin rule.
 */
@Mixin(CommandSourceStack.class)
public class MixinCommandSourceStackShardNames
{
    @Inject(method = "getOnlinePlayerNames", at = @At("RETURN"), cancellable = true, require = 0)
    private void su$addNetworkPlayerNames(CallbackInfoReturnable<Collection<String>> cir)
    {
        if (!ShardSync.active())
            return;
        List<String> names = ShardHooks.get().networkPlayerNames();
        if (names.isEmpty())
            return;

        Collection<String> local = cir.getReturnValue();
        // Local names first and kept: they are the original return value, copied ahead of anything remote, so a
        // local player always takes precedence in the completion list.
        Set<String> seen = new HashSet<>();
        for (String name : local)
            seen.add(name.toLowerCase(Locale.ROOT));

        List<String> merged = new ArrayList<>(local);
        for (String name : names)
        {
            // add() returns false when the name (folded to lower case) is already present, which drops a remote
            // entry that duplicates a local one and, incidentally, a remote name that appears on two shards at once.
            if (name != null && seen.add(name.toLowerCase(Locale.ROOT)))
                merged.add(name);
        }
        cir.setReturnValue(merged);
    }
}
