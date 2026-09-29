package net.shurui.shuruisutilities.character;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Tracks when each player was last in combat (dealt to, or took, damage from another living entity) so
 * {@link CharacterSlots#switchTo} can refuse swaps for the lock window ({@link CharacterSlots#COMBAT_LOCK_PROP}).
 * Environmental damage (falls, fire, drowning) doesn't count. Death/logout clears the tag, so a respawned
 * player is never locked out of the picker.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class CharacterCombatTracker
{
    private static final Map<UUID, Long> LAST_COMBAT = new ConcurrentHashMap<>();

    private CharacterCombatTracker() {}

    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event)
    {
        if (event.getEntity().level().isClientSide || event.getAmount() <= 0)
            return;
        // took a hit from a living attacker
        if (event.getEntity() instanceof ServerPlayer victim
                && event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != victim)
            LAST_COMBAT.put(victim.getUUID(), System.currentTimeMillis());
        // dealt a hit to a living target
        if (event.getSource().getEntity() instanceof ServerPlayer attacker
                && event.getEntity() != attacker)
            LAST_COMBAT.put(attacker.getUUID(), System.currentTimeMillis());
    }

    @SubscribeEvent
    public static void onDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event)
    {
        if (event.getEntity() instanceof ServerPlayer p)
            LAST_COMBAT.remove(p.getUUID()); // death ends the fight; don't lock the respawned player
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event)
    {
        LAST_COMBAT.remove(event.getEntity().getUUID());
    }

    // millis of combat lock remaining (0 = free to swap)
    public static long lockRemainingMillis(ServerPlayer p, int lockSeconds)
    {
        if (lockSeconds <= 0)
            return 0;
        Long last = LAST_COMBAT.get(p.getUUID());
        if (last == null)
            return 0;
        long remaining = last + lockSeconds * 1000L - System.currentTimeMillis();
        return Math.max(0, remaining);
    }
}
