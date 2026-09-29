package net.shurui.shuruisutilities.core.mixin.dmz;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import net.minecraftforge.event.TickEvent;

/**
 * Makes a ki beam clash advance ONCE per server tick instead of once per loaded dimension per server tick.
 *
 * <h2>The bug</h2>
 *
 * <p>{@code BeamClashManager} keeps its clashes in a {@code static} list, {@code ACTIVE_CLASHES}, but advances that
 * whole list from {@code onLevelTick}, which Forge fires once for EVERY loaded level every tick:
 *
 * <pre>
 *     ServerLevel level2 = (ServerLevel) level;
 *     BeamClashManager.advanceActiveClashes();   // every clash, on every level's tick
 *     ...                                        // the rest correctly uses level2
 * </pre>
 *
 * <p>Everything after that line is properly scoped to the level being ticked; only the advance is global. So a
 * clash ticks once per loaded dimension: on a vanilla-ish server that is 3 and barely noticeable, and on this pack
 * it is dozens, which makes a clash resolve almost the instant it forms.
 *
 * <p>The maths make the effect concrete. {@code BeamClash.tick} moves the tug of war by
 * {@code 0.005 * (pushA - pushB)} and wins at 0.8, so a clash that should take several seconds of pressing instead
 * finishes in a handful of real ticks once that runs twenty or thirty times over.
 *
 * <h2>The fix</h2>
 *
 * <p>Redirect that one call so it happens only on the overworld's tick. The overworld always ticks on a dedicated
 * server, exactly once per server tick, so the rate stops depending on how many dimensions happen to be loaded,
 * without changing any of DMZ's constants and without touching the per-level work that follows.
 *
 * <h2>Why the rate is three and not one</h2>
 *
 * <p>Clamping to a single advance per server tick was the first attempt and it made clashes visibly sluggish. The
 * reason is that DMZ's constants were never tuned against one advance per tick: they were tuned by feel with this
 * bug present, on a stock install, where the loaded levels are the overworld, the nether and the end. So the rate
 * DMZ actually ships is about three advances per server tick, and one is a third of the speed anybody playing DMZ
 * has ever seen. On this pack, where dozens of dimensions load, the drop from the old behaviour was far steeper
 * again, which is what "clashes are too slow" was.
 *
 * <p>{@link #ADVANCES_PER_TICK} is therefore the speed dial, and it is a plain linear one: {@code BeamClash.tick}
 * moves the bar by {@code 0.005 * (pushA - pushB)} per advance and wins at {@code 0.8}, so the bar crosses in
 * {@code 160 / ((pushA - pushB) * ADVANCES_PER_TICK)} ticks. Raise it to make clashes shorter, lower it to make
 * them longer. It is deliberately a constant rather than a config key: it is a fidelity target (match stock DMZ),
 * not something an operator should be tuning per server.
 *
 * <p>Deliberately NOT done by filtering clashes to the ticking level: a clash's two beams can be in different
 * dimensions in principle, and the manager has no per-level index, so picking a level to own each clash would be
 * inventing a rule DMZ does not have. Advancing once globally is what the code already meant to do.
 *
 * <p>{@code remap = false}: the target and {@code advanceActiveClashes} are DMZ's own names. {@code require = 0}
 * per the standing rule, so if DMZ restructures this method the injector degrades to DMZ's own (fast) behaviour
 * rather than failing mod load.
 */
@Mixin(targets = "com.dragonminez.common.combat.clash.BeamClashManager", remap = false)
public abstract class MixinDmzClashTickRate
{
    /**
     * Advances per server tick. Three, because that is what a stock DMZ install does (overworld, nether, end) and
     * therefore what its clash constants were tuned against. See the class javadoc: this is the speed dial.
     */
    private static final int ADVANCES_PER_TICK = 3;

    /** DMZ's own private advance. Shadowed so the guard below can still run it when it should. */
    @Shadow(remap = false)
    private static void advanceActiveClashes()
    {
        throw new AssertionError("shadow");
    }

    @Redirect(
            method = "onLevelTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/dragonminez/common/combat/clash/BeamClashManager;advanceActiveClashes()V",
                    remap = false),
            require = 0,
            remap = false)
    private static void su$advanceOncePerServerTick(TickEvent.LevelTickEvent event)
    {
        try
        {
            Level level = event.level;
            if (!(level instanceof ServerLevel serverLevel))
            {
                return;
            }
            MinecraftServer server = serverLevel.getServer();
            if (server != null && serverLevel != server.overworld())
            {
                // another dimension's tick: the overworld's tick already advanced every clash this server tick.
                return;
            }
            // Called in a loop rather than by scaling DMZ's step, because the step is inside BeamClash.tick and
            // advancing repeatedly is exactly what the unpatched code did. Anything that reads as a per-advance
            // event (a win check, a particle) therefore still fires the number of times DMZ expects.
            for (int i = 0; i < ADVANCES_PER_TICK; i++)
            {
                advanceActiveClashes();
            }
        }
        catch (Throwable t)
        {
            // fail open, in DMZ's direction: advancing is better than a clash that never resolves at all.
            advanceActiveClashes();
        }
    }
}
