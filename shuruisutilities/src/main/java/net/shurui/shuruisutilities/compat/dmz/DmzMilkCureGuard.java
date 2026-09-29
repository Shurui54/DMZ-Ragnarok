package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.entity.living.MobEffectEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Stops a bucket of milk undoing DragonMineZ's status effects.
 *
 * <h2>Why milk was the problem</h2>
 * DMZ's effects are the consequences of its combat: the stun that follows a strike lock, the exhaustion that
 * follows a manoeuvre someone could not afford. Milk is a bucket of a common liquid that cancels every one of them
 * at once, which made every such consequence optional for anybody with a cow, and made the abilities that impose
 * them worth nothing in a fight.
 *
 * <h2>Why the curative list and not the removal event</h2>
 * Forge decides whether an item cures an effect by asking the EFFECT INSTANCE which items cure it, so emptying
 * that list is the exact statement "no item cures this", which is what was wanted. The obvious alternative,
 * cancelling {@code MobEffectEvent.Remove}, is far too broad: that event does not say what asked for the removal,
 * so cancelling it would also break {@code /effect clear}, DMZ clearing its own effects, and any other mod's
 * legitimate cleanup. Natural expiry does not go through either path and is untouched.
 *
 * <p>Applied as each effect ARRIVES rather than by patching the effect type, so it holds for every source that
 * applies one and needs no mixin. The one consequence is that an instance already on a player before this loaded
 * keeps its old curative list until it is reapplied, which resolves itself the next time the effect lands.
 *
 * <p>Scoped by namespace: only effects registered by {@code dragonminez}. Vanilla poison, and every other mod's
 * effects, still come off with milk exactly as players expect.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class DmzMilkCureGuard
{
    private DmzMilkCureGuard() {}

    private static final String DMZ = "dragonminez";

    @SubscribeEvent
    public static void onEffectAdded(MobEffectEvent.Added event)
    {
        if (event.getEffectInstance() == null)
            return;
        ResourceLocation id = BuiltInRegistries.MOB_EFFECT.getKey(event.getEffectInstance().getEffect());
        if (id == null || !DMZ.equals(id.getNamespace()))
            return;
        // Empty, not null: Forge iterates this list, and a null would be a crash on the next bucket of milk.
        // A fresh mutable list rather than emptyList(), because the stored list is Forge's to hand out afterwards
        // and anything that tried to add to an immutable one would throw from inside somebody else's code.
        event.getEffectInstance().setCurativeItems(new ArrayList<>());
    }
}
