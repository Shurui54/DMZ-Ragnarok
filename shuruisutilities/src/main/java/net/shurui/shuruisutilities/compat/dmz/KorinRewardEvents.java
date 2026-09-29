package net.shurui.shuruisutilities.compat.dmz;

import com.dragonminez.common.init.entities.questnpc.QuestNPCEntity;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;


import net.shurui.shuruisutilities.senzu.SenzuModule;
import net.shurui.shuruisutilities.senzu.SenzuRegistry;

/**
 * Korin's weekly senzu handout, and his nimbus handout as the suite's nimbus chip.
 *
 * <p>The owner asked that Korin give a stack of senzu beans once every real-life week. Korin is not a suite entity: he
 * is one of DragonMineZ's story quest NPCs, a {@link QuestNPCEntity} carrying the free-form npc id {@code "karin"} (the
 * Japanese name for Korin, confirmed in DMZ's {@code SideQuestDefaults}). His dialogue has a senzu option handled by
 * DMZ's {@code NPCActionC2S.handleKarin}; {@code MixinDmzKorinSenzu} sends that option here instead, so the handout is
 * the suite's own beans on the suite's weekly cooldown rather than DMZ's beans on DMZ's tick cooldown.
 *
 * <h2>What is granted</h2>
 *
 * <p>The suite's own {@code bean_senzu} (a {@link SenzuRegistry} full-restore bean), NOT {@code dragonminez:senzu_bean}:
 * the owner ruled on that in the related dragon-ball wish context. Count and cooldown come from the Senzu module config
 * ({@link SenzuModule#korinBeanCount()} / {@link SenzuModule#korinCooldownDays()}), defaulting to 4 beans and 7 real
 * days. Delivery never loses the beans: it tries the inventory first and drops at the player's feet if it is full,
 * matching how the crate grants deliver.
 *
 * <h2>The cooldown</h2>
 *
 * <p>Per PLAYER (UUID), on the wall clock, persisted in {@link KorinCooldowns} and synced network-wide, so the week is
 * one real week that a player cannot dodge by relogging, rerolling a character slot, or hopping shards.
 *
 * <p>DMZ is a mandatory dependency, so referencing {@link QuestNPCEntity} directly here is safe; this lives under
 * {@code compat/dmz} by convention, alongside the other DMZ interop.
 */
public final class KorinRewardEvents
{
    private KorinRewardEvents() {}

    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    /**
     * Korin's senzu handout, run when the player picks the senzu option in DragonMineZ's own Korin dialogue
     * (NPCActionC2S.handleKarin, option 2, redirected here by MixinDmzKorinSenzu). It used to fire on right clicking
     * Korin, alongside DMZ's own gift of DMZ beans; the owner wants ONE handout, of our beans, once a week, through
     * Korin's real dialogue. DMZ's own gift and its tick-based cooldown no longer run.
     */
    public static void grantFromDialogue(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
            return;

        KorinCooldowns cooldowns = KorinCooldowns.get(server);
        long now = System.currentTimeMillis();

        if (cooldowns.onCooldown(player.getUUID(), now))
        {
            long remaining = cooldowns.remainingMillis(player.getUUID(), now);
            player.displayClientMessage(Component.translatable(
                    "message.dmz_ragnarok.core.korin_senzu.cooldown", formatRemaining(remaining)), false);
            return;
        }

        int count = SenzuModule.korinBeanCount();
        Item bean = SenzuRegistry.beanById("bean_senzu");
        if (bean == null || count <= 0)
            return; // registry not ready or misconfigured: do nothing rather than stamp a wasted cooldown.

        giveOrDrop(player, new ItemStack(bean, count));

        int days = SenzuModule.korinCooldownDays();
        // 0 days means no gate: stamp nothing so every interaction pays out.
        if (days > 0)
            cooldowns.stamp(player.getUUID(), now + (long) days * MILLIS_PER_DAY);

        player.displayClientMessage(Component.translatable(
                "message.dmz_ragnarok.core.korin_senzu.granted", count), false);
    }

    /**
     * Korin's nimbus handout, run when the player picks the nimbus option in DragonMineZ's Korin dialogue
     * (NPCActionC2S.handleKarin, option 1, redirected here by MixinDmzKorinSenzu). DMZ gives the actual cloud item; the
     * owner wants the suite's nimbus chip instead (2026-09-29): one chip, which summons the flying or the black nimbus
     * from the player's alignment when deployed, so the alignment split DMZ makes here is kept by the chip itself.
     * DMZ has no cooldown or once-per-player rule on this option and none is added, so nothing is stamped.
     *
     * @return true when the chip was handed over (DMZ's own gift must then not run); false when the nimbus feature is
     *         off or the chip is not registered, so DMZ's gift runs as before and the player is never left with nothing.
     */
    public static boolean grantNimbusChipFromDialogue(ServerPlayer player)
    {
        if (!net.shurui.shuruisutilities.core.config.PublicContent.allows(
                net.shurui.shuruisutilities.core.config.PublicContent.FEATURE_NIMBUS))
            return false;
        if (!net.shurui.shuruisutilities.hoverbike.HoverbikeItems.NIMBUS_CHIP.isPresent())
            return false;
        giveOrDrop(player, new ItemStack(net.shurui.shuruisutilities.hoverbike.HoverbikeItems.NIMBUS_CHIP.get()));
        return true;
    }

    // Never-lose delivery: inventory first, drop at the player's feet if it is full. Matches CrateManager's grant path.
    private static void giveOrDrop(ServerPlayer player, ItemStack stack)
    {
        player.getInventory().add(stack); // mutates stack down to whatever did not fit
        if (!stack.isEmpty())
            player.drop(stack, false);
    }

    // Compact, language-neutral remaining time in days/hours/minutes (e.g. "6d 3h"), following the dungeon cooldown's
    // formatRemaining precedent. Always shows at least a minute so a player never sees a blank or "0".
    private static String formatRemaining(long millis)
    {
        long totalMinutes = (millis + 60_000L - 1) / 60_000L; // round up to the next whole minute
        long days = totalMinutes / (24L * 60L);
        long hours = (totalMinutes % (24L * 60L)) / 60L;
        long minutes = totalMinutes % 60L;
        if (days > 0)
            return days + "d " + hours + "h";
        if (hours > 0)
            return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }
}
