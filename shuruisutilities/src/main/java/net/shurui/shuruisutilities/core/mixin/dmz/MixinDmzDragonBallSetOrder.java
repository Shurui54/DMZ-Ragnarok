package net.shurui.shuruisutilities.core.mixin.dmz;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Make dragon ball block/item registration DETERMINISTIC across processes.
 *
 * <p>THE BUG. DMZ registers every dragon ball block (and its BlockItem) by ITERATING each set's star -> registry
 * name map. Both {@code MainBlocks.registerDragonBallBlocks} and {@code MainItems.registerDragonBallBlockItems}
 * do {@code set.getBlockRegistryNamesByStar().entrySet()} and call {@code DeferredRegister.register} in that
 * iteration order, so the ORDER this map iterates in is the order the seven blocks (and seven items) of a set are
 * registered, which is the order Forge assigns their numeric registry ids in.
 *
 * <p>WHY it was non-deterministic. {@code DragonBallSetDefinition}'s constructor stores that map as
 * {@code Map.copyOf(...)}. For a map of more than two entries {@code Map.copyOf} returns a
 * {@code java.util.ImmutableCollections.MapN}, whose iteration order is RANDOMISED PER JVM PROCESS by
 * {@code ImmutableCollections.SALT} (seeded from {@code System.nanoTime()} at class load). So each server launch
 * iterated a set's seven balls in a different order, and each backend therefore froze a different star -> id
 * assignment into its own world's registry snapshot. This did not touch DMZ's own earth / namek balls in
 * practice, because those ids were already pinned in every world's {@code level.dat} from a shared original world
 * and Forge preserves an existing id; only the LATER-added Black Star / Super / Cerulean balls, which had no
 * pre-existing pin, received fresh ids at the tail of the registry, in the randomised order, differing per
 * backend. The input order we hand DMZ (a {@code LinkedHashMap} filled 1..7) makes no difference: the
 * constructor's {@code Map.copyOf} discards it, so fixing this from the definition side is impossible and this
 * mixin is the only place the iteration order can be pinned.
 *
 * <p>THE FIX. Return a copy ordered by the star key (1, 2, .. 7) from every call to
 * {@code getBlockRegistryNamesByStar}, so both registration loops walk the balls in ascending star order on every
 * process. This is intrinsic and permanent (a ball's star number never changes), so every backend now agrees.
 * It applies to DMZ's own earth / namek sets too, which is harmless: their ids are pinned in existing worlds
 * (Forge keeps them), and ascending star order is the order those pinned ids already sit in, so no existing world
 * is disturbed and only fresh registrations become stable. New worlds created after this fix register every set,
 * ours and DMZ's, in the same deterministic order everywhere.
 *
 * <p>This does NOT and CANNOT converge the three worlds that already froze differing ids: numeric ids are
 * world-local and Forge preserves each world's existing snapshot. That divergence is harmless for a client
 * connected directly to a given backend (the server syncs its own id map to the client at login), and is not
 * something a registry remap should touch; see the report accompanying this change.
 *
 * <p>{@code remap = false}: the target class and {@code getBlockRegistryNamesByStar} are DMZ's own names, and the
 * shadowed field is DMZ's, so nothing here needs the refmap. {@code require = 0} per the standing rule for mixins
 * into DMZ classes: if DMZ renames the method or the field, this degrades to a no-op (DMZ's own, possibly
 * non-deterministic, order) instead of crashing mod load. A no-op only re-exposes the original ordering bug for
 * freshly created worlds; it never corrupts data.
 */
@Mixin(targets = "com.dragonminez.common.dragonball.DragonBallSetDefinition", remap = false)
public abstract class MixinDmzDragonBallSetOrder
{
    // DMZ's immutable star -> block registry name map, stored by the constructor as Map.copyOf(...), hence with a
    // per-process randomised iteration order. Shadowed read-only so we can re-order a copy of it.
    @Shadow(remap = false)
    @Final
    private Map<Integer, String> blockRegistryNamesByStar;

    @Inject(method = "getBlockRegistryNamesByStar", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$deterministicStarOrder(CallbackInfoReturnable<Map<Integer, String>> cir)
    {
        Map<Integer, String> source = this.blockRegistryNamesByStar;
        if (source == null || source.size() <= 1)
        {
            // Nothing to order (0 or 1 entries iterate identically everywhere); let DMZ return its own map.
            return;
        }
        // TreeMap gives ascending star order; copy into a LinkedHashMap so the returned type is a plain, mutable-free
        // insertion-ordered map with that same fixed order, matching what the callers iterate.
        Map<Integer, String> ordered = new LinkedHashMap<>(new TreeMap<>(source));
        cir.setReturnValue(ordered);
    }
}
