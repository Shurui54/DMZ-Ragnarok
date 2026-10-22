package net.shurui.shuruisutilities.swordsman;

import java.util.UUID;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Keeps the swordsman's two attribute modifiers in step with what the player holds, every server tick.
 *
 * <ul>
 *   <li>ATTACK_SPEED x(1 + {@link Swordsman#SPEED_KEY}): DMZ's own melee reads this attribute for its swing
 *       cooldown (PlayerAttackHelper) and its server rate check, so one modifier speeds up both.</li>
 *   <li>ATTACK_DAMAGE x(1 + {@link Swordsman#DAMAGE_KEY}): scales the held sword's own damage, which DMZ adds on top
 *       of its STR damage. The STR part is scaled by {@link SwordsmanPassive} instead. A drawn ki blade leaves the
 *       hand empty, so it gets only the speed and the STR part, never a damage modifier on bare fists.</li>
 * </ul>
 *
 * <p>Both are transient MULTIPLY_TOTAL modifiers with fixed ids, the same pattern DMZ uses for its own speed
 * modifiers, so they never reach the save and a respawned or reloaded player is simply re-evaluated. They are
 * reinstalled only when the wanted amount changes. Removal runs from here too (DMZ stops calling a passive the
 * moment the class changes, so the passive itself could never take them off).
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwordsmanEvents
{
    private SwordsmanEvents() {}

    static final UUID ATTACK_SPEED_ID = UUID.fromString("7301b711-3437-4384-90cd-dc7a5edf7dd6");
    static final UUID ATTACK_DAMAGE_ID = UUID.fromString("85cea580-d4b3-4e1c-bd63-6b226cb37021");

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END || !(event.player instanceof ServerPlayer player))
            return;
        double speed = 0.0;
        double damage = 0.0;
        try
        {
            StatsData data = StatsProvider.get(StatsCapability.INSTANCE, player).orElse(null);
            if (Swordsman.isSwordsman(data))
            {
                boolean held = Swordsman.isSword(player.getMainHandItem());
                if (held || Swordsman.kiSwordDrawn(player))
                {
                    speed = Swordsman.bonus(data, Swordsman.SPEED_KEY);
                    if (held)
                        damage = Swordsman.bonus(data, Swordsman.DAMAGE_KEY);
                }
            }
        }
        catch (Throwable ignored)
        {
            // never break the tick; with no answer both modifiers come off
        }
        apply(player, Attributes.ATTACK_SPEED, ATTACK_SPEED_ID, "Swordsman attack speed", speed);
        apply(player, Attributes.ATTACK_DAMAGE, ATTACK_DAMAGE_ID, "Swordsman sword damage", damage);
    }

    private static void apply(ServerPlayer player, Attribute attribute, UUID id, String name, double amount)
    {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null)
            return;
        AttributeModifier existing = inst.getModifier(id);
        double current = existing != null ? existing.getAmount() : 0.0;
        if (Math.abs(current - amount) <= 1.0E-9)
            return;
        inst.removeModifier(id);
        if (amount != 0.0)
            inst.addTransientModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }
}
